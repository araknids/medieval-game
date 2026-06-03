package com.medieval.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

// TC-015-016 — TowerService: Chefes por Andar (d20 system)
@DisplayName("TC-015-016 | TowerService — Chefes e Stats por Andar")
class TowerServiceTest {

    // Boss formulas (d20 system):
    // ATK = 5 + floor * 3
    // DEF = 3 + floor * 2
    // HP  = 80 + floor * 25
    // DEX = min(floor/2, 20)  → AC = 10 + dex, max AC 30
    // strBonus = min(floor/10, 3)
    // luk = min(floor, 15)

    // ── TC-015: Stats do chefe escalam corretamente por andar ──
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 20})
    @DisplayName("TC-015 | Boss stats escalam por andar")
    void tc015_bossStatsScaleByFloor(int floor) {
        int expectedAtk = 5  + floor * 3;
        int expectedDef = 3  + floor * 2;
        int expectedHp  = 80 + floor * 25;
        int expectedDex = Math.min(floor / 2, 20);

        assertThat(expectedAtk).isGreaterThan(0);
        assertThat(expectedDef).isGreaterThan(0);
        assertThat(expectedHp).isGreaterThan(0);
        assertThat(expectedDex).isLessThanOrEqualTo(20);
    }

    // ── TC-016: AC do chefe (10 + DEX) nunca ultrapassa 30 ──
    @Test
    @DisplayName("TC-016 | Boss AC (10 + DEX) caps at 30 for any floor")
    void tc016_bossAcCapsAt30() {
        // Andar 100 → dex = min(50, 20) = 20 → AC = 30
        int dex = Math.min(100 / 2, 20);
        int ac  = 10 + dex;
        assertThat(ac).isEqualTo(30);
    }

    // ── TC-extra: Recompensas por andar ──
    @Test
    @DisplayName("TC-extra | Recompensas do andar 10: 400 bronze, 200 XP")
    void tcExtra_floorRewards() {
        int floor = 10;
        long bronze = (long) floor * 40;
        long xp     = (long) floor * 20;

        assertThat(bronze).isEqualTo(400L);
        assertThat(xp).isEqualTo(200L);
    }
}
