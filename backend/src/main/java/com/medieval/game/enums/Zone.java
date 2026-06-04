package com.medieval.game.enums;

public enum Zone {
    //                         name                   lv  mult  pvp%  npc%  description
    SAFE      ("Safe Zone",       1, 1.0,  0, 15, "Protected area. No PvP, but wild monsters appear."),
    PVP       ("PvP Zone",        10, 1.5, 20, 25, "Better resources. Risk of player and creature attacks. Level 10+."),
    HIGH_RISK ("High Risk Zone",  20, 2.5, 40, 35, "Rare resources. High risk. Equipped items at stake. Level 20+.");

    public final String displayName;
    public final int    minLevel;
    public final double multiplier;
    public final int    encounterChancePerHour;    // % PvP (player vs player)
    public final int    npcEncounterChancePerHour; // % NPC (monster/bandit)
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
