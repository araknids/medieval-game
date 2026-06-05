package com.medieval.game.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// TC-036 to TC-040 — Guild passive bonus formulas
@DisplayName("TC-036-040 | Guild — Passive Bonus Formulas")
class GuildModelTest {

    private Guild guild(int level) {
        Guild g = new Guild();
        g.setLevel(level);
        return g;
    }

    // TC-036: xpBonus level 1 → 0%
    @Test
    @DisplayName("TC-036 | xpBonus level 1 → 0%")
    void tc036_xpBonus_level1_isZero() {
        assertThat(guild(1).xpBonus()).isEqualTo(0);
    }

    // TC-037: xpBonus level 3 → 10%
    @Test
    @DisplayName("TC-037 | xpBonus level 3 → 10%")
    void tc037_xpBonus_level3_is10() {
        assertThat(guild(3).xpBonus()).isEqualTo(10);
    }

    // TC-038: xpBonus caps at 20% (level 5+)
    @Test
    @DisplayName("TC-038 | xpBonus level 5 → 20%, level 6 → still 20% (cap)")
    void tc038_xpBonus_capsAt20() {
        assertThat(guild(5).xpBonus()).isEqualTo(20);
        assertThat(guild(6).xpBonus()).isEqualTo(20);
        assertThat(guild(10).xpBonus()).isEqualTo(20);
    }

    // TC-039: dropBonus formula and cap
    @Test
    @DisplayName("TC-039 | dropBonus level 2 → 0%, level 3 → 2%, level 5 → 6%, level 7 → 7% (cap)")
    void tc039_dropBonus_formulaAndCap() {
        assertThat(guild(1).dropBonus()).isEqualTo(0);
        assertThat(guild(2).dropBonus()).isEqualTo(0);
        assertThat(guild(3).dropBonus()).isEqualTo(2);
        assertThat(guild(4).dropBonus()).isEqualTo(4);
        assertThat(guild(5).dropBonus()).isEqualTo(6);
        assertThat(guild(6).dropBonus()).isEqualTo(7); // cap
        assertThat(guild(10).dropBonus()).isEqualTo(7); // still capped
    }

    // TC-040: bronzeBonus formula and cap
    @Test
    @DisplayName("TC-040 | bronzeBonus level 3 → 0%, level 4 → 5%, level 5 → 10%, level 6 → 10% (cap)")
    void tc040_bronzeBonus_formulaAndCap() {
        assertThat(guild(1).bronzeBonus()).isEqualTo(0);
        assertThat(guild(2).bronzeBonus()).isEqualTo(0);
        assertThat(guild(3).bronzeBonus()).isEqualTo(0);
        assertThat(guild(4).bronzeBonus()).isEqualTo(5);
        assertThat(guild(5).bronzeBonus()).isEqualTo(10);
        assertThat(guild(6).bronzeBonus()).isEqualTo(10); // cap
        assertThat(guild(10).bronzeBonus()).isEqualTo(10); // still capped
    }

    // Extra: maxMembers formula — 10 no nível 1, +5/nível, teto 50.
    @Test
    @DisplayName("TC-extra | maxMembers: lv1→10, lv2→15, lv3→20, teto 50")
    void tcExtra_maxMembers_formula() {
        assertThat(guild(1).maxMembers()).isEqualTo(10);
        assertThat(guild(2).maxMembers()).isEqualTo(15);
        assertThat(guild(3).maxMembers()).isEqualTo(20);
        assertThat(guild(9).maxMembers()).isEqualTo(50);   // 10 + 8×5 = 50
        assertThat(guild(20).maxMembers()).isEqualTo(50);  // teto
    }

    // ── [GUILD_LEVEL_GOLD] Nível derivado do gold acumulado ──

    @Test
    @DisplayName("goldThreshold | curva prestígio (Lv2=10k, Lv5=100k, Lv10=450k, cap)")
    void goldThreshold_curve() {
        assertThat(Guild.goldThreshold(1)).isZero();
        assertThat(Guild.goldThreshold(2)).isEqualTo(10_000L);
        assertThat(Guild.goldThreshold(5)).isEqualTo(100_000L);
        assertThat(Guild.goldThreshold(10)).isEqualTo(450_000L);
        assertThat(Guild.goldThreshold(11)).isEqualTo(450_000L); // cap no MAX_LEVEL
    }

    @Test
    @DisplayName("levelForGold | limiares exatos e cap no Lv10")
    void levelForGold_thresholds() {
        assertThat(Guild.levelForGold(0)).isEqualTo(1);
        assertThat(Guild.levelForGold(9_999)).isEqualTo(1);
        assertThat(Guild.levelForGold(10_000)).isEqualTo(2);
        assertThat(Guild.levelForGold(449_999)).isEqualTo(9);
        assertThat(Guild.levelForGold(450_000)).isEqualTo(10);
        assertThat(Guild.levelForGold(9_999_999)).isEqualTo(10); // cap
    }

    @Test
    @DisplayName("recomputeLevel | sobe conforme o acumulado e NUNCA rebaixa")
    void recomputeLevel_monotonic() {
        Guild g = new Guild();
        g.setLifetimeGold(100_000); // Lv5
        g.recomputeLevel();
        assertThat(g.getLevel()).isEqualTo(5);
        g.recomputeLevel(); // estável
        assertThat(g.getLevel()).isEqualTo(5);

        // guild legada com nível alto e pouco acumulado NÃO é rebaixada
        Guild legacy = new Guild();
        legacy.setLevel(8);
        legacy.setLifetimeGold(0);
        legacy.recomputeLevel();
        assertThat(legacy.getLevel()).isEqualTo(8);
    }

    @Test
    @DisplayName("goldForNextLevel / goldToNextLevel / levelProgressPct")
    void nextLevelHelpers() {
        Guild g = new Guild();
        g.setLifetimeGold(10_000); // exatamente Lv2
        g.recomputeLevel();
        assertThat(g.getLevel()).isEqualTo(2);
        assertThat(g.goldForNextLevel()).isEqualTo(30_000L); // Lv3
        assertThat(g.goldToNextLevel()).isEqualTo(20_000L);  // 30k - 10k
        assertThat(g.levelProgressPct()).isZero();           // início do Lv2

        Guild maxg = new Guild();
        maxg.setLifetimeGold(450_000);
        maxg.recomputeLevel();
        assertThat(maxg.getLevel()).isEqualTo(10);
        assertThat(maxg.goldForNextLevel()).isEqualTo(-1L);
        assertThat(maxg.goldToNextLevel()).isZero();
        assertThat(maxg.levelProgressPct()).isEqualTo(100);
    }
}
