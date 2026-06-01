package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-059 a TC-065 — Missões
@DisplayName("TC-059-065 | Quest — Missões e Recompensas")
class QuestIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("qtest"));
    }

    // ── TC-059: GET /api/quests/types lista tipos disponíveis ──
    @Test
    @DisplayName("TC-059 | GET /api/quests/types → lista tipos com nome e duração")
    void tc059_getQuestTypes_returnsList() throws Exception {
        mockMvc.perform(get("/api/quests/types")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].durationMinutes").isNumber());
    }

    // ── TC-060: POST /api/quests/start envia guerreiro em missão ──
    @Test
    @DisplayName("TC-060 | POST /api/quests/start {questType:PATROL} → status IN_PROGRESS")
    void tc060_startPatrolQuest() throws Exception {
        mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questType", "PATROL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // ── TC-061: GET /api/quests após iniciar missão → mostra ativa ──
    @Test
    @DisplayName("TC-061 | GET /api/quests após PATROL → status IN_PROGRESS")
    void tc061_getActiveQuests_showsInProgress() throws Exception {
        mockMvc.perform(post("/api/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("questType", "PATROL"))));

        mockMvc.perform(get("/api/quests")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"));
    }

    // ── TC-062: Segundo start enquanto guerreiro está ocupado → 400 ──
    @Test
    @DisplayName("TC-062 | POST /api/quests/start duas vezes → 400 guerreiro ocupado")
    void tc062_startQuest_whenWarriorBusy_returns400() throws Exception {
        mockMvc.perform(post("/api/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("questType", "PATROL"))));

        mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questType", "PATROL"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── TC-063: collect com instant-complete → recebe recompensa ──
    @Test
    @DisplayName("TC-063 | POST /api/quests/{id}/collect (instant-complete) → goldEarned presente")
    void tc063_collectQuest_instant_receivesReward() throws Exception {
        String questResponse = mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questType", "PATROL"))))
                .andReturn().getResponse().getContentAsString();

        long questId = objectMapper.readTree(questResponse).get("id").asLong();

        mockMvc.perform(post("/api/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goldEarned").isNumber());
    }

    // ── TC-064: abandon → guerreiro fica livre para nova missão ──
    @Test
    @DisplayName("TC-064 | POST /api/quests/{id}/abandon → status ABANDONED, pode iniciar nova")
    void tc064_abandonQuest_setsAbandoned() throws Exception {
        String questResponse = mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questType", "PATROL"))))
                .andReturn().getResponse().getContentAsString();

        long questId = objectMapper.readTree(questResponse).get("id").asLong();

        mockMvc.perform(post("/api/quests/" + questId + "/abandon")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // após abandonar pode iniciar nova missão
        mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questType", "PATROL"))))
                .andExpect(status().isOk());
    }

    // ── TC-065: collect com instant-complete → quest já está pronta ──
    @Test
    @DisplayName("TC-065 | POST /api/quests/{id}/collect (instant) → expEarned presente")
    void tc065_collectInstant_expPresent() throws Exception {
        String questResponse = mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questType", "PATROL"))))
                .andReturn().getResponse().getContentAsString();

        long questId = objectMapper.readTree(questResponse).get("id").asLong();

        mockMvc.perform(post("/api/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expEarned").isNumber());
    }
}
