package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-068 a TC-070 — Torre Infernal
@DisplayName("TC-068-070 | Tower — Torre Infernal")
class TowerIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("tower"));
    }

    // ── TC-068: GET /api/tower/current sem run ativa → active:false ──
    @Test
    @DisplayName("TC-068 | GET /api/tower/current sem run ativa → active:false")
    void tc068_getTowerCurrent_noRun_activeFalse() throws Exception {
        mockMvc.perform(get("/api/tower/current")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // ── TC-069: POST /api/tower/enter + GET current → active:true com currentFloor ──
    @Test
    @DisplayName("TC-069 | POST /api/tower/enter → GET current → active:true e currentFloor")
    void tc069_enterTower_currentShowsActiveRun() throws Exception {
        mockMvc.perform(post("/api/tower/enter")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentFloor").isNumber());

        mockMvc.perform(get("/api/tower/current")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.currentFloor").isNumber());
    }

    // ── TC-070: POST /api/tower/enter com run já ativa → 400 ──
    // [SEM_TIMER] sem 'busy' cruzado; o guard que sobra é o próprio da torre (uma run por vez).
    @Test
    @DisplayName("TC-070 | POST /api/tower/enter com run já ativa → 400")
    void tc070_enterTower_whenAlreadyInRun_returns400() throws Exception {
        mockMvc.perform(post("/api/tower/enter")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tower/enter")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
