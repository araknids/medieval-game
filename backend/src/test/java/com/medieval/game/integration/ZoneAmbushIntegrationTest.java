package com.medieval.game.integration;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.Zone;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
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

// TC-212 to TC-220 — Zone PvP (raid-by-flag) integration tests
@DisplayName("TC-212-220 | Zone PvP (raid-by-flag) — Integration")
class ZoneAmbushIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository         playerRepository;
    @Autowired WarriorRepository        warriorRepository;
    @Autowired GatheringService         gatheringService;
    @Autowired MailService              mailService;
    @Autowired com.medieval.game.service.ZoneService zoneService;
    @Autowired com.medieval.game.service.InventoryService inventoryService;
    @Autowired com.medieval.game.service.StashService stashService;
    @Autowired com.medieval.game.repository.InventoryItemRepository itemRepo;

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

    // ── TC-214: GET /api/zones/current expõe a expedição ativa ──
    @Test
    @DisplayName("TC-214 | GET /api/zones/current exposes the active expedition")
    void tc214_currentExposesActivity() throws Exception {
        Player player = playerOf("amb");
        warriorOf(player); // ensure warrior exists
        mockMvc.perform(post("/api/zones/enter")
                .header("Authorization", bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/zones/current").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.zone").value("SAFE"));
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
        // Vítima: exposta em HIGH_RISK com bronze na bolsa (nível 45 — banda alta isolada de outros testes)
        Player victim = playerOf("amb");
        Warrior vw = warriorOf(victim);
        vw.setLevel(45);
        warriorRepository.save(vw);
        victim.addBronzeAmount(10_000);
        flagPlayer(victim, Zone.HIGH_RISK); // também salva
        long bronzeBefore = playerRepository.findById(victim.getId()).orElseThrow().totalBronze();

        // Atacante: separado e imbatível (flag só lhe é setado se sobreviver; raid só se vencer)
        registerAndGetToken(uniqueUser("amb"));
        Player attacker = playerOf("amb");
        Warrior aw = warriorOf(attacker);
        aw.setLevel(50); aw.setAttack(2000); aw.setDefense(2000); aw.setHealth(5000); // banda 40-60, esmaga tudo
        aw.setStrength(200); aw.setConstitution(200);
        warriorRepository.save(aw);
        long killerXpBefore = warriorRepository.findByPlayer(attacker).map(Warrior::getExperience).orElse(0L);

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
        // [PVP_FLAG] o killer ganha XP do raid (stealXp); coleta só dá XP de skill, não de warrior
        long killerXpAfter = warriorRepository.findByPlayer(playerRepository.findById(attacker.getId()).orElseThrow())
                .map(Warrior::getExperience).orElse(0L);
        assertThat(killerXpAfter).as("killer ganha XP do raid").isGreaterThan(killerXpBefore);
    }

    // ── TC-221: Farmar zona PvP trava os itens expostos + bloqueia venda [PVP_FLAG] ──
    @Test
    @DisplayName("TC-221 | Farming the RED zone locks bag items + blocks selling")
    void tc221_farmingLocksItems() {
        Player player = playerOf("amb");
        Warrior w = warriorOf(player);
        // lvl 30 (banda 20-40, isolada do TC-220 que usa 45/50) + stats esmagadores → sempre vence o encontro
        w.setLevel(30); w.setAttack(2000); w.setDefense(2000); w.setHealth(5000);
        w.setStrength(200); w.setConstitution(200);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);

        // item na bag (não-stashed, não-guarded → exposto)
        com.medieval.game.model.InventoryItem item =
            inventoryService.make(player, "Test Ring", com.medieval.game.enums.ItemType.RING, 0, 0, 0, 1, 10);
        long itemId = item.getId();

        // farma a zona VERMELHA (HIGH_RISK) → trava os itens expostos + flagga (item-lock só na vermelha)
        var act = zoneService.enter(player, Zone.HIGH_RISK,
                com.medieval.game.enums.ActivityRole.GATHERING,
                com.medieval.game.enums.SkillType.FISHING, 60);
        zoneService.collect(playerRepository.findById(player.getId()).orElseThrow(), act.getId());

        assertThat(itemRepo.findById(itemId).orElseThrow().isPvpLocked()).isTrue();

        // não pode vender enquanto flagged
        Player flagged = playerRepository.findById(player.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inventoryService.sell(flagged, itemId))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── TC-222: Zona AMARELA (PVP) flagga mas NÃO trava itens (só recursos) [PVP_FLAG] ──
    @Test
    @DisplayName("TC-222 | Farming the YELLOW zone flags but does NOT lock items")
    void tc222_yellowZoneNoItemLock() {
        Player player = playerOf("amb");
        Warrior w = warriorOf(player);
        // lvl 30 (banda 20-40, isolada do TC-219 lvl 15) + stats esmagadores → sempre sobrevive
        w.setLevel(30); w.setAttack(2000); w.setDefense(2000); w.setHealth(5000);
        w.setStrength(200); w.setConstitution(200);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);

        com.medieval.game.model.InventoryItem item =
            inventoryService.make(player, "Yellow Ring", com.medieval.game.enums.ItemType.RING, 0, 0, 0, 1, 10);
        long itemId = item.getId();

        var act = zoneService.enter(player, Zone.PVP,
                com.medieval.game.enums.ActivityRole.GATHERING,
                com.medieval.game.enums.SkillType.FISHING, 60);
        zoneService.collect(playerRepository.findById(player.getId()).orElseThrow(), act.getId());

        Player after = playerRepository.findById(player.getId()).orElseThrow();
        assertThat(after.isPvpFlagged()).isTrue();                                  // farmou amarela → flagged
        assertThat(itemRepo.findById(itemId).orElseThrow().isPvpLocked()).isFalse(); // amarela NÃO trava item
    }

    // ── TC-223: Coleta por zona com reino → drops + narrativa (unificação) [UNIFICAÇÃO_ZONA] ──
    @Test
    @DisplayName("TC-223 | Zone gathering com kingdom retorna drops + narrativa")
    void tc223_zoneGatheringKingdomDrops() {
        Player player = playerOf("amb");
        Warrior w = warriorOf(player);
        w.setLevel(30); w.setAttack(2000); w.setDefense(2000); w.setHealth(5000);
        w.setStrength(200); w.setConstitution(200);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);

        var act = zoneService.enter(player, Zone.SAFE,
                com.medieval.game.enums.ActivityRole.GATHERING,
                com.medieval.game.enums.SkillType.FISHING, 20,
                com.medieval.game.enums.Kingdom.MAR_ABENCOADO);
        var result = zoneService.collect(playerRepository.findById(player.getId()).orElseThrow(), act.getId());

        assertThat(result.survived()).isTrue();
        assertThat(result.drops()).isNotEmpty();      // coletou (drops do reino)
        assertThat(result.narrative()).isNotBlank();  // narrativa de coleta
    }

    // ── TC-224: Flagged não pode stashar recurso (recurso travado no PvP) [PVP_FLAG] ──
    @Test
    @DisplayName("TC-224 | Flagged não pode guardar recurso no stash")
    void tc224_flaggedCannotStashResource() {
        Player p = playerOf("amb");
        warriorOf(p);
        gatheringService.addResource(p, com.medieval.game.enums.ResourceType.SMALL_FISH, 10);
        flagPlayer(p, Zone.PVP); // exposto → recursos travados

        Player flagged = playerRepository.findById(p.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                stashService.depositResource(flagged, com.medieval.game.enums.ResourceType.SMALL_FISH, 5))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── TC-225: Vítima fora da banda de nível (±10) NÃO é saqueada [PVP_FLAG] ──
    @Test
    @DisplayName("TC-225 | Vítima fora da banda de ±10 níveis não é raidada")
    void tc225_outOfBandVictimNotRaided() throws Exception {
        // vítima nível 5 (bem abaixo da banda do atacante lvl 50)
        Player victim = playerOf("amb");
        Warrior vw = warriorOf(victim);
        vw.setLevel(5);
        warriorRepository.save(vw);
        victim.addBronzeAmount(10_000);
        flagPlayer(victim, Zone.HIGH_RISK);

        registerAndGetToken(uniqueUser("amb"));
        Player attacker = playerOf("amb");
        Warrior aw = warriorOf(attacker);
        aw.setLevel(50); aw.setAttack(2000); aw.setDefense(2000); aw.setHealth(5000); // banda 40-60
        aw.setStrength(200); aw.setConstitution(200);
        warriorRepository.save(aw);

        for (int i = 0; i < 40; i++) {
            try {
                Player atk = playerRepository.findById(attacker.getId()).orElseThrow();
                Warrior w = warriorRepository.findByPlayer(atk).orElseThrow();
                w.setOnMission(false); w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(java.time.LocalDateTime.now());
                warriorRepository.save(w);
                var act = zoneService.enter(atk, Zone.HIGH_RISK,
                        com.medieval.game.enums.ActivityRole.GATHERING,
                        com.medieval.game.enums.SkillType.FISHING, 60);
                zoneService.collect(playerRepository.findById(attacker.getId()).orElseThrow(), act.getId());
            } catch (Exception ignore) {}
        }

        Player v = playerRepository.findById(victim.getId()).orElseThrow();
        assertThat(v.isPvpFlagged()).isTrue();    // fora da banda → nunca alcançada
        assertThat(v.isPvpShielded()).isFalse();  // nunca saqueada
    }

    // Helper: expõe um player numa zona (flagged por 1h)
    private void flagPlayer(Player p, Zone zone) {
        p.setPvpFlaggedZone(zone);
        p.setPvpFlaggedUntil(java.time.LocalDateTime.now().plusHours(1));
        playerRepository.save(p);
    }
}
