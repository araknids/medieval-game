package com.medieval.game.service;

import com.medieval.game.enums.WarriorClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [TORRE_NARRATIVA] Sonda de balance da Torre: um Warrior BALANCEADO no nível recomendado (~1 andar por
 * nível, SEM gear) enfrenta o gauntlet do andar N. Mede win% pra tunar a escala dos monstros/MVPs.
 * Sem gear = piso conservador (jogador com gear passa mais fácil). Roda: mvn -q test -Dtest=TowerBalanceProbeTest
 */
class TowerBalanceProbeTest {

    private final BattleSimulator sim = new BattleSimulator();
    private final TowerService tower = new TowerService(null, null, null, sim, null, null, null, null, null,
            new Messages(new org.springframework.context.support.StaticMessageSource())); // [I18N] Messages real (defaults EN)
    private static final int N = 3000;

    /**
     * Warrior balanceado no nível dado: STR(dano)+DEX(acerto)+AGI+CON. {@code geared}=true soma um proxy
     * de gear típico do nível (ATK/DEF/HP/acerto) — é o alvo real de tuning (jogador equipado). [combat array]
     */
    private int[] balancedWarrior(int level, boolean geared) {
        int p = Math.max(0, (level - 1) * 2);
        WarriorClass W = WarriorClass.WARRIOR;
        int dex = Math.min(20, p / 4);  p -= dex;
        int agi = Math.min(W.agiCap, p / 6); p -= agi;
        int str = Math.min(W.strCap, (int) (p * 0.55)); p -= str;
        int con = p; // resto → HP
        int atk = W.baseAttack + str, def = W.baseDefense, hp = W.baseHealth + con * 8;
        if (geared) { // proxy de gear típico do nível (arma + armadura + afixos)
            atk += (int) Math.round(level * 1.7);
            def += (int) Math.round(level * 0.8);
            hp  += (int) Math.round(level * 7);
            dex += 10; // acerto da arma/afixos
        }
        return new int[]{ atk, def, hp, dex, agi, 0 };
    }

    /** Roda o gauntlet do andar (HP carrega entre monstros) com o player começando cheio. % de clears. */
    private double clearRate(int[] player, int floor, int n) {
        List<TowerService.BossInfo> monsters = tower.monstersFor(floor);
        int clears = 0;
        for (int i = 0; i < n; i++) {
            int hp = player[2];
            boolean alive = true;
            for (TowerService.BossInfo m : monsters) {
                BattleSimulator.BattleOutcome o = sim.simulateDetailed(
                        "P", player[0], player[1], hp, player[3], player[4], player[5],
                        m.name(), m.attack(), m.defense(), m.health(), m.dex(), m.agi(), m.luk(),
                        true, false, false);
                if (!o.firstWon()) { alive = false; break; }
                hp = o.firstHpFinal();
            }
            if (alive) clears++;
        }
        return 100.0 * clears / n;
    }

    @Test
    @DisplayName("Sonda: clear% por andar no nível recomendado (~1/level, sem gear)")
    void towerCurve() {
        StringBuilder out = new StringBuilder("\n=== TORRE — clear% (Warrior balanceado, nível=andar, SEM gear) ===\n");
        int[] floors = {5, 10, 20, 30, 40, 50};
        double atLevelMin = 100, atLevelMax = 0;
        for (int f : floors) {
            int lvl = TowerService.recommendedLevel(f);
            int[] me = balancedWarrior(lvl, true);                          // geared at-level (alvo)
            double atLevel = clearRate(me, f, N);
            double over5   = clearRate(balancedWarrior(lvl + 5, true), f, N);  // 5 níveis acima (geared)
            double noGear  = clearRate(balancedWarrior(lvl, false), f, N);     // sem gear (piso)
            boolean mvp = tower.isMvpFloor(f);
            out.append(String.format("  Floor %-2d (lvl %-2d)%s ATK %-3d HP %-4d | at-level %5.1f%%  +5 %5.1f%%  no-gear %5.1f%%%n",
                    f, lvl, mvp ? " [MVP]" : "     ", me[0], me[2], atLevel, over5, noGear));
            atLevelMin = Math.min(atLevelMin, atLevel);
            atLevelMax = Math.max(atLevelMax, atLevel);
        }
        out.append(String.format("  at-level range: %.0f%%–%.0f%% (alvo: desafiador mas vencível; gear dá folga)%n", atLevelMin, atLevelMax));
        System.out.println(out);

        // sanidade frouxa: no nível recomendado, a torre é jogável (nem impossível, nem trivial em todo andar)
        assertThat(atLevelMax).isGreaterThan(30.0);
        assertThat(atLevelMin).isLessThan(99.5);
    }
}
