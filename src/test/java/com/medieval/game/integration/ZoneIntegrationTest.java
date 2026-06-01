package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-092 a TC-095 - Zonas de Expedicao
@DisplayName("TC-092-095 | Zone - Expedicao")
class ZoneIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("zone"));
    }

    // TC-092: GET /api/zones retorna lista de zonas
    @Test
    @DisplayName("TC-092 | GET /api/zones - lista zonas")
    void tc092_getZones_returnsList() throws Exception {
        mockMvc.perform(get("/api/zones")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))));
    }

    // TC-093: POST /api/zones/enter (SAFE, HUNTING, 30 min) - expedicao criada
    @Test
    @DisplayName("TC-093 | POST /api/zones/enter SAFE HUNTING 30min - id e zone presentes")
    void tc093_enterZone_returnsActivity() throws Exception {
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"HUNTING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.zone").value("SAFE"));
    }

    // TC-094: guerreiro em missao nao pode entrar em zona
    @Test
    @DisplayName("TC-094 | POST /api/zones/enter com guerreiro em missao - 400")
    void tc094_enterZone_whenOnQuest_returns400() throws Exception {
        mockMvc.perform(post("/api/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questType\":\"PATROL\"}"));

        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"HUNTING\",\"durationMinutes\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-095: POST enter + collect (instant-complete) - coleta resultado
    @Test
    @DisplayName("TC-095 | POST /api/zones/enter + collect (instant) - retorna resultado")
    void tc095_enterAndCollect_returnsResult() throws Exception {
        String enterResponse = mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"HUNTING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long activityId = objectMapper.readTree(enterResponse).get("id").asLong();

        mockMvc.perform(post("/api/zones/" + activityId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }
}
