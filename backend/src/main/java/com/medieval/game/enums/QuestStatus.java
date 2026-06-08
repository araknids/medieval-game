package com.medieval.game.enums;

public enum QuestStatus {
    IN_PROGRESS,
    READY_TO_COLLECT,
    LUNA_PENDING,   // [LUNA_INTERRUPT] a Luna interrompeu a missão; aguardando a escolha ajudar/terminar
    COLLECTED,
    ABANDONED
}
