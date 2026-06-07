package com.medieval.game.service;

import com.medieval.game.enums.WarriorClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [CLASSES][REBALANCE] Balance da Path Trial: um RECRUIT Lv10 REALISTA precisa CONSEGUIR vencer cada Guardião.
 *
 * Modelo novo: acerto = d20 + DEX/5 − AGI_inimigo/8 ≥ 11 (sem AC). O Recruit Lv10 tem 18 pontos.
 * A AGI do guardião precisa ser baixa o bastante pra um build sem foco em DEX ainda acertar.
 * Roda o COMBATE REAL (BattleSimulator) N vezes e exige taxa de vitória sã.
 */
@DisplayName("Classes | Path Trial — guardiões vencíveis por um Recruit Lv10 realista")
class ClassTrialBalanceTest {

    private final BattleSimulator sim = new BattleSimulator();

    // Builds plausíveis de um Recruit Lv10 (base ATK 12 / DEF 10 / HP 100; 18 pontos; CON = +8 HP/pt).
    // Formato: {atk, def, hp, dex(acerto), agi(esquiva/velocidade), luk}. DEF não é atributo → fica 10.
    private static final int[] OFFENSIVE = { 22, 10, 100, 8, 0, 0 }; // STR10→ATK22, DEX8 (acc +1)
    private static final int[] DEFENSIVE = { 16, 10, 164, 6, 0, 0 }; // STR4→ATK16, CON8→HP164, DEX6
    private static final int[] AGILE     = { 18, 10, 100, 4, 8, 6 }; // STR6→ATK18, AGI8 (golpe extra/esquiva), LUK6
    private static final int[][] BUILDS  = { OFFENSIVE, DEFENSIVE, AGILE };

    private double winRate(int[] me, int[] g, int n) {
        int wins = 0;
        for (int i = 0; i < n; i++) {
            BattleSimulator.BattleOutcome o = sim.simulateDetailed(
                    "Recruit", me[0], me[1], me[2], me[3], me[4], me[5],
                    "Guardian", g[0], g[1], g[2], g[3], g[4], g[5],
                    true); // PvE: timeout = o Recruit perde (tem que MATAR o guardião)
            if (o.firstWon()) wins++;
        }
        return wins / (double) n;
    }

    @Test
    @DisplayName("Cada guardião é vencível (>45%) por algum build razoável, mas ainda é desafio (<97%)")
    void guardiansAreWinnableButNotTrivial() {
        int N = 500;
        for (WarriorClass path : new WarriorClass[]{ WarriorClass.WARRIOR, WarriorClass.ARCHER, WarriorClass.MERCHANT }) {
            int[] g = ClassChangeService.guardianStats(path);
            double best = 0, worst = 1;
            for (int[] b : BUILDS) {
                double wr = winRate(b, g, N);
                best  = Math.max(best, wr);
                worst = Math.min(worst, wr);
            }
            assertThat(best)
                    .as("%s: um Recruit Lv10 bem montado deveria vencer > 45%% (melhor build = %.2f)", path, best)
                    .isGreaterThan(0.45);
            assertThat(worst)
                    .as("%s: ainda é um desafio, não vitória garantida (pior build = %.2f)", path, worst)
                    .isLessThan(0.97);
        }
    }

    @Test
    @DisplayName("Nenhum guardião tem AGI alta demais (esquiva ≤ −1 no acerto +0 do Recruit)")
    void guardianAgiIsLowEnoughToBeHittable() {
        for (WarriorClass path : new WarriorClass[]{ WarriorClass.WARRIOR, WarriorClass.ARCHER, WarriorClass.MERCHANT }) {
            int agi = ClassChangeService.guardianStats(path)[4];
            assertThat(agi / 8)
                    .as("%s guardian AGI=%d → −%d no acerto; um Recruit sem DEX precisa ter chance real", path, agi, agi / 8)
                    .isLessThanOrEqualTo(1);
        }
    }
}
