package com.medieval.game.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// A10 — rate limiting + mensagem genérica (sem enumeração de usuário). docs/AUDITORIA_CONSELHO.md
@DisplayName("Auditoria A10 | Rate limit de login/reset + mensagem genérica")
class AuthRateLimitTest extends BaseIntegrationTest {

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginFrom(
            String ip, String user, String pass) throws Exception {
        return post("/api/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", user, "password", pass)));
    }

    // ── A10a: usuário inexistente e senha errada devolvem a MESMA mensagem ──
    @Test
    @DisplayName("A10a | Mensagem de login é genérica (não revela se o usuário existe)")
    void a10a_genericMessage() throws Exception {
        String user = uniqueUser("rlmsg");
        registerAndGetToken(user);

        // senha errada
        mockMvc.perform(loginFrom("10.10.0.1", user, "senhaErrada"))
                .andExpect(status().is(401))
                .andExpect(jsonPath("$.error").value("Invalid username or password"));

        // usuário inexistente → mesma mensagem
        mockMvc.perform(loginFrom("10.10.0.2", "fantasma_inexistente", "qualquer"))
                .andExpect(status().is(401))
                .andExpect(jsonPath("$.error").value("Invalid username or password"));
    }

    // ── A10b: após 10 falhas no mesmo IP+usuário, bloqueia com 429 ──
    @Test
    @DisplayName("A10b | 11ª tentativa de login falha com 429 (rate limit)")
    void a10b_loginRateLimited() throws Exception {
        String user = uniqueUser("rlblock");
        registerAndGetToken(user);
        String ip = "10.20.0.1";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(loginFrom(ip, user, "errada" + i))
                    .andExpect(status().is(401));
        }
        // limite estourado
        mockMvc.perform(loginFrom(ip, user, "maisuma"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── A10c: login bem-sucedido limpa o contador (não bloqueia depois) ──
    @Test
    @DisplayName("A10c | Login correto reseta o contador de falhas")
    void a10c_successResets() throws Exception {
        String user = uniqueUser("rlreset");
        registerAndGetToken(user);
        String ip = "10.30.0.1";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(loginFrom(ip, user, "errada" + i)).andExpect(status().is(401));
        }
        // sucesso (senha do BaseIntegrationTest = senha123) limpa o contador
        mockMvc.perform(loginFrom(ip, user, "senha123")).andExpect(status().isOk());
        // novas falhas recomeçam do zero — ainda não bloqueia
        mockMvc.perform(loginFrom(ip, user, "errada-again")).andExpect(status().is(401));
    }

    // ── A10d: forgot-password é limitado por IP (anti-spam de email) ──
    @Test
    @DisplayName("A10d | 6ª solicitação de reset no mesmo IP → 429")
    void a10d_forgotRateLimited() throws Exception {
        String ip = "10.40.0.1";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/forgot-password")
                            .header("X-Forwarded-For", ip)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(json(Map.of("email", "x" + i + "@test.com"))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/auth/forgot-password")
                        .header("X-Forwarded-For", ip)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "x6@test.com"))))
                .andExpect(status().is(429));
    }
}
