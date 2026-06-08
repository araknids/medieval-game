package com.medieval.game.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

// TC-005 a TC-008 — Player Model: Stamina e HP
@DisplayName("TC-005-008 | Player — Stamina e HP")
class PlayerModelTest {

    // ── TC-005: Stamina regenera corretamente com tempo ──
    @Test
    @DisplayName("TC-005 | getCalculatedStamina recupera ao longo do tempo")
    void tc005_calculatedStamina_regenOverTime() {
        Player player = new Player();
        player.setCreatedAt(LocalDateTime.now().minusDays(10)); // [BUFF_NOVATO] conta antiga → regen normal 60min
        player.setCurrentStamina(0);
        player.setStaminaUpdatedAt(LocalDateTime.now().minusMinutes(30)); // 30 min atrás

        int stamina = player.getCalculatedStamina();

        // SEM_TIMER: regen 100% em 1h → 30 min = 50. (1h cheia = 100). Buff de novato (15min) testado em NewbieBuffTest.
        assertThat(stamina).isEqualTo(50);
    }

    // ── TC-006: Stamina não ultrapassa 100 ──
    @Test
    @DisplayName("TC-006 | getCalculatedStamina não ultrapassa 100")
    void tc006_calculatedStamina_capsAt100() {
        Player player = new Player();
        player.setCurrentStamina(80);
        player.setStaminaUpdatedAt(LocalDateTime.now().minusHours(3)); // muito tempo

        assertThat(player.getCalculatedStamina()).isEqualTo(100);
    }

    // ── TC-007: HP regenera corretamente em 30 minutos ──
    @Test
    @DisplayName("TC-007 | getCalculatedHpPercent recupera 50% em 30min")
    void tc007_calculatedHp_regenIn30Minutes() {
        Warrior warrior = new Warrior();
        warrior.setCurrentHpSnapshot(0);
        warrior.setHpUpdatedAt(LocalDateTime.now().minusMinutes(30)); // 30 min atrás

        // 30 min = 50% de regen (100% em 60 min)
        int hp = warrior.getCalculatedHpPercent();

        assertThat(hp).isEqualTo(50);
    }

    // ── TC-008: HP não ultrapassa 100 ──
    @Test
    @DisplayName("TC-008 | getCalculatedHpPercent não ultrapassa 100")
    void tc008_calculatedHp_capsAt100() {
        Warrior warrior = new Warrior();
        warrior.setCurrentHpSnapshot(80);
        warrior.setHpUpdatedAt(LocalDateTime.now().minusHours(5));

        assertThat(warrior.getCalculatedHpPercent()).isEqualTo(100);
    }
}
