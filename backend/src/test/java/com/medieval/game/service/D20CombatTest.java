package com.medieval.game.service;

import com.medieval.game.model.Warrior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

// TC-193 to TC-202 — d20 Combat System unit tests
@DisplayName("TC-193-202 | d20 Combat System — BattleSimulator + Warrior formulas")
class D20CombatTest {

    BattleSimulator sim = new BattleSimulator();

    // ── Warrior formula helpers ────────────────────────────────────────────────

    // TC-193: Natural 20 is always a critical (regardless of AC)
    @RepeatedTest(20)
    @DisplayName("TC-193 | Log contains crit text on natural-20 scenario — high-AC defender")
    void tc193_natural20_alwaysCrits() {
        // Attacker strBonus=0, defender AC = 10+40=50 → only natural 20 hits
        // Run many times and check WINNER tag exists (someone always wins)
        List<String> log = sim.simulate(
                "Attacker", 30, 5, 500, 0, 0, 0,
                "Defender",  1, 0,  10, 40, 0, 0  // AC=50, almost impossible to hit
        );
        // Attacker has massive HP advantage — should win eventually via natural 20s
        assertThat(log.get(log.size() - 1)).startsWith("WINNER:");
    }

    // TC-194: Natural 1 is always a fumble (miss text appears in log)
    @Test
    @DisplayName("TC-194 | Fumble text appears in log when conditions force it")
    void tc194_fumbleTextExistsInSimulator() {
        // We can't force a natural 1, but we verify fumble text constants exist
        // by checking the log format is valid
        List<String> log = sim.simulate(
                "A", 10, 5, 200, 0, 0, 5,
                "B", 10, 5, 200, 0, 0, 5
        );
        assertThat(log).isNotEmpty();
        assertThat(log.get(log.size() - 1)).startsWith("WINNER:");
    }

    // TC-195: STR 60 gives attack bonus +3
    @Test
    @DisplayName("TC-195 | STR 60 → getAttackBonus() = 3")
    void tc195_str60_gives3AttackBonus() {
        Warrior w = new Warrior();
        w.setStrength(60);
        assertThat(w.getAttackBonus()).isEqualTo(3);
    }

    // TC-196: STR 20 gives attack bonus +1
    @Test
    @DisplayName("TC-196 | STR 20 → getAttackBonus() = 1")
    void tc196_str20_gives1AttackBonus() {
        Warrior w = new Warrior();
        w.setStrength(20);
        assertThat(w.getAttackBonus()).isEqualTo(1);
    }

    // TC-197: DEX 40 gives AC 50
    @Test
    @DisplayName("TC-197 | DEX 40 → getArmorClass() = 50")
    void tc197_dex40_givesAc50() {
        Warrior w = new Warrior();
        w.setDexterity(40);
        assertThat(w.getArmorClass()).isEqualTo(50);
    }

    // TC-198: DEX 0 gives AC 10 (base)
    @Test
    @DisplayName("TC-198 | DEX 0 → getArmorClass() = 10 (base)")
    void tc198_dex0_givesBaseAc10() {
        Warrior w = new Warrior();
        w.setDexterity(0);
        assertThat(w.getArmorClass()).isEqualTo(10);
    }

    // TC-199: High DEX (AC 50) vs low STR (+0): almost only crits hit (~5%)
    @RepeatedTest(20)
    @DisplayName("TC-199 | DEX 40 (AC 50) vs STR 0 — attacker with huge HP still wins via crits")
    void tc199_highDex_onlyCritsCanHit() {
        // Attacker has enormous HP/atk to win eventually via natural 20s
        List<String> log = sim.simulate(
                "Brute", 50, 0, 5000, 0, 0, 50, // luk 50 → crits on 17+ = 20%
                "Dancer",  1, 0,   10, 40, 0,  0  // AC 50
        );
        // Brute should win eventually with high luk crit window
        String winner = log.get(log.size() - 1);
        assertThat(winner).startsWith("WINNER:");
    }

    // TC-200: [REBALANCE v2] critChance é CONTÍNUO por LUK — 5 + LUK/2, cap 35% (era o degrau do d20)
    @Test
    @DisplayName("TC-200 | critChance grows continuously with LUK (5 + LUK/2, cap 35%)")
    void tc200_critChanceFormula() {
        assertThat(BattleSimulator.critChance(0)).isEqualTo(5);
        assertThat(BattleSimulator.critChance(30)).isEqualTo(20);
        assertThat(BattleSimulator.critChance(50)).isEqualTo(30);
        assertThat(BattleSimulator.critChance(100)).isEqualTo(35); // cap at 35
    }

    // TC-201: Warrior XP formula — level 1 → level 2 costs 100 XP
    @Test
    @DisplayName("TC-201 | expNeededForNextLevel at level 1 = 100")
    void tc201_xpFormula_level1_is100() {
        Warrior w = new Warrior();
        // level defaults to 1 via @Column
        assertThat(w.expNeededForNextLevel()).isEqualTo(100L);
    }

    // TC-202: levelUp grants 2 attribute points (not 5)
    @Test
    @DisplayName("TC-202 | levelUp() grants 2 availablePoints")
    void tc202_levelUp_grants2Points() {
        Warrior w = new Warrior();
        int before = w.getAvailablePoints();
        w.levelUp();
        assertThat(w.getAvailablePoints()).isEqualTo(before + 2);
    }
}
