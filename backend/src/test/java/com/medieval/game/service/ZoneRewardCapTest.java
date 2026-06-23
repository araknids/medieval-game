package com.medieval.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ECON_EXPLOIT / hardening P1] A recompensa de zona escala por rounds = duração/10, mas a estamina
 * SATURA em 100 (staminaCostFor faz Math.min(100, …), atingido aos 200min). Sem cap, rounds cresceria
 * até 72 em 720min enquanto a estamina ficaria travada em 100 → eficiência/estamina ~3.6× na duração
 * máxima. {@link ZoneService#rewardRounds(int)} capa as rodadas no ponto de saturação (20), re-travando
 * a eficiência. Teste puro (sem Spring): exercita o invariante econômico direto.
 */
@DisplayName("Zona — teto de rodadas de recompensa (anti-inflação por duração longa)")
class ZoneRewardCapTest {

    @Test
    @DisplayName("rounds cresce linear até a saturação da estamina e depois trava em 20")
    void roundsCapAtStaminaSaturation() {
        assertThat(ZoneService.rewardRounds(50)).isEqualTo(5);    // 50/10
        assertThat(ZoneService.rewardRounds(100)).isEqualTo(10);  // 100/10
        assertThat(ZoneService.rewardRounds(200)).isEqualTo(20);  // estamina satura aqui (dur/2 = 100)
        assertThat(ZoneService.rewardRounds(360)).isEqualTo(20);  // capado
        assertThat(ZoneService.rewardRounds(720)).isEqualTo(20);  // duração máx → MESMA recompensa de 200min
    }

    @Test
    @DisplayName("piso de 1 rodada e cap = MAX_REWARD_ROUNDS")
    void roundsFloorAndCap() {
        assertThat(ZoneService.rewardRounds(5)).isEqualTo(1);   // Math.max(1, 0)
        assertThat(ZoneService.rewardRounds(0)).isEqualTo(1);
        assertThat(ZoneService.rewardRounds(99_999)).isEqualTo(ZoneService.MAX_REWARD_ROUNDS);
    }

    @Test
    @DisplayName("além de 200min a eficiência (rounds/estamina) NÃO passa da de 200min (~0.20)")
    void efficiencyDoesNotInflateWithLongDuration() {
        // estamina = min(100, max(5, dur/2)); eficiência = rounds / estamina.
        // O exploit era a eficiência DISPARAR acima de 200min — aqui ela fica ≤ a de 200min (0.20).
        for (int dur = 200; dur <= 720; dur += 20) {
            int    stamina = Math.min(100, Math.max(5, dur / 2));
            double eff     = (double) ZoneService.rewardRounds(dur) / stamina;
            assertThat(eff).as("eficiência em dur=%d min", dur).isLessThanOrEqualTo(0.20 + 1e-9);
        }
    }
}
