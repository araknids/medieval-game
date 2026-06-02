package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests that a warrior cannot perform 2 simultaneous tasks,
 * and that collecting one task frees the warrior for the next.
 *
 * Covers the class of bugs where state between systems becomes
 * inconsistent (e.g., warrior.onMission stays true after collection).
 */
@DisplayName("Warrior Exclusivity & Sequence Tests")
class WarriorExclusivityTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("excl"));
    }

    // ── Quest sequence ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("After collecting kingdom quest, warrior is free for next quest")
    void afterCollectKingdomQuest_warriorFree_canStartAnother() throws Exception {
        // Start and collect first quest
        String r1 = mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long q1Id = objectMapper.readTree(r1).get("id").asLong();

        mockMvc.perform(post("/api/world/FISHING/quests/" + q1Id + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // After collecting, must be able to start a new quest immediately
        mockMvc.perform(post("/api/world/MINING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"ESCORT_MINERS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // ── Kingdom quest + Kingdom quest (same system) ───────────────────────────

    @Test
    @DisplayName("Cannot start 2 kingdom quests simultaneously")
    void cannotStart2KingdomQuestSimultaneously() throws Exception {
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk());

        // Second quest in different kingdom must also be rejected
        mockMvc.perform(post("/api/world/MINING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"ESCORT_MINERS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Kingdom quest + Work (cross-system) ───────────────────────────────────

    @Test
    @DisplayName("Cannot start Work while on Kingdom quest")
    void cannotStartWork_whileOnKingdomQuest() throws Exception {
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questType\":\"PATROL_COAST\"}"));

        mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workType\":\"TAVERN_HELPER\",\"hours\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Work + Kingdom quest (cross-system) ───────────────────────────────────

    @Test
    @DisplayName("Cannot start Kingdom quest while Working")
    void cannotStartKingdomQuest_whileWorking() throws Exception {
        mockMvc.perform(post("/api/work/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workType\":\"TAVERN_HELPER\",\"hours\":1}"));

        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Training + Kingdom quest (cross-system) ───────────────────────────────

    @Test
    @DisplayName("Cannot start Kingdom quest while Training at Fortaleza")
    void cannotStartKingdomQuest_whileTraining() throws Exception {
        mockMvc.perform(post("/api/world/COMBAT/training/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":1}"));

        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Gathering + Kingdom quest (cross-system) ──────────────────────────────

    @Test
    @DisplayName("Cannot start Kingdom quest while gathering")
    void cannotStartKingdomQuest_whileGathering() throws Exception {
        mockMvc.perform(post("/api/gathering/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skillType\":\"FISHING\",\"durationMinutes\":5}"));

        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── After work collect → free for quest ───────────────────────────────────

    @Test
    @DisplayName("After Work collected, warrior is free for Kingdom quest")
    void afterWorkCollected_warriorFree_canStartKingdomQuest() throws Exception {
        String r = mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workType\":\"TAVERN_HELPER\",\"hours\":1}"))
                .andReturn().getResponse().getContentAsString();

        long sessionId = objectMapper.readTree(r).get("id").asLong();

        mockMvc.perform(post("/api/work/" + sessionId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // Now warrior must be free
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // ── Abandon quest → warrior free → can start another ─────────────────────

    @Test
    @DisplayName("After abandoning quest, warrior is free for a different quest")
    void afterAbandonQuest_warriorFree_canStartAnother() throws Exception {
        String r = mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andReturn().getResponse().getContentAsString();

        long qId = objectMapper.readTree(r).get("id").asLong();

        mockMvc.perform(post("/api/world/FISHING/quests/" + qId + "/abandon")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/world/COMBAT/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"DEFEND_WALLS\"}"))
                .andExpect(status().isOk());
    }
}
