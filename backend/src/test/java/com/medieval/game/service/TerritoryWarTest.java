package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.service.TerritoryService.BrawlResult;
import com.medieval.game.service.TerritoryService.Fighter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa o guildBrawl no modelo de FORMAÇÃO 3×5 (lanes): cada coluna é um gauntlet (frente vs
 * frente, vencedor segue com o HP real); vence quem leva ≥2 das 3 lanes. [GUERRA_FORMACAO]
 */
@DisplayName("Kingdom War — GuildBrawl por formação 3×5 (lanes)")
class TerritoryWarTest {

    private TerritoryService service;

    @BeforeEach
    void setup() {
        // guildBrawl não usa repos/statsService/abilityService → nulls; BattleSimulator real.
        service = new TerritoryService(null, null, null, null, null, new BattleSimulator(), null, null, null);
    }

    private Fighter f(String name, int atk, int def, int hp) {
        return new Fighter(null, name, atk, def, hp, 0, 0, 0, null);
    }

    /** Grade 3×5 preenchida frente→fundo (depth 0 lanes 0,1,2; depois depth 1…). */
    private Fighter[][] grid(Fighter... fs) {
        Fighter[][] g = new Fighter[3][5];
        int i = 0;
        for (int d = 0; d < 5 && i < fs.length; d++)
            for (int l = 0; l < 3 && i < fs.length; l++) g[l][d] = fs[i++];
        return g;
    }
    private Fighter[][] empty() { return new Fighter[3][5]; }

    // ── 1v1 (uma lane contestada) ──────────────────────────────────────────────
    @RepeatedTest(10)
    @DisplayName("1v1: atacante muito superior vence a lane")
    void brawl_strongAttackerWins() {
        BrawlResult r = service.guildBrawl(grid(f("Strong", 100, 50, 300)), grid(f("Weak", 5, 2, 20)), Kingdom.COMBAT);
        assertThat(r.attackersWon()).isTrue();
    }

    @RepeatedTest(10)
    @DisplayName("1v1: defensor muito superior segura a lane")
    void brawl_strongDefenderWins() {
        BrawlResult r = service.guildBrawl(grid(f("Weak", 5, 2, 20)), grid(f("Strong", 100, 50, 300)), Kingdom.COMBAT);
        assertThat(r.attackersWon()).isFalse();
    }

    // ── Vantagem numérica espalhada (lanes vazias = W.O.) ──────────────────────
    @Test
    @DisplayName("3 atacantes em 3 lanes vs 1 defensor → maioria garantida (2 lanes por W.O.)")
    void spreadNumericalAdvantage_winsMajority() {
        // A1[L0] A2[L1] A3[L2] vs D1[L0]: lanes 1 e 2 sem defensor → atacante leva 2 lanes sempre.
        BrawlResult r = service.guildBrawl(
                grid(f("A1", 25, 15, 80), f("A2", 25, 15, 80), f("A3", 25, 15, 80)),
                grid(f("D1", 25, 15, 80)),
                Kingdom.COMBAT);
        assertThat(r.attackersWon()).isTrue();
    }

    // ── HP carregado dentro da lane (gauntlet) ─────────────────────────────────
    @RepeatedTest(10)
    @DisplayName("Um forte limpa uma lane empilhada (carrega a vida restante)")
    void hpCarry_strongClearsStackedLane() {
        Fighter[][] atk = empty(); atk[0][0] = f("Strong", 100, 50, 300);          // lane 0, frente
        Fighter[][] def = empty(); def[0][0] = f("Weak1", 5, 2, 20); def[0][1] = f("Weak2", 5, 2, 20); // lane 0, 2 de profundidade
        BrawlResult r = service.guildBrawl(atk, def, Kingdom.COMBAT);
        assertThat(r.attackersWon()).isTrue(); // vence a única lane contestada
    }

    @RepeatedTest(10)
    @DisplayName("Fresco (200 HP) vence cansado (1 HP) na lane")
    void freshBeatsTired() {
        BrawlResult r = service.guildBrawl(grid(f("Fresh", 50, 30, 200)), grid(f("Tired", 50, 30, 1)), Kingdom.MINING);
        assertThat(r.attackersWon()).isTrue();
    }

    @RepeatedTest(5)
    @DisplayName("10× mais HP vence sempre")
    void betterHp_alwaysWins() {
        BrawlResult r = service.guildBrawl(grid(f("Strong", 40, 25, 500)), grid(f("Weak", 40, 25, 50)), Kingdom.MINING);
        assertThat(r.attackersWon()).isTrue();
    }

    // ── Bordas: lados vazios ───────────────────────────────────────────────────
    @Test
    @DisplayName("Sem atacantes → defensores seguram")
    void noAttackers_defendersWin() {
        assertThat(service.guildBrawl(empty(), grid(f("D1", 30, 20, 100)), Kingdom.COMBAT).attackersWon()).isFalse();
    }

    @Test
    @DisplayName("Sem defensores → atacantes conquistam")
    void noDefenders_attackersWin() {
        assertThat(service.guildBrawl(grid(f("A1", 30, 20, 100)), empty(), Kingdom.COMBAT).attackersWon()).isTrue();
    }

    // ── Log ────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Log tem início de batalha e linha de resultado")
    void brawl_logHasStartAndOutcome() {
        BrawlResult r = service.guildBrawl(grid(f("A", 30, 20, 100)), grid(f("D", 30, 20, 100)), Kingdom.COMBAT);
        String fullLog = String.join("\n", r.log());
        assertThat(fullLog).contains("The battle begins!");
        String lastLine = r.log().get(r.log().size() - 1);
        assertThat(lastLine).containsAnyOf("Attackers conquered", "Defenders held");
    }

    // ── Debuff cap (modelo) ────────────────────────────────────────────────────
    @Test
    @DisplayName("Debuff trava em 50% mesmo com streak alto")
    void debuffCapsAt50Percent() {
        var ctrl = new com.medieval.game.model.TerritoryControl();
        ctrl.setDefenseStreak(100);
        assertThat(ctrl.debuffPercent()).isEqualTo(50);
    }
}
