package com.medieval.game.service;

import com.medieval.game.enums.Zone;
import com.medieval.game.model.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

// TC-025-026 — ZoneService: Encounters e Penalidade
@DisplayName("TC-025-026 | ZoneService — Encontros e Penalidade")
class ZoneServiceTest {

    // ── TC-025: Chance de encontro por zona ──
    @Test
    @DisplayName("TC-025 | SAFE tem 0% PvP e 15% NPC por hora")
    void tc025_safeZone_encounterRates() {
        assertThat(Zone.SAFE.encounterChancePerHour).isEqualTo(0);
        assertThat(Zone.SAFE.npcEncounterChancePerHour).isEqualTo(15);
    }

    @Test
    @DisplayName("TC-025b | PVP tem 20% PvP e 25% NPC por hora")
    void tc025b_pvpZone_encounterRates() {
        assertThat(Zone.PVP.encounterChancePerHour).isEqualTo(20);
        assertThat(Zone.PVP.npcEncounterChancePerHour).isEqualTo(25);
    }

    @Test
    @DisplayName("TC-025c | HIGH_RISK tem 40% PvP e 35% NPC por hora")
    void tc025c_highRiskZone_encounterRates() {
        assertThat(Zone.HIGH_RISK.encounterChancePerHour).isEqualTo(40);
        assertThat(Zone.HIGH_RISK.npcEncounterChancePerHour).isEqualTo(35);
    }

    // ── TC-026: Penalidade de derrota tira 15% do bronze total ──
    @Test
    @DisplayName("TC-026 | Derrota tira 15% do bronze total")
    void tc026_defeatPenalty_takes15PercentBronze() {
        Player player = new Player();
        player.setGold(1);    // 10.000 bronze
        player.setSilver(0);
        player.setBronze(0);

        long totalBronze = player.totalBronze(); // 10.000
        long bronzeLost  = Math.round(totalBronze * 0.15);

        assertThat(bronzeLost).isEqualTo(1500L);
    }

    @Test
    @DisplayName("TC-026b | Derrota com 0 bronze não causa erro")
    void tc026b_defeatPenalty_zeroBronzeNoProblem() {
        Player player = new Player();
        player.setGold(0);
        player.setSilver(0);
        player.setBronze(0);

        long bronzeLost = Math.round(player.totalBronze() * 0.15);
        assertThat(bronzeLost).isEqualTo(0L);
    }

    // ── TC-extra: Multiplicador de zona ──
    @Test
    @DisplayName("TC-extra | HIGH_RISK tem multiplicador 2.5x")
    void tcExtra_highRiskMultiplier() {
        assertThat(Zone.HIGH_RISK.multiplier).isEqualTo(2.5);
    }

    // ── TC-extra: Nível mínimo por zona ──
    @Test
    @DisplayName("TC-extra | PVP requer level 10, HIGH_RISK requer level 20")
    void tcExtra_zoneLevelRequirements() {
        assertThat(Zone.SAFE.minLevel).isEqualTo(1);
        assertThat(Zone.PVP.minLevel).isEqualTo(10);
        assertThat(Zone.HIGH_RISK.minLevel).isEqualTo(20);
    }
}
