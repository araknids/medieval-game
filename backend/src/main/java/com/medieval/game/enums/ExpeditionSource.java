package com.medieval.game.enums;

/** [INCURSAO] De onde uma Incursão (Delve) nasce: tela de Reino (gear) ou de Zona/coleta (recursos). */
public enum ExpeditionSource {
    KINGDOM,  // [VARREDURA] NÃO é write-dead: deserializado do StartRequest (Incursão da tela de Reino)
    ZONE
}
