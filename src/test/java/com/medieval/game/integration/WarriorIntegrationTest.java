package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-055 a TC-058 — Guerreiro
@DisplayName("TC-055-058 | Warrior — Stats e Atributos")
class WarriorIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("wtest"));
    }

    // ── TC-055: GET /api/warrior retorna todos os campos esperados ──
    @Test
    @DisplayName("TC-055 | GET /api/warrior → campos hp, stamina, bronze, silver, gold presentes")
    void tc055_getWarrior_hasAllFields() throws Exception {
        mockMvc.perform(get("/api/warrior")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hpPercent").value(100))
                .andExpect(jsonPath("$.stamina").isNumber())
                .andExpect(jsonPath("$.bronze").isNumber())
                .andExpect(jsonPath("$.silver").isNumber())
                .andExpect(jsonPath("$.gold").isNumber())
                .andExpect(jsonPath("$.availablePoints").isNumber())
                .andExpect(jsonPath("$.evasionChance").isNumber());
    }

    // ── TC-056: POST /api/warrior/attributes/STRENGTH sem pontos → 400 ──
    @Test
    @DisplayName("TC-056 | POST /api/warrior/attributes/STRENGTH sem pontos → 400")
    void tc056_spendPoint_noPoints_returns400() throws Exception {
        mockMvc.perform(post("/api/warrior/attributes/STRENGTH")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("ponto")));
    }

    // ── TC-057: GET /api/warrior sem token → 403 (Spring Security default) ──
    @Test
    @DisplayName("TC-057 | GET /api/warrior sem Authorization → 403")
    void tc057_getWarrior_noToken_forbidden() throws Exception {
        mockMvc.perform(get("/api/warrior"))
                .andExpect(status().is(403));
    }

    // ── TC-058: POST /api/warrior/free → resposta ok (guerreiro já está livre) ──
    @Test
    @DisplayName("TC-058 | POST /api/warrior/free quando livre → 200")
    void tc058_freeWarrior_alreadyFree_returnsOk() throws Exception {
        mockMvc.perform(post("/api/warrior/free")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }
}
