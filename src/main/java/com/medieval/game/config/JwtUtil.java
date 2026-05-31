package com.medieval.game.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String secret;

    private static final long EXPIRATION_MS = 7L * 24 * 60 * 60 * 1000; // 7 dias

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
