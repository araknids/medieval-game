package com.medieval.game.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter    jwtAuthFilter;
    private final LoginRateLimiter loginRateLimiter; // [LAUNCH_HARDENING] reusado pelo throttle global da API

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile; // [AUDITORIA_2 A8] proíbe CORS '*' em prod

    // [LAUNCH_HARDENING] Teto volumétrico da API (/api/**) — folgado p/ jogo normal, instantâneo p/ flood.
    // Ligado em prod (default true); desligado em dev/teste via application-{dev,pgtest}.properties.
    @Value("${app.ratelimit.enabled:true}")
    private boolean rlEnabled;
    @Value("${app.ratelimit.window-ms:10000}")
    private long rlWindowMs;
    @Value("${app.ratelimit.max-requests:60}")
    private int rlMaxRequests;
    @Value("${app.ratelimit.max-body-bytes:65536}")
    private long rlMaxBodyBytes;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/server-info").permitAll() // [SERVIDORES] tela de login (pré-auth)
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/", "/index.html", "/style.css", "/app.js", "/battleArena.js", "/favicon.ico", "/servers.json").permitAll()
                        .requestMatchers("/lang/**").permitAll()
                        .requestMatchers("/assets/**").permitAll() // [BATALHA_ANIMADA] sprites/imagens estáticas (img sem JWT)
                        .anyRequest().authenticated()
                )
                // Headers de segurança. frameOptions=SAMEORIGIN (em vez de disable) ainda
                // permite o console H2 na mesma origem e bloqueia clickjacking cross-origin.
                // CSP estrita ficou de fora p/ não quebrar o frontend (inline) — ver backlog. [AUDITORIA B1]
                .headers(h -> h
                        .frameOptions(f -> f.sameOrigin())
                        .contentTypeOptions(c -> {}) // X-Content-Type-Options: nosniff
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // [LAUNCH_HARDENING] roda DEPOIS do JwtAuthFilter → o principal (playerId) já está no contexto,
                // então o throttle é por-jogador quando autenticado (e por-IP no resto).
                .addFilterAfter(new ApiRateLimitFilter(rlEnabled, loginRateLimiter, rlWindowMs, rlMaxRequests, rlMaxBodyBytes),
                        JwtAuthFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        if ("*".equals(allowedOrigins.trim())) {
            // [AUDITORIA_2 A8] '*' só em dev/test. Em prod falha o boot (evita abrir CORS por engano
            // ao tentar "consertar" um erro de CORS). Sempre defina origens explícitas em produção.
            if (activeProfile != null && activeProfile.contains("prod"))
                throw new IllegalStateException("[SEGURANÇA] APP_CORS_ALLOWED_ORIGINS='*' não é permitido em prod — defina origens explícitas.");
            config.addAllowedOriginPattern("*");
        } else {
            config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
