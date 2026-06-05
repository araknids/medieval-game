package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-117 to TC-120 — Guild passive bonuses
@DisplayName("TC-117-120 | Guild Bonuses — Passive XP/Drop/Bronze")
class GuildBonusIntegrationTest extends BaseIntegrationTest {

    String leaderToken;

    @BeforeEach
    void setup() throws Exception {
        leaderToken = registerAndGetToken(uniqueUser("gbonus"));
        // Create guild
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "BonusGuild" + System.nanoTime(), "description", ""))));
    }

    // TC-117: GET /api/guild returns xpBonus, dropBonus, bronzeBonus fields
    @Test
    @DisplayName("TC-117 | GET /api/guild → xpBonus, dropBonus, bronzeBonus present")
    void tc117_guildDetail_hasBonusFields() throws Exception {
        mockMvc.perform(get("/api/guild")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGuild").value(true))
                .andExpect(jsonPath("$.xpBonus").isNumber())
                .andExpect(jsonPath("$.dropBonus").isNumber())
                .andExpect(jsonPath("$.bronzeBonus").isNumber());
    }

    // TC-118: Level 1 guild has 0 bonuses
    @Test
    @DisplayName("TC-118 | Guild level 1 → all bonuses = 0")
    void tc118_level1Guild_hasZeroBonuses() throws Exception {
        mockMvc.perform(get("/api/guild")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value(1))
                .andExpect(jsonPath("$.xpBonus").value(0))
                .andExpect(jsonPath("$.dropBonus").value(0))
                .andExpect(jsonPath("$.bronzeBonus").value(0));
    }

    // (TC-119 removido: testava o /api/quests legado; TC-120 já cobre bônus-de-guilda no reward via Work.)

    // TC-120: Work collect with level 1 guild — rewards unchanged
    @Test
    @DisplayName("TC-120 | Work collect with level 1 guild → no bonus applied")
    void tc120_workCollect_level1_noBonus() throws Exception {
        String workResp = mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("workType", "TAVERN_HELPER", "hours", 1))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long sessionId = objectMapper.readTree(workResp).get("id").asLong();

        mockMvc.perform(post("/api/work/" + sessionId + "/collect")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goldEarned").isNumber());
    }
}
