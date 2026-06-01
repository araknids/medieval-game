package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-071 a TC-074 — Trabalho
@DisplayName("TC-071-074 | Work — Trabalho e Profissoes")
class WorkIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("work"));
    }

    // ── TC-071: GET /api/work/jobs → lista tipos de trabalho ──
    @Test
    @DisplayName("TC-071 | GET /api/work/jobs → lista trabalhos com displayName")
    void tc071_getJobs_returnsList() throws Exception {
        mockMvc.perform(get("/api/work/jobs")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].displayName").isNotEmpty());
    }

    // ── TC-072: POST /api/work/start com TAVERN_HELPER (minLevel=0) → ok ──
    @Test
    @DisplayName("TC-072 | POST /api/work/start {TAVERN_HELPER,1} → jobName presente")
    void tc072_startWork_returnsSession() throws Exception {
        mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("workType", "TAVERN_HELPER", "hours", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.jobName").isNotEmpty());
    }

    // ── TC-073: POST /api/work/start duas vezes → 400 ──
    @Test
    @DisplayName("TC-073 | POST /api/work/start duas vezes → 400 guerreiro ocupado")
    void tc073_startWork_whenBusy_returns400() throws Exception {
        mockMvc.perform(post("/api/work/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("workType", "TAVERN_HELPER", "hours", 1))));

        mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("workType", "TAVERN_HELPER", "hours", 1))))
                .andExpect(status().isBadRequest());
    }

    // ── TC-074: POST /api/work/{id}/cancel → cancelled:true ──
    @Test
    @DisplayName("TC-074 | POST /api/work/{id}/cancel → cancelled:true, pode iniciar novo")
    void tc074_cancelWork_warriorFree() throws Exception {
        String startResponse = mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("workType", "TAVERN_HELPER", "hours", 4))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long sessionId = objectMapper.readTree(startResponse).get("id").asLong();

        mockMvc.perform(post("/api/work/" + sessionId + "/cancel")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(true));

        // após cancelar, pode trabalhar novamente
        mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("workType", "TAVERN_HELPER", "hours", 1))))
                .andExpect(status().isOk());
    }
}
