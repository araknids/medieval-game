package com.medieval.game.enums;

/** [INCURSAO] Estado de uma Incursão (Delve). Espelha QuestStatus/ZoneActivityStatus. */
public enum ExpeditionStatus {
    IN_PROGRESS,   // aguardando a escolha do próximo nó
    NODE_PENDING,  // um nó abriu uma decisão interna (evento d20 / baú armadilhado / chefe fugir-encarar)
    COMPLETED,     // extraído (loot sacado)
    DEFEATED,      // KO no meio da run (bolsa não-travada perdida)
    ABANDONED      // desistiu numa parada segura (bolsa não-travada perdida, sem KO)
}
