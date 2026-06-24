package com.medieval.game.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * [LAUNCH_HARDENING] Teto volumétrico + cap de corpo para TODA a API (/api/**).
 *
 * Motivação: o Railway só mitiga ataque em camada 4; o flood HTTP (L7) eles mesmos dizem que "pode não
 * ser suficiente" — então é por nossa conta. O gate natural do jogo é a ESTAMINA, mas (a) endpoints de
 * LEITURA não custam estamina e (b) um cliente com 1 JWT válido pode martelar qualquer endpoint num loop.
 * Sem isto, um único cliente satura as threads do Tomcat e o pool do banco e derruba a instância p/ todos.
 *
 * Implementação: contador por janela curta, em memória, por chave = player autenticado (justo entre quem
 * compartilha NAT) ou, na falta, IP (right-most X-Forwarded-For, igual ao limiter de auth). Single-instance
 * basta (1 app por servidor [SERVIDORES]); Redis/distribuído seria over-engineering nessa escala. Reusa o
 * {@link LoginRateLimiter} (mesmo mapa limitado e sincronizado já auditado). Roda DEPOIS do
 * {@link JwtAuthFilter} na cadeia de segurança, então o principal já está no contexto. O brute-force de
 * auth tem seu próprio limiter mais estrito ([AUDITORIA A10]); aqui é só o backstop de volume.
 *
 * Limites configuráveis por env (sem deploy). NÃO é {@code @Component} de propósito: é instanciado em
 * {@link SecurityConfig} e adicionado só à cadeia (evita auto-registro pelo container fora de ordem).
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final LoginRateLimiter limiter;
    private final long windowMs;
    private final int  maxRequests;
    private final long maxBodyBytes;

    public ApiRateLimitFilter(boolean enabled, LoginRateLimiter limiter, long windowMs, int maxRequests, long maxBodyBytes) {
        this.enabled      = enabled;
        this.limiter      = limiter;
        this.windowMs     = windowMs;
        this.maxRequests  = maxRequests;
        this.maxBodyBytes = maxBodyBytes;
    }

    /** Só /api/** — estáticos e o console H2 passam direto. Desligado (dev/teste) → não filtra nada. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Corpo absurdo → 413 antes de o Jackson tentar desserializar (payload-bomb pré-parse).
        if (request.getContentLengthLong() > maxBodyBytes) {
            reject(response, 413, "Request body too large.");
            return;
        }
        String key = rateKey(request);
        if (limiter.isBlocked(key, maxRequests, windowMs)) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, windowMs / 1000)));
            reject(response, 429, "Too many requests. Slow down.");
            return;
        }
        limiter.recordAttempt(key, windowMs);
        chain.doFilter(request, response);
    }

    /** Player autenticado (principal = playerId, posto pelo JwtAuthFilter) ou, na falta, IP. */
    private String rateKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long pid) return "api-u:" + pid;
        return "api-ip:" + clientIp(request);
    }

    /** Igual ao AuthController: o IP REAL é o ÚLTIMO do X-Forwarded-For (anexado pelo proxy confiável do
     *  Railway); o cliente pode falsificar os primeiros p/ tentar rotacionar a chave. */
    private String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isBlank()) return last;
        }
        return http.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
