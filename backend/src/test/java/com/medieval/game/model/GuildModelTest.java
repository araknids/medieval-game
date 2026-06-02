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

    // Extra: maxMembers formula
    @Test
    @DisplayName("TC-extra | maxMembers level 1 → 15, level 2 → 20, level 3 → 25")
    void tcExtra_maxMembers_formula() {
        assertThat(guild(1).maxMembers()).isEqualTo(15);
        assertThat(guild(2).maxMembers()).isEqualTo(20);
        assertThat(guild(3).maxMembers()).isEqualTo(25);
    }

    // Extra: levelUpCost formula
    @Test
    @DisplayName("TC-extra | levelUpCost level 1 → 0, level 2 → 1000, level 3 → 2000")
    void tcExtra_levelUpCost_formula() {
        assertThat(guild(1).levelUpCost()).isEqualTo(0L);
        assertThat(guild(2).levelUpCost()).isEqualTo(1000L);
        assertThat(guild(3).levelUpCost()).isEqualTo(2000L);
    }
}
