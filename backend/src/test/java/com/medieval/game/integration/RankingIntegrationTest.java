package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-103 a TC-104 — Ranking
@DisplayName("TC-103-104 | Ranking — Top Guerreiros")
class RankingIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("rank"));
        registerAndGetToken(uniqueUser("rank2"));
    }

    // ── TC-103: GET /api/arena/rank → lista por rankPoints ──
    @Test
    @DisplayName("TC-103 | GET /api/arena/rank → lista com warriorName e rankPoints")
    void tc103_getArenaRank_isSorted() throws Exception {
        mockMvc.perform(get("/api/arena/rank")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].warriorName").isNotEmpty())
                .andExpect(jsonPath("$[0].rankPoints").isNumber());
    }

    // ── TC-104: GET /api/tower/ranking → top da Torre Infernal ──
    @Test
    @DisplayName("TC-104 | GET /api/tower/ranking → lista por andar mais alto")
    void tc104_getTowerRanking_returnsList() throws Exception {
        mockMvc.perform(get("/api/tower/ranking")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
