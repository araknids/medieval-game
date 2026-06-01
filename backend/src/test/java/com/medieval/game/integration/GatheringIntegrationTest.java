package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-081 a TC-085 — Coleta (Pesca e Mineracao)
@DisplayName("TC-081-085 | Gathering — Pesca e Mineracao")
class GatheringIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("gather"));
    }

    // ── TC-081: POST /api/gathering/start FISHING (min 5 min) → ok ──
    @Test
    @DisplayName("TC-081 | POST /api/gathering/start {FISHING,5} → id e skillType presentes")
    void tc081_startFishing_success() throws Exception {
        mockMvc.perform(post("/api/gathering/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("skillType", "FISHING", "durationMinutes", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.skillType").value("FISHING"));
    }

    // ── TC-082: POST /api/gathering/start MINING (min 10 min) → ok ──
    @Test
    @DisplayName("TC-082 | POST /api/gathering/start {MINING,10} → id e skillType presentes")
    void tc082_startMining_success() throws Exception {
        mockMvc.perform(post("/api/gathering/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("skillType", "MINING", "durationMinutes", 10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.skillType").value("MINING"));
    }

    // ── TC-083: POST /api/gathering/start com guerreiro em missao → 400 ──
    @Test
    @DisplayName("TC-083 | POST /api/gathering/start com guerreiro em missao → 400")
    void tc083_gather_whenOnQuest_returns400() throws Exception {
        mockMvc.perform(post("/api/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("questType", "PATROL"))));

        mockMvc.perform(post("/api/gathering/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("skillType", "FISHING", "durationMinutes", 5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── TC-084: GET /api/gathering/skills → array com skillType e level ──
    @Test
    @DisplayName("TC-084 | GET /api/gathering/skills → array com skillType e level")
    void tc084_getSkills_returnsArray() throws Exception {
        mockMvc.perform(get("/api/gathering/skills")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].skillType").isNotEmpty())
                .andExpect(jsonPath("$[0].level").isNumber());
    }

    // ── TC-085: POST /api/gathering/start depois de FISHING ativa → 400 ──
    @Test
    @DisplayName("TC-085 | POST /api/gathering/start duas vezes → 400 sessao ativa")
    void tc085_startGathering_twice_returns400() throws Exception {
        // primeira sessao com FISHING (min 5 min) — fica IN_PROGRESS
        mockMvc.perform(post("/api/gathering/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("skillType", "FISHING", "durationMinutes", 5))));

        // segunda start deve falhar pois a sessao ainda nao foi coletada
        mockMvc.perform(post("/api/gathering/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("skillType", "FISHING", "durationMinutes", 5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
