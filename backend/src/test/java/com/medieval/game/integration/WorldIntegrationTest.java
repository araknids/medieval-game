package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-142 to TC-157 — World / 3 Kingdoms integration tests
@DisplayName("TC-142-157 | World — 3 Kingdoms Integration")
class WorldIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("world"));
    }

    // TC-142: GET /api/world returns 3 kingdoms
    @Test
    @DisplayName("TC-142 | GET /api/world → 5 kingdoms (3 war + Grutas/Garimpo + Mar Abençoado)")
    void tc142_listKingdoms_returns5() throws Exception {
        mockMvc.perform(get("/api/world").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].kingdom").isNotEmpty())
                .andExpect(jsonPath("$[0].displayName").isNotEmpty())
                .andExpect(jsonPath("$[0].icon").isNotEmpty());
    }

    // TC-143: GET /api/world/{kingdom}/quests returns 4 quests
    @Test
    @DisplayName("TC-143 | GET /api/world/FISHING/quests → 4 quest types")
    void tc143_getFishingQuests_returns4() throws Exception {
        mockMvc.perform(get("/api/world/FISHING/quests").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].displayName").isNotEmpty())
                .andExpect(jsonPath("$[0].durationMinutes").isNumber());
    }

    // TC-144: GET /api/world/{kingdom}/quests/active → empty for new player
    @Test
    @DisplayName("TC-144 | GET /api/world/FISHING/quests/active → empty initially")
    void tc144_getActiveQuests_emptyInitially() throws Exception {
        mockMvc.perform(get("/api/world/FISHING/quests/active").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // TC-145: POST /api/world/{kingdom}/quests/start → quest started
    @Test
    @DisplayName("TC-145 | POST /api/world/FISHING/quests/start → quest started")
    void tc145_startKingdomQuest_success() throws Exception {
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.kingdom").value("FISHING"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // TC-146: Start quest twice → 400 warrior busy
    @Test
    @DisplayName("TC-146 | Start two quests → 400 warrior busy")
    void tc146_startTwoQuests_returns400() throws Exception {
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questType\":\"PATROL_COAST\"}"));

        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-147: Collect kingdom quest (instant-complete)
    @Test
    @DisplayName("TC-147 | Collect quest (instant-complete) → bronzeEarned present")
    void tc147_collectKingdomQuest_success() throws Exception {
        String startResp = mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long questId = objectMapper.readTree(startResp).get("id").asLong();

        mockMvc.perform(post("/api/world/FISHING/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bronzeEarned").isNumber())
                .andExpect(jsonPath("$.xpEarned").isNumber());
    }

    // TC-148: Abandon kingdom quest
    @Test
    @DisplayName("TC-148 | Abandon quest → warrior freed")
    void tc148_abandonKingdomQuest_warriorFreed() throws Exception {
        String startResp = mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andReturn().getResponse().getContentAsString();

        long questId = objectMapper.readTree(startResp).get("id").asLong();

        mockMvc.perform(post("/api/world/FISHING/quests/" + questId + "/abandon")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // Can start new quest after abandon
        mockMvc.perform(post("/api/world/MINING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"ESCORT_MINERS\"}"))
                .andExpect(status().isOk());
    }

    // TC-149: GET /api/world/COMBAT/training → active:false initially
    @Test
    @DisplayName("TC-149 | GET /api/world/COMBAT/training → active false initially")
    void tc149_getCombatTraining_emptyInitially() throws Exception {
        mockMvc.perform(get("/api/world/COMBAT/training").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // TC-150: POST /api/world/COMBAT/training/start → training started
    @Test
    @DisplayName("TC-150 | POST /api/world/COMBAT/training/start → training session created")
    void tc150_startTraining_success() throws Exception {
        mockMvc.perform(post("/api/world/COMBAT/training/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.xpReward").isNumber())
                .andExpect(jsonPath("$.bronzeCost").isNumber());
    }

    // TC-151: Collect training (instant-complete)
    @Test
    @DisplayName("TC-151 | Collect training → xpEarned present")
    void tc151_collectTraining_success() throws Exception {
        String startResp = mockMvc.perform(post("/api/world/COMBAT/training/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":1}"))
                .andReturn().getResponse().getContentAsString();

        long sessionId = objectMapper.readTree(startResp).get("id").asLong();

        mockMvc.perform(post("/api/world/COMBAT/training/" + sessionId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.xpEarned").isNumber());
    }

    // TC-152: Wrong quest type for kingdom → 400
    @Test
    @DisplayName("TC-152 | Start MINING quest in FISHING kingdom → 400")
    void tc152_wrongQuestForKingdom_returns400() throws Exception {
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"ESCORT_MINERS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-153: GET /api/world — each kingdom has lore, icon, secsUntilBattle, isMine, xpBonus
    @Test
    @DisplayName("TC-153 | GET /api/world — response has all required overlay fields")
    void tc153_worldOverview_hasAllRequiredFields() throws Exception {
        mockMvc.perform(get("/api/world").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lore").isNotEmpty())
                .andExpect(jsonPath("$[0].icon").isNotEmpty())
                .andExpect(jsonPath("$[0].secsUntilBattle").isNumber())
                .andExpect(jsonPath("$[0].isMine").isBoolean())
                .andExpect(jsonPath("$[0].xpBonus").isNumber())
                .andExpect(jsonPath("$[0].bronzeBonus").isNumber())
                .andExpect(jsonPath("$[0].exclusiveBonus").isNumber());
    }

    // TC-154: POST training/start with hours=0 → 400
    @Test
    @DisplayName("TC-154 | POST training/start with hours=0 → 400")
    void tc154_startTraining_zeroHours_returns400() throws Exception {
        mockMvc.perform(post("/api/world/COMBAT/training/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-155: Cancel training → warrior freed → can start kingdom quest
    @Test
    @DisplayName("TC-155 | Cancel training → warrior freed → can start quest")
    void tc155_cancelTraining_warriorFreed_canStartQuest() throws Exception {
        String startResp = mockMvc.perform(post("/api/world/COMBAT/training/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":2}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long sessionId = objectMapper.readTree(startResp).get("id").asLong();

        mockMvc.perform(post("/api/world/COMBAT/training/" + sessionId + "/cancel")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // TC-156: Collect training → warrior freed → can start kingdom quest
    @Test
    @DisplayName("TC-156 | Collect training → warrior freed → can start quest")
    void tc156_collectTraining_warriorFreed_canStartQuest() throws Exception {
        String startResp = mockMvc.perform(post("/api/world/COMBAT/training/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long sessionId = objectMapper.readTree(startResp).get("id").asLong();

        mockMvc.perform(post("/api/world/COMBAT/training/" + sessionId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/world/MINING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"ESCORT_MINERS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // TC-157: GET /api/world — COMBAT kingdom returns 4 quest types
    @Test
    @DisplayName("TC-157 | GET /api/world/COMBAT/quests → 4 quest types")
    void tc157_getCombatQuests_returns4() throws Exception {
        mockMvc.perform(get("/api/world/COMBAT/quests").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(4)));
    }
}
