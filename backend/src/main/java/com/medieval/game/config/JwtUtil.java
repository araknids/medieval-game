package com.medieval.game.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
public class JwtUtil {

    /** Valor padrão de dev (definido em application.properties). Nunca pode ir para produção. */
    static final String DEV_DEFAULT_SECRET = "chave_local_dev_nao_usar_em_producao";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    private static final long EXPIRATION_MS = 7L * 24 * 60 * 60 * 1000; // 7 dias

    // SEGURANÇA: aborta o boot se o JWT_SECRET não foi definido em produção — senão
    // qualquer um forjaria tokens com a string padrão que está no repositório. [AUDITORIA M6]
    @PostConstruct
    void validateSecret() {
        boolean prod = activeProfile != null && activeProfile.contains("prod");
        if (prod && (secret == null || secret.isBlank() || DEV_DEFAULT_SECRET.equals(secret))) {
            throw new IllegalStateException(
                "JWT_SECRET não definido em produção. Configure a variável de ambiente JWT_SECRET "
                + "com uma string longa e aleatória antes de subir.");
        }
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            log.warn("[JwtUtil] Usando o JWT secret padrão de DEV — ok só em desenvolvimento.");
        }
    }

    public String generateToken(Long playerId, String username) {
        return JWT.create()
                .withSubject(String.valueOf(playerId))
                .withClaim("username", username)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .sign(Algorithm.HMAC256(secret));
    }

    public DecodedJWT validate(String token) throws JWTVerificationException {
        return JWT.require(Algorithm.HMAC256(secret)).build().verify(token);
    }
}
