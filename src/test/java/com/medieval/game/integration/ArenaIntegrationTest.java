package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-066 a TC-067 — Arena
@DisplayName("TC-066-067 | Arena — Duelo PvP")
class ArenaIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("arena"));
        // cria segundo guerreiro para ter oponente no ranking
        registerAndGetToken(uniqueUser("arenaOpp"));
    }

    // ── TC-066: GET /api/arena/rank → lista ranking ──
    @Test
    @DisplayName("TC-066 | GET /api/arena/rank → retorna lista de guerreiros")
    void tc066_getArenaRank_returnsList() throws Exception {
        mockMvc.perform(get("/api/arena/rank")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── TC-067: POST /api/arena/fight → inicia duelo, collect → log de batalha ──
    @Test
    @DisplayName("TC-067 | POST /api/arena/fight → collect → log com won/opponent")
    void tc067_fightAndCollect_returnsBattleLog() throws Exception {
        // inicia duelo
        String fightResponse = mockMvc.perform(post("/api/arena/fight")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn().getResponse().getContentAsString();

        long matchId = objectMapper.readTree(fightResponse).get("id").asLong();

        // coleta resultado (instant-complete → já finalizado)
        mockMvc.perform(post("/api/arena/" + matchId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opponent").isNotEmpty())
                .andExpect(jsonPath("$.log").isArray());
    }
}
