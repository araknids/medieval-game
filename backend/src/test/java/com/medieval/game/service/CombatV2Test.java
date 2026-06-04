package com.medieval.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Combate V2 — mitigação % e regra do teto de 40 rounds (PvE = derrota / PvP = %HP).
@DisplayName("Combate V2 | mitigação % + timeout")
class CombatV2Test {

    private final BattleSimulator sim = new BattleSimulator();

    @Test
    @DisplayName("Dano usa mitigação % (não subtração) com piso 1")
    void mitigation_isPercentNotSubtraction() {
        assertThat(BattleSimulator.mitigatedDamage(15, 5)).isEqualTo(14);   // 15×100/105 ≈ 14
        assertThat(BattleSimulator.mitigatedDamage(8, 12)).isEqualTo(7);    // antes era max(1,8-12)=1
        assertThat(BattleSimulator.mitigatedDamage(100, 100)).isEqualTo(50);// 50% de redução
        assertThat(BattleSimulator.mitigatedDamage(10, 0)).isEqualTo(10);   // sem DEF, dano cheio
        assertThat(BattleSimulator.mitigatedDamage(1, 100000)).isEqualTo(1);// piso 1, nunca zera
    }

    @Test
    @DisplayName("PvE: não matou em 40 rounds → desafiante (1º) PERDE")
    void pve_timeout_challengerLoses() {
        // ATK 1 dos dois → 1 de dano/golpe; HP gigante → ninguém morre em 40 rounds.
        var out = sim.simulateDetailed(
            "Player", 1, 0, 100_000, 0, 0, 0,
            "Boss",   1, 0, 100_000, 0, 0, 0,
            true); // PvE
        assertThat(out.firstWon()).isFalse();
    }

    @Test
    @DisplayName("PvP: no timeout vence quem tem maior % de HP restante")
    void pvp_timeout_higherHpPctWins() {
        // Ninguém morre em 40 rounds; o 1º tem MUITO mais HP máx → %HP maior → vence.
        var out = sim.simulateDetailed(
            "Player", 1, 0, 100_000, 0, 0, 0,
            "Boss",   1, 0,   1_000, 0, 0, 0,
            false); // PvP (default)
        assertThat(out.firstWon()).isTrue();
    }
}
