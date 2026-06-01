package com.medieval.game.enums;

public enum Zone {
    SAFE      ("Zona Segura",      1,  1.0, 0,   "Área protegida. Sem PvP, recursos básicos."),
    PVP       ("Zona PvP",        10,  1.5, 20,  "Recursos melhores. Risco de ataque. Nível 10+."),
    HIGH_RISK ("Zona de Alto Risco",20, 2.5, 40, "Recursos raros. Alto risco. Itens equipados em jogo. Nível 20+.");

    public final String displayName;
    public final int    minLevel;           // nível mínimo do guerreiro
    public final double multiplier;         // multiplicador de recursos e XP
    public final int    encounterChancePerHour; // % de chance de encontro PvP por hora
    public final String description;

    Zone(String displayName, int minLevel, double multiplier, int encounterChancePerHour, String description) {
        this.displayName             = displayName;
        this.minLevel                = minLevel;
        this.multiplier              = multiplier;
        this.encounterChancePerHour  = encounterChancePerHour;
        this.description             = description;
    }
}
