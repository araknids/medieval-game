package com.medieval.game.enums;

public enum Zone {
    //                         name                   lv  mult  pvp%  npc%  description
    SAFE      ("Safe Zone",       1, 1.0,  0, 20, "Protected area. No PvP, but wild monsters appear."),
    PVP       ("PvP Zone",        10, 1.5, 20, 25, "Better resources. On loss: 50% bag resources + 10% bronze. Gear & XP are safe. Level 10+."),
    HIGH_RISK ("High Risk Zone",  20, 2.5, 40, 35, "Rare resources. On loss: resources + 15% bronze + an item + XP. Items get LOCKED. Level 20+.");

    public final String displayName;
    public final int    minLevel;
    public final double multiplier;
    // [SEM_TIMER] Chance de encontro por AÇÃO de farm (1 run = 1 rolagem). NÃO é "por hora":
    // o jogo é instantâneo, então a duração não entra na conta — cada farm rola isto uma vez.
    public final int    pvpEncounterChance; // % PvP (player vs player) por farm
    public final int    npcEncounterChance; // % NPC (monster/bandit) por farm
    public final String description;

    Zone(String displayName, int minLevel, double multiplier,
         int pvpEncounterChance, int npcEncounterChance, String description) {
        this.displayName        = displayName;
        this.minLevel           = minLevel;
        this.multiplier         = multiplier;
        this.pvpEncounterChance = pvpEncounterChance;
        this.npcEncounterChance = npcEncounterChance;
        this.description        = description;
    }
}
