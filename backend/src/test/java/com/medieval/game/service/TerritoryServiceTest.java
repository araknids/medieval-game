package com.medieval.game.service;

import com.medieval.game.enums.Territory;
import com.medieval.game.model.TerritoryControl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// TC-041 to TC-048 — Territory War unit tests
@DisplayName("TC-041-048 | TerritoryService — Debuff, NPC, Brawl")
class TerritoryServiceTest {

    private TerritoryControl control(int streak) {
        TerritoryControl tc = new TerritoryControl();
        tc.setDefenseStreak(streak);
        return tc;
    }

    // TC-041: streak 0 → debuff 0%
    @Test
    @DisplayName("TC-041 | defenseStreak 0 → debuffPercent 0")
    void tc041_streak0_noDebuff() {
        assertThat(control(0).debuffPercent()).isEqualTo(0);
    }

    // TC-042: streak 1 → debuff 5%
    @Test
    @DisplayName("TC-042 | defenseStreak 1 → debuffPercent 5")
    void tc042_streak1_5percent() {
        assertThat(control(1).debuffPercent()).isEqualTo(5);
    }

    // TC-043: streak 10 → debuff capped at 50%
    @Test
    @DisplayName("TC-043 | defenseStreak 10 → debuffPercent 50 (cap)")
    void tc043_streak10_cappedAt50() {
        assertThat(control(10).debuffPercent()).isEqualTo(50);
        assertThat(control(20).debuffPercent()).isEqualTo(50);
    }

    // TC-044: streak 3 → debuff 15%
    @Test
    @DisplayName("TC-044 | defenseStreak 3 → debuffPercent 15")
    void tc044_streak3_15percent() {
        assertThat(control(3).debuffPercent()).isEqualTo(15);
    }

    // TC-045: TerritoryBonus.NONE has all zeros
    @Test
    @DisplayName("TC-045 | TerritoryBonus.NONE → all bonus values = 0")
    void tc045_bonusNone_allZero() {
        TerritoryService.TerritoryBonus none = TerritoryService.TerritoryBonus.NONE;
        assertThat(none.xpBonus()).isEqualTo(0);
        assertThat(none.bronzeBonus()).isEqualTo(0);
        assertThat(none.miningBonus()).isEqualTo(0);
        assertThat(none.fishingBonus()).isEqualTo(0);
        assertThat(none.questXpBonus()).isEqualTo(0);
    }

    // TC-046: TerritoryBonus for Minas de Ferro Negro has miningBonus = 20
    @Test
    @DisplayName("TC-046 | TerritoryBonus(MINAS_DE_FERRO_NEGRO) → miningBonus = 20")
    void tc046_minasBonus_mining20() {
        TerritoryService.TerritoryBonus bonus =
                new TerritoryService.TerritoryBonus(Territory.MINAS_DE_FERRO_NEGRO, 10, 10);
        assertThat(bonus.miningBonus()).isEqualTo(20);
        assertThat(bonus.fishingBonus()).isEqualTo(0);
        assertThat(bonus.questXpBonus()).isEqualTo(0);
    }

    // TC-047: TerritoryBonus for Desfiladeiro do Osso has fishingBonus = 20
    @Test
    @DisplayName("TC-047 | TerritoryBonus(DESFILADEIRO_DO_OSSO) → fishingBonus = 20")
    void tc047_desfiladeiro_fishing20() {
        TerritoryService.TerritoryBonus bonus =
                new TerritoryService.TerritoryBonus(Territory.DESFILADEIRO_DO_OSSO, 10, 10);
        assertThat(bonus.fishingBonus()).isEqualTo(20);
        assertThat(bonus.miningBonus()).isEqualTo(0);
    }

    // TC-048: TerritoryBonus for Fortaleza Maldita has questXpBonus = 10
    @Test
    @DisplayName("TC-048 | TerritoryBonus(FORTALEZA_MALDITA) → questXpBonus = 10")
    void tc048_fortaleza_questXp10() {
        TerritoryService.TerritoryBonus bonus =
                new TerritoryService.TerritoryBonus(Territory.FORTALEZA_MALDITA, 10, 10);
        assertThat(bonus.questXpBonus()).isEqualTo(10);
        assertThat(bonus.miningBonus()).isEqualTo(0);
        assertThat(bonus.fishingBonus()).isEqualTo(0);
    }
}
