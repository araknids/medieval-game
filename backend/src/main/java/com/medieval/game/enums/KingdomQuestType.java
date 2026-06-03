package com.medieval.game.enums;

public enum KingdomQuestType {

    // 2 quests por reino (Reinos V2): uma inicial (curta/barata) e uma avançada (longa/rica).

    // ── Fishing Kingdom (Desfiladeiro do Osso) ───────────────────────────────
    PATROL_COAST    (Kingdom.FISHING, "Patrol the Coast",       5,  100,  50, 10, 10),
    HUNT_SEA_MONSTER(Kingdom.FISHING, "Hunt the Sea Monster",  30, 1000, 750, 50, 60),

    // ── Mining Kingdom (Minas de Ferro Negro) ────────────────────────────────
    ESCORT_MINERS   (Kingdom.MINING,  "Escort the Miners",      5,  100,  50, 10, 10),
    DEFEAT_CAVE_BEAST(Kingdom.MINING, "Defeat the Cave Beast", 30, 1000, 750, 50, 60),

    // ── Combat Kingdom (Fortaleza Maldita) ───────────────────────────────────
    DEFEND_WALLS    (Kingdom.COMBAT,  "Defend the Walls",       5,  100,  50, 10, 10),
    HUNT_WARLORD    (Kingdom.COMBAT,  "Hunt the Warlord",      30, 1000, 750, 50, 60),

    // ── Grutas de Cristal (Garimpo) ──────────────────────────────────────────
    GUARD_CRYSTAL_VEINS(Kingdom.GRUTAS_DE_CRISTAL, "Guard the Crystal Veins",  5,  100,  50, 10, 10),
    SLAY_CRYSTAL_BEAST (Kingdom.GRUTAS_DE_CRISTAL, "Slay the Crystal Beast",  30, 1000, 750, 50, 60),

    // ── Mar Abençoado (águas sagradas) ───────────────────────────────────────
    CLEANSE_THE_TIDES(Kingdom.MAR_ABENCOADO, "Cleanse the Tides",     5,  100,  50, 10, 10),
    GUARD_SACRED_REEF(Kingdom.MAR_ABENCOADO, "Guard the Sacred Reef", 30, 1000, 750, 50, 60);

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
