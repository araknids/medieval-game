package com.medieval.game.quest;

import com.medieval.game.enums.Attribute;

/**
 * Resultado de uma escolha numa quest interativa. Os valores de recompensa são MULTIPLICADORES da
 * reward base da quest (auto-escala por dificuldade). Ver docs/PLANO_QUESTS_INTERATIVAS.md.
 */
public sealed interface QuestOutcome
        permits QuestOutcome.Peaceful, QuestOutcome.Fight, QuestOutcome.Check {

    /** Resolve em paz (sem combate) e paga a recompensa. */
    record Peaceful(double bronzeMult, double xpMult, int dropChance, String narrative)
            implements QuestOutcome {}

    /** Dispara uma luta contra o monstro da quest. Vence → recompensa; perde → KO, sem recompensa. */
    record Fight(double bronzeMult, double xpMult, int dropChance,
                 String winNarrative, String loseNarrative) implements QuestOutcome {}

    /** Teste de atributo d20 (1d20 + floor(attr/4) vs DC). Ramifica pra onSuccess/onFail. */
    record Check(Attribute attr, int dc, QuestOutcome onSuccess, QuestOutcome onFail)
            implements QuestOutcome {}
}
