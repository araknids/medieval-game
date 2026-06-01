package com.medieval.game.enums;

public enum Zone {
    //                         nome                   lv  mult  pvp%  npc%  descrição
    SAFE      ("Zona Segura",       1, 1.0,  0, 15, "Área protegida. Sem PvP, mas monstros selvagens aparecem."),
    PVP       ("Zona PvP",         10, 1.5, 20, 25, "Recursos melhores. Risco de ataque de players e criaturas. Nível 10+."),
    HIGH_RISK ("Zona de Alto Risco",20, 2.5, 40, 35, "Recursos raros. Alto risco. Itens equipados em jogo. Nível 20+.");

    public final String displayName;
    public final int    minLevel;
    public final double multiplier;
    public final int    encounterChancePerHour;    // % PvP (player vs player)
    public final int    npcEncounterChancePerHour; // % NPC (monstro/bandido)
    public final String description;

    Zone(String displayName, int minLevel, double multiplier,
         int encounterChancePerHour, int npcEncounterChancePerHour, String description) {
        this.displayName              = displayName;
        this.minLevel                 = minLevel;
        this.multiplier               = multiplier;
        this.encounterChancePerHour   = encounterChancePerHour;
        this.npcEncounterChancePerHour= npcEncounterChancePerHour;
        this.description              = description;
    }
}
