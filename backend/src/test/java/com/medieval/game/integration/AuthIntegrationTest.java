package com.medieval.game.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-051 a TC-054 — Autenticação
@DisplayName("TC-051-054 | Auth — Cadastro e Login")
class AuthIntegrationTest extends BaseIntegrationTest {

    // ── TC-051: Cadastro bem-sucedido retorna token e cria guerreiro ──
    @Test
    @DisplayName("TC-051 | POST /api/auth/register → 200, token presente, guerreiro criado")
    void tc051_register_success() throws Exception {
        String user = uniqueUser("tc051");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username",    user,
                                "email",       user + "@test.com",
                                "password",    "senha123",
                                "warriorName", "Aldeão " + user
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.warrior.name").value("Aldeão " + user));
    }

    // ── TC-052: Username duplicado → 400 ──
    @Test
    @DisplayName("TC-052 | POST /api/auth/register com username duplicado → 400")
    void tc052_register_duplicateUsername() throws Exception {
        String user = uniqueUser("tc052");
        registerAndGetToken(user); // cria o primeiro

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username",    user,
                                "email",       user + "_2@test.com",
                                "password",    "senha123",
                                "warriorName", "Guerreiro"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── TC-053: Login com senha errada → 401 ──
    @Test
    @DisplayName("TC-053 | POST /api/auth/login com senha errada → 401")
    void tc053_login_wrongPassword() throws Exception {
        String user = uniqueUser("tc053");
        registerAndGetToken(user);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", user,
                                "password", "senhaErrada"
                        ))))
                .andExpect(status().is(401));
    }

    // ── TC-054: Forgot-password sempre retorna 200 (não revela se email existe) ──
    @Test
    @DisplayName("TC-054 | POST /api/auth/forgot-password → sempre 200")
    void tc054_forgotPassword_alwaysReturns200() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "naoexiste@naoexiste.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
