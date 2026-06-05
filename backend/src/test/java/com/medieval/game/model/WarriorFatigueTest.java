package com.medieval.game.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Cansaço de guerra: stacking/reset por ciclo (sem Spring — só a matemática do model). [GUERRA_ROSTER]
@DisplayName("War Fatigue | acúmulo/reset por ciclo (GUERRA_ROSTER)")
class WarriorFatigueTest {

    private Warrior fresh() {
        return new Warrior(); // warFatigueStacks=0, warLastCycleFought=0
    }

    @Test
    @DisplayName("Guerreiro fresco → 0% de cansaço")
    void fresh_noFatigue() {
        Warrior w = fresh();
        assertThat(w.incomingFatigueStacks(100)).isZero();
        assertThat(w.fatiguePctForCycle(100)).isZero();
    }

    @Test
    @DisplayName("Ciclos consecutivos acumulam -10%/ciclo")
    void consecutive_accumulates() {
        Warrior w = fresh();
        w.recordWarParticipation(100);
        assertThat(w.fatiguePctForCycle(101)).isEqualTo(10); // entra no 101 cansado de ter lutado o 100
        w.recordWarParticipation(101);
        assertThat(w.fatiguePctForCycle(102)).isEqualTo(20);
        w.recordWarParticipation(102);
        assertThat(w.fatiguePctForCycle(103)).isEqualTo(30);
    }

    @Test
    @DisplayName("Descansar 1 ciclo zera o cansaço")
    void rest_resets() {
        Warrior w = fresh();
        w.recordWarParticipation(100);
        w.recordWarParticipation(101);
        assertThat(w.fatiguePctForCycle(102)).isEqualTo(20);
        // pula o ciclo 102 (não escalado) → no 103 está fresco de novo
        assertThat(w.fatiguePctForCycle(103)).isZero();
        // lutar o 103 recomeça a contagem (-10% indo pro 104)
        w.recordWarParticipation(103);
        assertThat(w.fatiguePctForCycle(104)).isEqualTo(10);
    }

    @Test
    @DisplayName("Cansaço tem teto de -50% (5 stacks)")
    void capsAt50() {
        Warrior w = fresh();
        for (long c = 100; c < 110; c++) w.recordWarParticipation(c); // 10 ciclos seguidos
        assertThat(w.getWarFatigueStacks()).isEqualTo(5);
        assertThat(w.fatiguePctForCycle(110)).isEqualTo(50);
    }

    @Test
    @DisplayName("currentFatiguePct (display) reflete a próxima batalha")
    void displayFatigue() {
        Warrior w = fresh();
        w.recordWarParticipation(100);              // lutou o ciclo atual (100)
        assertThat(w.currentFatiguePct(100)).isEqualTo(10); // próxima batalha (101) → -10%
        assertThat(w.currentFatiguePct(101)).isZero();      // já no 101 sem ter lutado → fresco
    }
}
