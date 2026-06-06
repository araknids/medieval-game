package com.medieval.game.service;

import com.medieval.game.enums.WarriorClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [CLASSES] Balance da Path Trial: um RECRUIT Lv10 REALISTA precisa CONSEGUIR vencer cada Guardião.
 *
 * Regressão do bug "AC 28 impossível de acertar": o Recruit Lv10 tem só 18 pontos → STR < 20 →
 * bônus de acerto = floor(STR/20) = 0. Logo o acerto é d20+0 e o AC do guardião precisa ser baixo
 * (~12–14). Este teste roda o COMBATE REAL (BattleSimulator) N vezes e exige taxa de vitória sã.
 */
@DisplayName("Classes | Path Trial — guardiões vencíveis por um Recruit Lv10 realista")
class ClassTrialBalanceTest {

    private final BattleSimulator sim = new BattleSimulator();

    // Builds plausíveis de um Recruit Lv10 (base ATK 12 / DEF 10 / HP 100; 18 pontos; CON = +8 HP/pt).
    // Formato: {atk, def, hp, dex(AC=10+dex), strBonus(=floor(STR/20)=0), luk}. DEF não é atributo → fica 10.
    private static final int[] OFFENSIVE = { 24, 10, 148, 0, 0, 0 }; // STR12→ATK24, CON6→HP148, AC10
    private static final int[] DEFENSIVE = { 16, 10, 180, 4, 0, 0 }; // STR4→ATK16, CON10→HP180, DEX4→AC14

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
    @DisplayName("Cada guardião é vencível (>45%) por um build razoável, mas ainda é desafio (<97%)")
    void guardiansAreWinnableButNotTrivial() {
        int N = 500;
        for (WarriorClass path : new WarriorClass[]{ WarriorClass.WARRIOR, WarriorClass.ARCHER, WarriorClass.MERCHANT }) {
            int[] g = ClassChangeService.guardianStats(path);
            double off = winRate(OFFENSIVE, g, N);
            double def = winRate(DEFENSIVE, g, N);
            double best = Math.max(off, def);

            assertThat(best)
                    .as("%s: um Recruit Lv10 bem montado deveria vencer > 45%% (off=%.2f def=%.2f)", path, off, def)
                    .isGreaterThan(0.45);
            assertThat(Math.min(off, def))
                    .as("%s: ainda é um desafio, não vitória garantida (off=%.2f def=%.2f)", path, off, def)
                    .isLessThan(0.97);
        }
    }

    @Test
    @DisplayName("Nenhum guardião tem AC inacessível ao acerto +0 do Recruit (AC ≤ 16)")
    void guardianAcIsHittableByZeroBonusRecruit() {
        for (WarriorClass path : new WarriorClass[]{ WarriorClass.WARRIOR, WarriorClass.ARCHER, WarriorClass.MERCHANT }) {
            int ac = 10 + ClassChangeService.guardianStats(path)[3]; // 10 + dex
            assertThat(ac)
                    .as("%s guardian AC=%d — d20+0 precisa ter chance real de acertar", path, ac)
                    .isLessThanOrEqualTo(16);
        }
    }
}
