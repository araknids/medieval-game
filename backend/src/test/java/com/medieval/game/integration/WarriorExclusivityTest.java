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
 * [SEM_TIMER] O antigo conceito de "busy" cruzado (onMission) foi removido — tudo é instantâneo.
 * Não há mais bloqueio entre sistemas (dá pra ter uma quest ativa E trabalhar). O que sobra é o
 * guard PRÓPRIO de cada atividade (ex.: uma quest em progresso por vez), testado aqui.
 */
@DisplayName("Warrior Activity — guards próprios + sequência (sem 'busy' cruzado)")
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

        var start = objectMapper.readTree(r1);
        long q1Id       = start.get("id").asLong();
        String optionId = start.get("dialog").get("options").get(0).get("id").asText(); // [QUESTS_INTERATIVAS]

        mockMvc.perform(post("/api/world/FISHING/quests/" + q1Id + "/collect")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().isOk());

        // After collecting, must be able to start a new quest immediately
        mockMvc.perform(post("/api/world/MINING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"ESCORT_MINERS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // ── [DIARIO_QUEST] 1 DIÁRIA em progresso por vez — aceitar uma trava as outras até resolver.
    // (Toda quest de reino é daily hoje; quest normal/história, quando existir, não conta neste limite.) ──

    @Test
    @DisplayName("[DIARIO_QUEST] 1 diária em progresso por vez")
    void onlyOneDailyQuestInProgressAtATime() throws Exception {
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk());

        // Segunda DIÁRIA (mesmo de outro reino) é rejeitada enquanto a 1ª está em progresso
        mockMvc.perform(post("/api/world/MINING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"ESCORT_MINERS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── [SEM_TIMER] Atividades diferentes são independentes (sem 'busy' cruzado) ──

    @Test
    @DisplayName("Pode trabalhar mesmo com uma quest ativa (sem bloqueio cruzado)")
    void canStartWork_withActiveQuest_noCrossBlock() throws Exception {
        // Inicia uma quest e NÃO coleta (fica IN_PROGRESS)
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk());

        // Trabalhar continua liberado — não há mais bloqueio entre sistemas
        mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workType\":\"TAVERN_HELPER\",\"hours\":1}"))
                .andExpect(status().isOk());
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

        fastForwardWork(sessionId); // [WORK_IDLE] timer real: simula o tempo decorrido p/ poder coletar

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
