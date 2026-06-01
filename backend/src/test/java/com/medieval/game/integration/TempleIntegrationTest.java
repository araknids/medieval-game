package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-096 a TC-102 - Templo
@DisplayName("TC-096-102 | Temple - Cura, Bencao e Protecao")
class TempleIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("temple"));
    }

    // TC-096: GET /api/temple retorna info do templo
    @Test
    @DisplayName("TC-096 | GET /api/temple - healCost e buffs presentes")
    void tc096_getTemple_returnsCosts() throws Exception {
        mockMvc.perform(get("/api/temple")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healCost").isNumber())
                .andExpect(jsonPath("$.buffs").isArray());
    }

    // TC-097: GET /api/temple - protectedCount presente
    @Test
    @DisplayName("TC-097 | GET /api/temple - protectedCount presente")
    void tc097_getTemple_hasProtectedCount() throws Exception {
        mockMvc.perform(get("/api/temple")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protectedCount").isNumber());
    }

    // TC-098: POST /api/temple/protect/99999 - item nao encontrado
    @Test
    @DisplayName("TC-098 | POST /api/temple/protect/99999 - 4xx item nao encontrado")
    void tc098_protectItem_notFound_returns4xx() throws Exception {
        mockMvc.perform(post("/api/temple/protect/99999")
                        .header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError());
    }

    // TC-099: POST /api/temple/heal com HP cheio - 400
    @Test
    @DisplayName("TC-099 | POST /api/temple/heal com HP cheio - 400")
    void tc099_healAtFullHp_returns400() throws Exception {
        mockMvc.perform(post("/api/temple/heal")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-100: POST /api/temple/buff/STRENGTH com silver inicial - 200 e buff ativado
    @Test
    @DisplayName("TC-100 | POST /api/temple/buff/STRENGTH - warrior tem silver, buff ativado")
    void tc100_buff_withSilver_returns200() throws Exception {
        mockMvc.perform(post("/api/temple/buff/STRENGTH")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.buff").value("STRENGTH"));
    }

    // TC-101: POST /api/temple/unprotect/99999 - nao encontrado
    @Test
    @DisplayName("TC-101 | POST /api/temple/unprotect/99999 - 4xx nao encontrado")
    void tc101_unprotectItem_notFound_returns4xx() throws Exception {
        mockMvc.perform(post("/api/temple/unprotect/99999")
                        .header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError());
    }

    // TC-102: GET /api/temple - healFree true para guerreiro nivel 1
    @Test
    @DisplayName("TC-102 | GET /api/temple - healFree = true para guerreiro nivel <= 10")
    void tc102_getTemple_newWarriorHealFree() throws Exception {
        mockMvc.perform(get("/api/temple")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healFree").value(true));
    }
}
