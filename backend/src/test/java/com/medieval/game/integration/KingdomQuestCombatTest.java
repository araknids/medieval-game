package com.medieval.game.integration;

import com.medieval.game.enums.QuestStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.KingdomActiveQuestRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Quests V2 — encontro de monstro na coleta: vencer dá recompensa, perder zera. + narrativa sempre presente.
@DisplayName("Quests V2 | Kingdom Quest — combate na coleta + narrativa")
class KingdomQuestCombatTest extends BaseIntegrationTest {

    @Autowired PlayerRepository             playerRepository;
    @Autowired WarriorRepository            warriorRepository;
    @Autowired KingdomActiveQuestRepository questRepository;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("kquest"));
    }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("kquest"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    /** Reset determinístico antes de cada start: guerreiro livre + HP cheio + stamina cheia.
     *  Evita flakiness por estado deixado pela iteração anterior (start exige warrior livre + stamina). */
    private void resetForStart() {
        Player p = player();
        p.setCurrentStamina(100);
        p.setStaminaUpdatedAt(LocalDateTime.now());
        playerRepository.save(p);
        warriorRepository.findByPlayer(p).ifPresent(w -> {
            w.setOnMission(false);
            w.setCurrentHpSnapshot(100);
            w.setHpUpdatedAt(LocalDateTime.now());
            warriorRepository.save(w);
        });
        // [DAILY_QUESTS] limpa conclusões da daily para repetir a MESMA quest no loop do teste
        // (sem isso, o lock 1x/janela bloquearia a 2ª chamada de startAndCollect).
        questRepository.deleteAll(
                questRepository.findByPlayerAndStatusNotOrderByStartedAtDesc(p, QuestStatus.ABANDONED));
    }

    /** Inicia HUNT_SEA_MONSTER (FISHING, 90% de monstro) e coleta na hora; devolve o JSON do collect. */
    private JsonNode startAndCollect() throws Exception {
        resetForStart();
        String startResp = mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"HUNT_SEA_MONSTER\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long questId = objectMapper.readTree(startResp).get("id").asLong();

        String collectResp = mockMvc.perform(post("/api/world/FISHING/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(collectResp);
    }

    // ── Coleta sempre traz narrativa ──
    @Test
    @DisplayName("Toda coleta retorna uma narrativa não-vazia")
    void collect_alwaysHasNarrative() throws Exception {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setLevel(20); w.setAttack(300); w.setHealth(3000);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);

        JsonNode r = startAndCollect();
        assertThat(r.get("narrative").asText()).isNotBlank();
        assertThat(r.has("monsterEncountered")).isTrue();
    }

    // ── Perder o combate → 0 XP / 0 bronze ──
    @Test
    @DisplayName("Guerreiro fraco que perde o combate não recebe XP nem bronze")
    void losingCombat_givesNoReward() throws Exception {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setLevel(1); w.setAttack(1); w.setDefense(0); w.setHealth(15);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);

        boolean sawLoss = false;
        for (int i = 0; i < 25; i++) {
            JsonNode r = startAndCollect();
            assertThat(r.get("narrative").asText()).isNotBlank();
            boolean encountered = r.get("monsterEncountered").asBoolean();
            boolean defeated    = r.get("monsterDefeated").asBoolean();
            if (encountered && !defeated) {
                sawLoss = true;
                assertThat(r.get("xpEarned").asLong()).as("XP ao perder").isZero();
                assertThat(r.get("bronzeEarned").asLong()).as("bronze ao perder").isZero();
            }
        }
        assertThat(sawLoss).as("esperava observar ao menos uma derrota em 25 tentativas (90% de encontro)").isTrue();
    }

    // ── Vencer o combate → XP creditado ──
    @Test
    @DisplayName("Guerreiro forte que vence o combate recebe XP")
    void winningCombat_givesReward() throws Exception {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setLevel(50); w.setAttack(9999); w.setHealth(99999);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);

        boolean sawMonsterWin = false;
        for (int i = 0; i < 25 && !sawMonsterWin; i++) {
            JsonNode r = startAndCollect();
            if (r.get("monsterEncountered").asBoolean() && r.get("monsterDefeated").asBoolean()) {
                sawMonsterWin = true;
                assertThat(r.get("xpEarned").asLong()).as("XP ao vencer").isPositive();
            }
        }
        assertThat(sawMonsterWin).as("esperava observar ao menos uma vitória com monstro em 25 tentativas").isTrue();
    }

    // ── [DAILY_QUESTS] daily trava 1x por janela após CONCLUIR (vitória/sem encontro) ──
    @Test
    @DisplayName("Daily concluída trava na janela: doneToday=true, canStart=false, re-start → 400")
    void dailyQuest_lockedAfterSuccessInSameWindow() throws Exception {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setLevel(50); w.setAttack(9999); w.setHealth(99999);          // garante vitória → conclui → trava
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);

        // 1ª quest da vitrine do reino FISHING (fresh → não feita ainda)
        String showcase = mockMvc.perform(get("/api/world/FISHING/quests")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode q0 = objectMapper.readTree(showcase).get(0);
        String questType = q0.get("id").asText();
        assertThat(q0.get("doneToday").asBoolean()).isFalse();
        assertThat(q0.get("canStart").asBoolean()).isTrue();
        assertThat(q0.has("secondsUntilReset")).isTrue();

        // start + collect (vitória garantida → daily consumida nesta janela)
        String startResp = mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"" + questType + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long questId = objectMapper.readTree(startResp).get("id").asLong();
        mockMvc.perform(post("/api/world/FISHING/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // vitrine agora: a quest aparece feita (doneToday=true, canStart=false)
        String showcase2 = mockMvc.perform(get("/api/world/FISHING/quests")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode locked = null;
        for (JsonNode q : objectMapper.readTree(showcase2)) {
            if (q.get("id").asText().equals(questType)) locked = q;
        }
        assertThat(locked).as("quest %s presente na vitrine", questType).isNotNull();
        assertThat(locked.get("doneToday").asBoolean()).isTrue();
        assertThat(locked.get("canStart").asBoolean()).isFalse();

        // re-start na MESMA janela → 400 (lock diário; guerreiro já livre após o collect)
        mockMvc.perform(post("/api/world/FISHING/quests/start")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"" + questType + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
