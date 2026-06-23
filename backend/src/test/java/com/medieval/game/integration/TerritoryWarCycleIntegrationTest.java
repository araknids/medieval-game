package com.medieval.game.integration;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.Player;
import com.medieval.game.model.TerritoryControl;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.Guild;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.TerritoryControlRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.TerritoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-219 to TC-228 — Kingdom War resolution cycle (resolveTerritory) integration tests
@DisplayName("TC-219-228 | Kingdom War Cycle — resolveTerritory")
class TerritoryWarCycleIntegrationTest extends BaseIntegrationTest {

    @Autowired TerritoryService           territoryService;
    @Autowired TerritoryControlRepository controlRepo;
    @Autowired WarriorRepository          warriorRepository;
    @Autowired PlayerRepository           playerRepository;
    @Autowired GuildRepository            guildRepository;

    /** Funds the controlling guild's treasury so it can pay territory upkeep. */
    private void fundControllingGuild(Kingdom t, long bronze) {
        TerritoryControl ctrl = territoryService.getTerritory(t);
        Guild guild = ctrl.getControllingGuild();
        if (guild != null) {
            guild.setTreasuryBronze(bronze);
            guildRepository.save(guild);
        }
    }

    String leaderToken;

    @BeforeEach
    void setup() throws Exception {
        leaderToken = registerAndGetToken(uniqueUser("twc"));
    }

    private Player leaderPlayer() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("twc"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    /** Makes the leader's warrior strong enough to crush NPC defenders deterministically. */
    private void buffWarrior(Player p) {
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setAttack(200);
        w.setHealth(5000);
        w.setStrength(60);
        w.setCurrentHpSnapshot(100);
        w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);
    }

    private long createGuildAndDeclare(Kingdom t) throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "TWC_" + System.nanoTime(), "description", ""))));
        Player leader = leaderPlayer();
        buffWarrior(leader);
        mockMvc.perform(post("/api/territory/" + t.name() + "/declare")
                .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk());
        return territoryService.currentCycleId() + 1; // declare targets next cycle
    }

    // ── TC-219: Sem declarações → território neutro continua neutro ──
    @Test
    @DisplayName("TC-219 | No declarations on neutral territory → stays neutral")
    void tc219_noDeclarations_staysNeutral() {
        long cycle = territoryService.currentCycleId();
        territoryService.resolveTerritory(Kingdom.MINING, cycle);

        TerritoryControl ctrl = territoryService.getTerritory(Kingdom.MINING);
        assertThat(ctrl.isNeutral()).isTrue();
    }

    // ── TC-220: Guild forte ataca neutro → captura o território ──
    @Test
    @DisplayName("TC-220 | Strong guild attacks neutral → captures territory")
    void tc220_strongGuild_capturesNeutral() throws Exception {
        long cycle = createGuildAndDeclare(Kingdom.COMBAT);
        territoryService.resolveTerritory(Kingdom.COMBAT, cycle);

        TerritoryControl ctrl = territoryService.getTerritory(Kingdom.COMBAT);
        assertThat(ctrl.isNeutral()).isFalse();
        assertThat(ctrl.getControllingGuild()).isNotNull();
    }

    // ── TC-221: Território controlado sem novos ataques → defenseStreak +1 ──
    @Test
    @DisplayName("TC-221 | Held territory, no attacks → defenseStreak increments")
    void tc221_heldTerritory_streakIncrements() throws Exception {
        long cycle = createGuildAndDeclare(Kingdom.FISHING);
        territoryService.resolveTerritory(Kingdom.FISHING, cycle);

        TerritoryControl held = territoryService.getTerritory(Kingdom.FISHING);
        assertThat(held.isNeutral()).isFalse();
        int streakBefore = held.getDefenseStreak();

        // Fund the treasury so the guild can pay upkeep and keep the territory
        fundControllingGuild(Kingdom.FISHING, 10_000);

        // Resolve a later cycle with no declarations → streak should increment
        territoryService.resolveTerritory(Kingdom.FISHING, cycle + 5);
        TerritoryControl after = territoryService.getTerritory(Kingdom.FISHING);
        assertThat(after.getDefenseStreak()).isEqualTo(streakBefore + 1);
    }

    // ── TC-222: Bônus de território aplicado aos membros da guild dominante ──
    @Test
    @DisplayName("TC-222 | Controlling guild member gets +10% XP / +10% bronze bonus")
    void tc222_controllingGuild_getsBonus() throws Exception {
        long cycle = createGuildAndDeclare(Kingdom.COMBAT);
        territoryService.resolveTerritory(Kingdom.COMBAT, cycle);

        Player leader = leaderPlayer();
        TerritoryService.TerritoryBonus bonus = territoryService.getBonusForPlayer(leader);
        assertThat(bonus.territory()).isEqualTo(Kingdom.COMBAT);
        assertThat(bonus.xpBonus()).isEqualTo(10);
        assertThat(bonus.bronzeBonus()).isEqualTo(10);
    }

    // ── TC-223: Jogador sem guild → bônus NONE ──
    @Test
    @DisplayName("TC-223 | Player without guild → TerritoryBonus.NONE")
    void tc223_noGuild_noBonus() {
        Player leader = leaderPlayer(); // no guild created in this test
        TerritoryService.TerritoryBonus bonus = territoryService.getBonusForPlayer(leader);
        assertThat(bonus.territory()).isNull();
        assertThat(bonus.xpBonus()).isEqualTo(0);
    }

    // ── TC-224: getBonusForPlayer exclusivo por território (Fortaleza → questXp) ──
    @Test
    @DisplayName("TC-224 | Exclusive bonus maps to territory (FORTALEZA → questXpBonus)")
    void tc224_exclusiveBonus_perTerritory() throws Exception {
        long cycle = createGuildAndDeclare(Kingdom.COMBAT);
        territoryService.resolveTerritory(Kingdom.COMBAT, cycle);

        Player leader = leaderPlayer();
        TerritoryService.TerritoryBonus bonus = territoryService.getBonusForPlayer(leader);
        assertThat(bonus.questXpBonus()).isGreaterThan(0); // FORTALEZA gives quest XP bonus
        assertThat(bonus.miningBonus()).isEqualTo(0);
        assertThat(bonus.fishingBonus()).isEqualTo(0);
    }

    // ── TC-225: Guild já controlando não pode declarar novo ataque ──
    @Test
    @DisplayName("TC-225 | Guild already holding a territory cannot declare attack")
    void tc225_holdingGuild_cannotDeclare() throws Exception {
        long cycle = createGuildAndDeclare(Kingdom.COMBAT);
        territoryService.resolveTerritory(Kingdom.COMBAT, cycle);

        // Now holding FORTALEZA — try to declare on another territory
        mockMvc.perform(post("/api/territory/MINING/declare")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── TC-226: Captura reseta defenseStreak para 0 ──
    @Test
    @DisplayName("TC-226 | Capturing a territory sets defenseStreak to 0")
    void tc226_capture_resetsStreak() throws Exception {
        long cycle = createGuildAndDeclare(Kingdom.MINING);
        territoryService.resolveTerritory(Kingdom.MINING, cycle);

        TerritoryControl ctrl = territoryService.getTerritory(Kingdom.MINING);
        assertThat(ctrl.getDefenseStreak()).isEqualTo(0);
    }

    // ── TC-227: ensureInitialized cria os 3 territórios ──
    @Test
    @DisplayName("TC-227 | All 3 territories exist after ensureInitialized")
    void tc227_allTerritoriesInitialized() {
        territoryService.ensureInitialized();
        assertThat(controlRepo.findAll()).hasSizeGreaterThanOrEqualTo(3);
    }

    // ── TC-228: debuffPercent caps at 50% via TerritoryControl ──
    @Test
    @DisplayName("TC-228 | defenseStreak debuff caps at 50%")
    void tc228_debuffCapsAt50() {
        TerritoryControl ctrl = new TerritoryControl();
        ctrl.setDefenseStreak(100);
        assertThat(ctrl.debuffPercent()).isEqualTo(50);
    }
}
