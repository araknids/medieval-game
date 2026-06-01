package com.medieval.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

// TC-030-031 — BattleSimulator
@DisplayName("TC-030-031 | BattleSimulator — Log e Vencedor")
class BattleSimulatorTest {

    BattleSimulator simulator = new BattleSimulator();

    // ── TC-030: Tag WINNER está sempre presente ──
    @RepeatedTest(10)
    @DisplayName("TC-030 | Tag WINNER sempre presente no log")
    void tc030_winnerTagAlwaysPresent() {
        List<String> log = simulator.simulate(
                "Guerreiro", 20, 15, 150, 10,
                "Inimigo",   15, 10, 120, 8
        );

        String lastLine = log.get(log.size() - 1);
        assertThat(lastLine).startsWith("WINNER:");
    }

    // ── TC-031: Guerreiro muito mais forte sempre vence ──
    @RepeatedTest(5)
    @DisplayName("TC-031 | Guerreiro muito superior sempre vence")
    void tc031_strongWarriorAlwaysWins() {
        // Guerreiro com stats 10x maiores
        List<String> log = simulator.simulate(
                "Aldeão Forte", 100, 100, 1000, 0,
                "Rato Fraco",    1,   1,   10, 0
        );

        String winnerTag = log.get(log.size() - 1);
        assertThat(winnerTag).contains("WINNER:Aldeão Forte");
    }

    // ── TC-031b: Log não contém tag WINNER visível ao usuário ──
    @Test
    @DisplayName("TC-031b | Linhas visíveis do log não contêm a tag WINNER:")
    void tc031b_winnerTagIsOnlyInLastLine() {
        List<String> log = simulator.simulate(
                "A", 15, 10, 100, 10,
                "B", 10, 8,  80, 8
        );

        // Remove a última linha (tag interna)
        List<String> visibleLog = log.subList(0, log.size() - 1);

        visibleLog.forEach(line ->
                assertThat(line).doesNotStartWith("WINNER:")
        );
    }

    // ── TC-extra: Log começa com cabeçalho e HP dos lutadores ──
    @Test
    @DisplayName("TC-extra | Log contém cabeçalho e linha de HP")
    void tcExtra_logHasHeaderAndHpLine() {
        List<String> log = simulator.simulate(
                "Guerreiro", 15, 10, 100, 10,
                "Boss",      12, 8,  90, 8
        );

        assertThat(log.get(0)).contains("vs");
        assertThat(log.get(1)).contains("HP");
    }
}
