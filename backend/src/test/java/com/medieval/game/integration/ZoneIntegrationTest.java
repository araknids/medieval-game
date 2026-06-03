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

    // TC-093: POST /api/zones/enter (SAFE, GATHERING/FISHING, 30 min) - expedicao criada
    @Test
    @DisplayName("TC-093 | POST /api/zones/enter SAFE GATHERING 30min - id e zone presentes")
    void tc093_enterZone_returnsActivity() throws Exception {
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
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
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
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
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long activityId = objectMapper.readTree(enterResponse).get("id").asLong();

        mockMvc.perform(post("/api/zones/" + activityId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    // ── TC-096: freeIfStuck cancels ZoneActivity → re-enter works ────────────
    // This test catches the specific bug: warrior freed via /api/warrior/free
    // left a ZoneActivity IN_PROGRESS, blocking all subsequent zone entries.
    @Test
    @DisplayName("TC-096 | freeIfStuck clears IN_PROGRESS zone → re-enter works")
    void tc096_freeWarrior_clearsZoneActivity_allowsReEntry() throws Exception {
        // 1. Enter a zone (creates IN_PROGRESS ZoneActivity)
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk());

        // 2. Free the warrior (simulates freeIfStuck — should also cancel zone activity)
        mockMvc.perform(post("/api/warrior/free")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // 3. Re-enter the same zone — must succeed (not "already on expedition")
        // This is the exact scenario that was broken: orphaned IN_PROGRESS zone
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk());
    }

    // TC-097: Auto-cancel orphaned zone (IN_PROGRESS + warrior free) on re-enter
    @Test
    @DisplayName("TC-097 | Zone enter auto-cancels orphaned expedition when warrior is free")
    void tc097_zoneEnter_autoCancels_orphanedExpedition() throws Exception {
        // 1. Enter zone
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk());

        // 2. Free warrior (but zone activity stays IN_PROGRESS — orphaned state)
        mockMvc.perform(post("/api/warrior/free").header("Authorization", bearer(token)));

        // 3. Directly try to enter zone — ZoneService should auto-cancel the orphan
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zone").value("SAFE"));
    }
}
