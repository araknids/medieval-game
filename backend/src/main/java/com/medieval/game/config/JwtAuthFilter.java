package com.medieval.game.config;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.medieval.game.repository.PlayerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil          jwtUtil;
    private final PlayerRepository playerRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                DecodedJWT decoded = jwtUtil.validate(token);
                Long playerId = Long.parseLong(decoded.getSubject());
                String username = decoded.getClaim("username").asString();

                // M6: rejeita tokens emitidos antes da última troca/reset de senha do player.
                if (issuedBeforePasswordChange(playerId, decoded.getIssuedAt())) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("{\"error\": \"Sessão expirada (senha alterada). Faça login novamente.\"}");
                    return;
                }

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(playerId, username, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JWTVerificationException e) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"error\": \"Token inválido ou expirado\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /** True se o token foi emitido antes do último reset de senha (→ inválido). [AUDITORIA M6]
     *  Compara em granularidade de SEGUNDO (o `iat` do JWT é em segundos), assim um token emitido no
     *  mesmo segundo do reset (ex.: o novo login logo após) não é rejeitado por engano. */
    private boolean issuedBeforePasswordChange(Long playerId, Date issuedAt) {
        if (issuedAt == null) return false; // sem iat → não bloqueia
        java.time.LocalDateTime validFrom = playerRepository.findTokenValidFrom(playerId).orElse(null);
        if (validFrom == null) return false; // sem restrição
        long iatSec       = issuedAt.toInstant().getEpochSecond();
        long validFromSec = validFrom.atZone(ZoneId.systemDefault()).toEpochSecond();
        return iatSec < validFromSec;
    }
}
