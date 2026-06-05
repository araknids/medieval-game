package com.medieval.game.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Quests interativas: diálogo + escolha + roll d20 + limite VIP. Ver docs/PLANO_QUESTS_INTERATIVAS.md.
// (ids de opção acoplados ao conteúdo de PATROL_COAST: hail=Peaceful, ambush=Fight, tally=Check DEX 10)
@DisplayName("Quests Interativas | diálogo, escolha, roll d20, limite VIP")
class QuestInteractiveTest extends BaseIntegrationTest {

    @Autowired PlayerRepository playerRepository;

    String token;

    @BeforeEach
    void setup() throws Exception { token = registerAndGetToken(uniqueUser("iquest")); }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("iquest"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    /** Inicia PATROL_COAST (FISHING) e devolve o JSON do /start (com o diálogo). */
    private JsonNode startPatrol() throws Exception {
        String resp = mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    private JsonNode collect(long questId, String optionId) throws Exception {
        String resp = mockMvc.perform(post("/api/world/FISHING/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + optionId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    private void makeVip() {
        Player p = player();
        p.setVipExpiresAt(LocalDateTime.now().plusDays(1));
        playerRepository.save(p);
    }

    // ── Start devolve o diálogo (história + opções, sem vazar outcomes) ──
    @Test
    @DisplayName("start de quest interativa → interactive:true + dialog com opções")
    void start_returnsDialog() throws Exception {
        JsonNode r = startPatrol();
        assertThat(r.get("interactive").asBoolean()).isTrue();
        assertThat(r.get("dialog").get("intro").asText()).isNotBlank();
        JsonNode opts = r.get("dialog").get("options");
        assertThat(opts).hasSizeGreaterThan(1);
        assertThat(opts.get(0).has("id")).isTrue();
        assertThat(opts.get(0).has("label")).isTrue();
        assertThat(opts.get(0).has("outcome")).isFalse(); // não vaza o desfecho
    }

    // ── Collect sem optionId numa interativa → 400 ──
    @Test
    @DisplayName("collect de interativa sem optionId → 400")
    void collect_withoutOption_returns400() throws Exception {
        long questId = startPatrol().get("id").asLong();
        mockMvc.perform(post("/api/world/FISHING/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Opção pacífica (hail) → recompensa, sem combate, sem roll ──
    @Test
    @DisplayName("opção pacífica → bronze/xp > 0, sem combate")
    void peacefulOption_givesReward_noCombat() throws Exception {
        long questId = startPatrol().get("id").asLong();
        JsonNode r = collect(questId, "hail");
        assertThat(r.get("bronzeEarned").asLong()).isPositive();
        assertThat(r.get("xpEarned").asLong()).isPositive();
        assertThat(r.get("monsterEncountered").asBoolean()).isFalse();
        assertThat(r.has("roll")).isFalse();
    }

    // ── Opção de teste de atributo → resposta traz o roll d20 (attr/dc/rolled) ──
    @Test
    @DisplayName("opção de check → roll d20 presente (attr=DEX, dc=10)")
    void checkOption_returnsRoll() throws Exception {
        long questId = startPatrol().get("id").asLong();
        JsonNode r = collect(questId, "tally");
        assertThat(r.has("roll")).isTrue();
        JsonNode roll = r.get("roll");
        assertThat(roll.get("attr").asText()).isEqualTo("DEX");
        assertThat(roll.get("dc").asInt()).isEqualTo(10);
        assertThat(roll.get("rolled").asInt()).isBetween(1, 20);
        assertThat(roll.has("passed")).isTrue();
    }

    // ── VIP pode fazer a daily 2×; a 3ª é bloqueada. Não-VIP só 1×. ──
    @Test
    @DisplayName("VIP faz a daily 2×, 3ª → 400 (não-VIP só 1×)")
    void vip_canDoDailyTwice_thirdBlocked() throws Exception {
        makeVip();
        // 1ª e 2ª completas (pacífica → sem RNG)
        collect(startPatrol().get("id").asLong(), "hail");
        collect(startPatrol().get("id").asLong(), "hail");
        // 3ª start → 400 (limite VIP = 2 por janela)
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL_COAST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
