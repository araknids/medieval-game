package com.medieval.game.integration;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.Zone;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.ZoneActivity;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.repository.ZoneActivityRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-212 to TC-218 — Zone Ambush PvP integration tests
@DisplayName("TC-212-218 | Zone Ambush PvP — Integration")
class ZoneAmbushIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository         playerRepository;
    @Autowired WarriorRepository        warriorRepository;
    @Autowired ZoneActivityRepository   activityRepository;
    @Autowired GatheringService         gatheringService;
    @Autowired MailService              mailService;
    @Autowired com.medieval.game.service.ZoneService zoneService;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("amb"));
    }

    private Player playerOf(String prefix) {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith(prefix))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .orElseThrow();
    }

    private Warrior warriorOf(Player p) {
        return warriorRepository.findByPlayer(p).orElseThrow();
    }

    // ── TC-212: Peixe de VIDA (Fênix) cura HP até o teto de 90% [REINOS_V2] ──
    @Test
    @DisplayName("TC-212 | Consume PHOENIX_FISH a 20% → HP capado em 90%")
    void tc212_phoenixFish_cappedAt90() throws Exception {
        Player player = playerOf("amb");
        Warrior w = warriorOf(player);
        w.setCurrentHpSnapshot(20);
        w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);
        gatheringService.addResource(player, ResourceType.PHOENIX_FISH, 1);

        mockMvc.perform(post("/api/gathering/consume/PHOENIX_FISH")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newHpPercent").value(90)); // 20 + 90, capado em 90
    }

    // ── TC-213: Peixe-Coral (vida) cura +15% HP quando abaixo do teto ──
    @Test
    @DisplayName("TC-213 | Consume CORAL_FISH a 30% → +15% HP")
    void tc213_coralFish_heals15pct() throws Exception {
        Player player = playerOf("amb");
        Warrior w = warriorOf(player);
        w.setCurrentHpSnapshot(30);
        w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);
        gatheringService.addResource(player, ResourceType.CORAL_FISH, 1);

        mockMvc.perform(post("/api/gathering/consume/CORAL_FISH")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newHpPercent").value(greaterThanOrEqualTo(45)));
    }

    // ── TC-214: GET /api/zones/current expõe campos de emboscada ──
    @Test
    @DisplayName("TC-214 | GET /api/zones/current exposes ambush fields")
    void tc214_currentExposesAmbushFields() throws Exception {
        Player player = playerOf("amb");
        warriorOf(player); // ensure warrior exists
        mockMvc.perform(post("/api/zones/enter")
                .header("Authorization", bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/zones/current").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ambushPending").value(false))
                .andExpect(jsonPath("$.ambushCount").value(0));
    }

    // ── TC-215: Continue endpoint limpa ambushPending ──
    @Test
    @DisplayName("TC-215 | POST /api/zones/{id}/continue clears ambushPending")
    void tc215_continueClearsPending() throws Exception {
        Player player = playerOf("amb");
        warriorOf(player);
        String resp = mockMvc.perform(post("/api/zones/enter")
                .header("Authorization", bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andReturn().getResponse().getContentAsString();
        long activityId = objectMapper.readTree(resp).get("id").asLong();

        // Simulate a pending ambush
        ZoneActivity act = activityRepository.findById(activityId).orElseThrow();
        act.setAmbushPending(true);
        act.setAmbushCount(1);
        activityRepository.save(act);

        mockMvc.perform(post("/api/zones/" + activityId + "/continue")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        ZoneActivity after = activityRepository.findById(activityId).orElseThrow();
        assertThat(after.isAmbushPending()).isFalse();
        assertThat(after.getAmbushCount()).isEqualTo(1); // count preserved
    }

    // ── TC-216: sendSystemMail aparece no inbox ──
    @Test
    @DisplayName("TC-216 | System ambush mail appears in target inbox")
    void tc216_systemMailInInbox() throws Exception {
        Player player = playerOf("amb");
        mailService.sendSystemMail(player, "⚔ You were ambushed by Someone and SURVIVED!");

        mockMvc.perform(get("/api/mail/inbox").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letters[0].from").value("System"))
                .andExpect(jsonPath("$.letters[0].message").value(containsString("ambushed")));
    }

    // ── TC-217: Pool de FLAGGED — 2 players expostos na mesma zona PvP [PVP_FLAG] ──
    @Test
    @DisplayName("TC-217 | Two flagged players → each is in the other's flag pool")
    void tc217_flaggedPool() throws Exception {
        Player a = playerOf("amb");
        warriorOf(a);
        flagPlayer(a, Zone.PVP);

        registerAndGetToken(uniqueUser("amb"));
        Player b = playerOf("amb");
        flagPlayer(b, Zone.PVP);

        var poolForA = playerRepository.findFlaggedInZone(Zone.PVP, java.time.LocalDateTime.now(), a.getId());
        var poolForB = playerRepository.findFlaggedInZone(Zone.PVP, java.time.LocalDateTime.now(), b.getId());

        assertThat(poolForA).anyMatch(p -> p.getId().equals(b.getId()));
        assertThat(poolForB).anyMatch(p -> p.getId().equals(a.getId()));
        assertThat(poolForA).noneMatch(p -> p.getId().equals(a.getId())); // self-exclusion
    }

    // ── TC-218: Flag de outra zona / expirado não aparece no pool [PVP_FLAG] ──
    @Test
    @DisplayName("TC-218 | Other-zone or expired flag → not in pool")
    void tc218_expiredOrOtherZoneExcluded() {
        Player a = playerOf("amb");
        warriorOf(a);

        // flag em HIGH_RISK → não aparece ao consultar PVP
        flagPlayer(a, Zone.HIGH_RISK);
        assertThat(playerRepository.findFlaggedInZone(Zone.PVP, java.time.LocalDateTime.now(), -1L))
                .noneMatch(p -> p.getId().equals(a.getId()));

        // flag expirado em PVP → não aparece (re-busca fresco p/ evitar version stale)
        Player a2 = playerRepository.findById(a.getId()).orElseThrow();
        a2.setPvpFlaggedZone(Zone.PVP);
        a2.setPvpFlaggedUntil(java.time.LocalDateTime.now().minusMinutes(1));
        playerRepository.save(a2);
        assertThat(playerRepository.findFlaggedInZone(Zone.PVP, java.time.LocalDateTime.now(), -1L))
                .noneMatch(p -> p.getId().equals(a.getId()));
    }

    // ── TC-219: Farmar zona PvP e sobreviver → fica flagged por 1h [PVP_FLAG] ──
    @Test
    @DisplayName("TC-219 | Farming a PvP zone flags the player")
    void tc219_farmingFlagsPlayer() throws Exception {
        Player player = playerOf("amb");
        Warrior w = warriorOf(player);
        // guerreiro forte: flag só é setado se sobreviver ao encontro (NPC/raid)
        w.setLevel(15); w.setAttack(500); w.setDefense(500); w.setHealth(500);
        w.setStrength(100); w.setConstitution(100);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);

        String resp = mockMvc.perform(post("/api/zones/enter")
                .header("Authorization", bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"zone\":\"PVP\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":60}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long activityId = objectMapper.readTree(resp).get("id").asLong();

        mockMvc.perform(post("/api/zones/" + activityId + "/collect")
                .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        Player after = playerRepository.findById(player.getId()).orElseThrow();
        assertThat(after.isPvpFlagged()).isTrue();
        assertThat(after.getPvpFlaggedZone()).isEqualTo(Zone.PVP);
    }

    // ── TC-220: Raid de player flagged → vítima perde bronze, ganha escudo e flag cai [PVP_FLAG] ──
    @Test
    @DisplayName("TC-220 | Raiding a flagged victim steals bronze + shields victim")
    void tc220_raidLootsFlaggedVictim() throws Exception {
        // Vítima: exposta em HIGH_RISK com bronze na bolsa
        Player victim = playerOf("amb");
        warriorOf(victim);
        victim.addBronzeAmount(10_000);
        flagPlayer(victim, Zone.HIGH_RISK); // também salva
        long bronzeBefore = playerRepository.findById(victim.getId()).orElseThrow().totalBronze();

        // Atacante: separado e imbatível (flag só lhe é setado se sobreviver; raid só se vencer)
        registerAndGetToken(uniqueUser("amb"));
        Player attacker = playerOf("amb");
        Warrior aw = warriorOf(attacker);
        aw.setLevel(25); aw.setAttack(800); aw.setDefense(800); aw.setHealth(800);
        aw.setStrength(200); aw.setConstitution(200);
        warriorRepository.save(aw);

        boolean raided = false;
        for (int i = 0; i < 120 && !raided; i++) {
            try {
                Player atk = playerRepository.findById(attacker.getId()).orElseThrow();
                Warrior w = warriorRepository.findByPlayer(atk).orElseThrow();
                w.setOnMission(false);
                w.setCurrentHpSnapshot(100);
                w.setHpUpdatedAt(java.time.LocalDateTime.now());
                warriorRepository.save(w);

                var act = zoneService.enter(atk, Zone.HIGH_RISK,
                        com.medieval.game.enums.ActivityRole.GATHERING,
                        com.medieval.game.enums.SkillType.FISHING, 60);
                zoneService.collect(playerRepository.findById(attacker.getId()).orElseThrow(), act.getId());
            } catch (Exception ignore) {
                // conflito transitório de versão → tenta de novo
            }
            raided = playerRepository.findById(victim.getId()).orElseThrow().isPvpShielded();
        }

        assertThat(raided).as("vítima deveria ter sido saqueada em até 120 farms").isTrue();
        Player v = playerRepository.findById(victim.getId()).orElseThrow();
        assertThat(v.isPvpShielded()).isTrue();              // escudo pós-derrota
        assertThat(v.isPvpFlagged()).isFalse();              // flag caiu (saqueado 1x por ciclo)
        assertThat(v.totalBronze()).isLessThan(bronzeBefore); // bronze roubado no raid
    }

    // Helper: expõe um player numa zona (flagged por 1h)
    private void flagPlayer(Player p, Zone zone) {
        p.setPvpFlaggedZone(zone);
        p.setPvpFlaggedUntil(java.time.LocalDateTime.now().plusHours(1));
        playerRepository.save(p);
    }
}
