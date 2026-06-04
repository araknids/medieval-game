package com.medieval.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Torre V2 — testes de INTENÇÃO de dificuldade (win-rate estatístico, Combate V2).
// player = {atk, def, hp, dex, strBonus, luk}
@DisplayName("Torre V2 | dificuldade por nível")
class TowerBalanceTest {

    private final BattleSimulator sim = new BattleSimulator();

    // bossForFloor não usa estado de instância — replicado aqui para teste puro (sem subir contexto).
    private TowerService.BossInfo boss(int floor) {
        String name = "Boss F" + floor;
        return new TowerService.BossInfo(
            name, 12 + floor * 5, 5 + floor * 3, 120 + floor * 45,
            Math.min(floor / 2, 8), Math.min(floor / 10, 3), Math.min(floor, 18));
    }

    private double winRate(int[] p, int floor, int trials) {
        var b = boss(floor);
        int wins = 0;
        for (int i = 0; i < trials; i++) {
            var out = sim.simulateDetailed(
                "P", p[0], p[1], p[2], p[3], p[4], p[5],
                b.name(), b.attack(), b.defense(), b.health(), b.dex(), b.strBonus(), b.luk(),
                true); // PvE: timeout = derrota
            if (out.firstWon()) wins++;
        }
        return wins / (double) trials;
    }

    private static final int[] FRESH_LVL1 = {15, 12, 110, 0, 0, 0};      // sem gear, sem atributos
    private static final int[] INVESTED   = {30, 28, 210, 2, 1, 4};      // ~lvl8 com kit + atributos

    @Test
    @DisplayName("Lvl1 pelado PERDE o Andar 1 (não dá pra facerollar)")
    void freshLvl1_losesFloor1() {
        assertThat(winRate(FRESH_LVL1, 1, 400)).isLessThan(0.20);
    }

    @Test
    @DisplayName("Lvl1 pelado praticamente não passa do Andar 3")
    void freshLvl1_cannotDoFloor3() {
        assertThat(winRate(FRESH_LVL1, 3, 400)).isLessThan(0.05);
    }

    @Test
    @DisplayName("Personagem investido (~lvl8 + gear) já encara o Andar 1")
    void investedChar_clearsFloor1() {
        assertThat(winRate(INVESTED, 1, 400)).isGreaterThan(0.50);
    }
}
