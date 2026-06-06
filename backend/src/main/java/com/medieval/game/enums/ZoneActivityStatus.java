package com.medieval.game.enums;

public enum ZoneActivityStatus {
    IN_PROGRESS,
    BOSS_PENDING, // [ZONA_CHEFE] chefe errante apareceu no collect; aguardando o jogador fugir/encarar
    COMPLETED,
    DEFEATED,   // gatherer foi derrotado; hunter foi derrotado
    CANCELLED
}
