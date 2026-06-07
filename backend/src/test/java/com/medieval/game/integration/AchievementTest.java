package com.medieval.game.integration;

import com.medieval.game.enums.Achievement;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerAchievementRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.AchievementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// [TITULOS] Achievements + títulos: desbloqueio por marco, seleção do título, soft-wipe.
@DisplayName("Titles | Achievements + título ativo")
class AchievementTest extends BaseIntegrationTest {

    @Autowired AchievementService          achievementService;
    @Autowired PlayerAchievementRepository achievementRepository;
    @Autowired PlayerRepository            playerRepository;
    @Autowired WarriorRepository           warriorRepository;
    @Autowired com.medieval.game.service.MaintenanceService maintenanceService;

    private Player newPlayer() {
        String u = uniqueUser("ach");
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        return playerRepository.save(p);
    }

    private Warrior makeWarrior(Player p, WarriorClass clazz, int level) {
        Warrior w = new Warrior();
        w.setName("W_" + p.getUsername());
        w.setWarriorClass(clazz);
        w.setPlayer(p);
        w.setLevel(level);
        w.setCurrentHpSnapshot(100);
        w.setHpUpdatedAt(LocalDateTime.now());
        return warriorRepository.save(w);
    }

    private Player reload(Player p) { return playerRepository.findById(p.getId()).orElseThrow(); }

    // ── Desbloqueio por marco + idempotência ──
    @Test
    @DisplayName("checkAndUnlock libera marcos batidos (nível/arena/classe) e não duplica")
    void unlock_byMetric_idempotent() {
        Player p = newPlayer();
        makeWarrior(p, WarriorClass.WARRIOR, 25);
        p.setArenaWins(50);
        playerRepository.save(p);

        achievementService.checkAndUnlock(reload(p));
        achievementService.checkAndUnlock(reload(p)); // 2ª vez não pode duplicar

        var unlocked = achievementRepository.findByPlayer(reload(p)).stream()
                .map(pa -> pa.getAchievement()).toList();
        assertThat(unlocked).contains(
                Achievement.LEVEL_10, Achievement.LEVEL_25,   // nível 25 ≥ 10 e ≥ 25
                Achievement.ARENA_10, Achievement.ARENA_50,   // 50 wins
                Achievement.PATH_WARRIOR);                    // classe Warrior
        assertThat(unlocked).doesNotContain(Achievement.LEVEL_50, Achievement.RANK_1500);
        assertThat(unlocked).doesNotHaveDuplicates();
    }

    // ── Seleção do título: valida desbloqueio ──
    @Test
    @DisplayName("selectTitle aceita título desbloqueado e rejeita travado; titleString reflete")
    void selectTitle_validatesUnlock() {
        Player p = newPlayer();
        makeWarrior(p, WarriorClass.RECRUIT, 12);
        achievementService.checkAndUnlock(reload(p)); // libera LEVEL_10

        // título travado (LEVEL_50) → rejeitado
        assertThatThrownBy(() -> achievementService.selectTitle(reload(p), "LEVEL_50"))
                .isInstanceOf(IllegalStateException.class);

        // título desbloqueado (LEVEL_10 = "Adventurer") → ativa
        String title = achievementService.selectTitle(reload(p), "LEVEL_10");
        assertThat(title).isEqualTo("Adventurer");
        Player after = reload(p);
        assertThat(after.getActiveTitle()).isEqualTo("LEVEL_10");
        assertThat(AchievementService.titleString(after)).isEqualTo("Adventurer");

        // limpar
        achievementService.selectTitle(reload(p), null);
        assertThat(AchievementService.titleString(reload(p))).isEmpty();
    }

    @Test
    @DisplayName("titleString é vazio quando não há título e ignora id inválido")
    void titleString_emptyWhenNone() {
        Player p = newPlayer();
        assertThat(AchievementService.titleString(p)).isEmpty();
        p.setActiveTitle("NOT_A_REAL_ACHIEVEMENT");
        assertThat(AchievementService.titleString(p)).isEmpty();
    }

    // Cenário do dono: desbloqueio via gameplay (notify) → a página mostra desbloqueado + dá pra selecionar.
    @Test
    @DisplayName("Após desbloqueio via gameplay, GET mostra unlocked=true e o título é selecionável")
    void afterGameplayUnlock_listShowsUnlockedAndSelectable() throws Exception {
        String token = registerAndGetToken(uniqueUser("ach"));
        Player p = playerRepository.findAll().stream()
                .filter(x -> x.getUsername().startsWith("ach"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
        warriorRepository.findByPlayer(p).ifPresent(w -> {
            w.setWarriorClass(WarriorClass.ARCHER); w.setLevel(12); warriorRepository.save(w);
        });
        // gatilho de gameplay (manda mail) — igual ao que a Trial faz
        achievementService.checkAndUnlock(reload(p), true);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/achievements").header("Authorization", bearer(token)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.achievements[?(@.id=='PATH_ARCHER')].unlocked").value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.achievements[?(@.id=='PATH_ARCHER')].title").value(org.hamcrest.Matchers.hasItem("Hunter")));

        // o título "Hunter" é selecionável e passa a aparecer no /api/warrior
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/achievements/title").header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"PATH_ARCHER\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.title").value("Hunter")); // [TITULOS] título no header do jogador
    }

    @Test
    @DisplayName("soft-wipe apaga as conquistas e zera o título ativo")
    void softWipe_clearsAchievementsAndTitle() {
        Player p = newPlayer();
        makeWarrior(p, WarriorClass.WARRIOR, 25);
        achievementService.checkAndUnlock(reload(p));
        achievementService.selectTitle(reload(p), "LEVEL_10");
        assertThat(achievementRepository.findByPlayer(reload(p))).isNotEmpty();

        maintenanceService.softWipe();

        assertThat(achievementRepository.count()).isZero();
        assertThat(reload(p).getActiveTitle()).isNull();
    }

    // ── Endpoints ──
    @Test
    @DisplayName("GET /api/achievements lista + desbloqueia lazy; POST /title seta o título")
    void endpoints_listAndSelect() throws Exception {
        String token = registerAndGetToken(uniqueUser("ach"));
        Player p = playerRepository.findAll().stream()
                .filter(x -> x.getUsername().startsWith("ach"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
        warriorRepository.findByPlayer(p).ifPresent(w -> { w.setLevel(10); warriorRepository.save(w); });

        // lazy unlock no GET → LEVEL_10 aparece desbloqueado
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/achievements").header("Authorization", bearer(token)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.achievements[?(@.id=='LEVEL_10')].unlocked").value(org.hamcrest.Matchers.hasItem(true)));

        // seleciona "Adventurer"
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/achievements/title").header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"LEVEL_10\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.activeTitle").value("Adventurer"));

        assertThat(reload(p).getActiveTitle()).isEqualTo("LEVEL_10");
    }

    // ── Títulos ocultos (anti-spoiler) — a escolha no topo da Torre [LORE] ──
    @Test
    @DisplayName("Ocultos: somem da lista até grant(); métrica não os libera; depois aparecem e são selecionáveis")
    void hiddenTitles_absentUntilGranted() {
        Player p = newPlayer();
        makeWarrior(p, WarriorClass.WARRIOR, 50); // bate vários marcos, mas NÃO os ocultos (MANUAL)

        achievementService.checkAndUnlock(reload(p));
        var before = achievementService.list(reload(p)).achievements();
        assertThat(before).noneMatch(v -> v.id().equals("REGICIDE") || v.id().equals("THE_MERCIFUL"));

        // grant dirigido por evento (matar o Rei Arka)
        assertThat(achievementService.grant(reload(p), Achievement.REGICIDE)).isTrue();
        assertThat(achievementService.grant(reload(p), Achievement.REGICIDE)).isFalse(); // idempotente

        var after = achievementService.list(reload(p)).achievements();
        assertThat(after).anyMatch(v -> v.id().equals("REGICIDE") && v.unlocked()); // agora visível
        assertThat(after).noneMatch(v -> v.id().equals("THE_MERCIFUL"));            // o par segue oculto

        assertThat(achievementService.selectTitle(reload(p), "REGICIDE")).isEqualTo("Regicide");
        assertThat(AchievementService.titleString(reload(p))).isEqualTo("Regicide");
    }
}
