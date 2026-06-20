package com.medieval.game.service;

import com.medieval.game.service.BattleSimulator.BattleEvent;
import com.medieval.game.service.BattleSimulator.BattleOutcome;
import com.medieval.game.service.BattleSimulator.GroupFoe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

// [TORRE_GRUPO] simulateGroup: 1 jogador vs N inimigos SIMULTÂNEOS (andares de vários monstros da Torre).
// Reusa o motor 1x1; o teste cobre o contrato de eventos (spawns) + os dois desfechos.
@DisplayName("TORRE_GRUPO | BattleSimulator.simulateGroup — 1 vs N ao mesmo tempo")
class BattleGroupTest {

    private final BattleSimulator sim = new BattleSimulator();

    @Test
    @DisplayName("herói esmagador derruba os 2 inimigos → firstWon + 3 spawns + victory do herói")
    void heroCrushesTwoFoes() {
        BattleOutcome out = sim.simulateGroup(
                "Hero", 120, 40, 4000, 30, 10, 10,
                List.of(new GroupFoe("Husk I", 4, 0, 30, 0, 0, 0),
                        new GroupFoe("Husk II", 4, 0, 30, 0, 0, 0)),
                true, false);

        assertThat(out.firstWon()).isTrue();
        assertThat(out.log().get(out.log().size() - 1)).startsWith("WINNER:Hero");

        // 1 spawn do jogador + 1 por inimigo (= 3), cada um com maxHp coerente.
        List<BattleEvent> spawns = out.events().stream().filter(e -> e.type().equals("spawn")).toList();
        assertThat(spawns).hasSize(3);
        assertThat(spawns.get(0).actor()).isEqualTo("Hero");          // jogador = 1º spawn (esquerda no replay)
        assertThat(spawns).allSatisfy(s -> assertThat(s.targetMaxHp()).isPositive());

        // 1 victory, com o herói como vencedor.
        List<BattleEvent> victory = out.events().stream().filter(e -> e.type().equals("victory")).toList();
        assertThat(victory).hasSize(1);
        assertThat(victory.get(0).actor()).isEqualTo("Hero");
    }

    @Test
    @DisplayName("herói frágil vs 2 inimigos brutais → firstWon=false (timeout/morte = derrota PvE)")
    void weakHeroLosesToTwoBrutes() {
        BattleOutcome out = sim.simulateGroup(
                "Hero", 5, 0, 40, 0, 0, 0,
                List.of(new GroupFoe("Brute I", 200, 80, 5000, 30, 10, 10),
                        new GroupFoe("Brute II", 200, 80, 5000, 30, 10, 10)),
                true, false);

        assertThat(out.firstWon()).isFalse();
        assertThat(out.log().get(out.log().size() - 1)).startsWith("WINNER:");
        // o vencedor (último log) NÃO é o herói
        assertThat(out.log().get(out.log().size() - 1)).doesNotStartWith("WINNER:Hero|");
    }

    @Test
    @DisplayName("um só inimigo na lista ainda funciona (degrada pro caso trivial)")
    void singleFoeStillWorks() {
        BattleOutcome out = sim.simulateGroup(
                "Hero", 120, 40, 4000, 30, 10, 10,
                List.of(new GroupFoe("Lonely", 4, 0, 30, 0, 0, 0)),
                true, false);

        assertThat(out.firstWon()).isTrue();
        List<BattleEvent> spawns = out.events().stream().filter(e -> e.type().equals("spawn")).toList();
        assertThat(spawns).hasSize(2); // jogador + 1 inimigo
    }
}
