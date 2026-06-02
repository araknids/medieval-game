package com.medieval.game.enums;

public enum KingdomQuestType {

    // ── Fishing Kingdom (Desfiladeiro do Osso) ───────────────────────────────
    PATROL_COAST    (Kingdom.FISHING, "Patrol the Coast",       5,  100,  50, 10, 10),
    EXPLORE_REEFS   (Kingdom.FISHING, "Explore the Reefs",     10,  250, 150, 20, 25),
    DEEP_SEA_RAID   (Kingdom.FISHING, "Deep Sea Raid",         20,  500, 300, 35, 40),
    HUNT_SEA_MONSTER(Kingdom.FISHING, "Hunt the Sea Monster",  30, 1000, 750, 50, 60),

    // ── Mining Kingdom (Minas de Ferro Negro) ────────────────────────────────
    ESCORT_MINERS   (Kingdom.MINING,  "Escort the Miners",      5,  100,  50, 10, 10),
    CLEAR_CAVES     (Kingdom.MINING,  "Clear Cave Monsters",   10,  250, 150, 20, 25),
    RETRIEVE_ORE    (Kingdom.MINING,  "Retrieve Rare Ore",     20,  500, 300, 35, 40),
    DEFEAT_CAVE_BEAST(Kingdom.MINING, "Defeat the Cave Beast", 30, 1000, 750, 50, 60),

    // ── Combat Kingdom (Fortaleza Maldita) ───────────────────────────────────
    DEFEND_WALLS    (Kingdom.COMBAT,  "Defend the Walls",       5,  100,  50, 10, 10),
    CLEAR_DUNGEON   (Kingdom.COMBAT,  "Clear the Dungeon",     10,  250, 150, 20, 25),
    RAID_ENCAMPMENT (Kingdom.COMBAT,  "Raid the Encampment",   20,  500, 300, 35, 40),
    HUNT_WARLORD    (Kingdom.COMBAT,  "Hunt the Warlord",      30, 1000, 750, 50, 60);

    public final Kingdom  kingdom;
    public final String   displayName;
    public final int      durationMinutes;
    public final long     bronzeReward;
    public final long     expReward;
    public final int      staminaCost;
    public final int      dropChance; // %

    KingdomQuestType(Kingdom kingdom, String displayName, int durationMinutes,
                     long bronzeReward, long expReward, int staminaCost, int dropChance) {
        this.kingdom         = kingdom;
        this.displayName     = displayName;
        this.durationMinutes = durationMinutes;
        this.bronzeReward    = bronzeReward;
        this.expReward       = expReward;
        this.staminaCost     = staminaCost;
        this.dropChance      = dropChance;
    }
}
