package com.medieval.game.enums;

public enum KingdomQuestType {

    // 6 quests per realm (Reinos V2). The UI shows 2 at a time, rotating every 6h (see KingdomService).
    // Increasing difficulty tiers; monsterChance scales with it (chance of combat on collect).
    // params: (kingdom, displayName, durationMinutes, bronzeReward, expReward, staminaCost, dropChance, monsterChance)

    // ── Fishing Kingdom (Bone Gorge) ─────────────────────────────────────────
    PATROL_COAST     (Kingdom.FISHING, "Patrol the Coast",        5,  100,  50, 5, 10, 15),
    EXPLORE_REEFS    (Kingdom.FISHING, "Explore the Reefs",      10,  250, 150, 7, 20, 30),
    SALVAGE_THE_WRECK(Kingdom.FISHING, "Salvage the Wreck",      15,  400, 250, 9, 30, 45),
    CLEAR_PIRATE_COVE(Kingdom.FISHING, "Clear the Pirate Cove",  20,  600, 400, 11, 40, 60),
    DEEP_SEA_RAID    (Kingdom.FISHING, "Deep Sea Raid",          25,  800, 575, 13, 50, 75),
    HUNT_SEA_MONSTER (Kingdom.FISHING, "Hunt the Sea Monster",   30, 1000, 750, 15, 60, 90),

    // ── Mining Kingdom (Black Iron Mines) ────────────────────────────────────
    ESCORT_MINERS     (Kingdom.MINING, "Escort the Miners",       5,  100,  50, 5, 10, 15),
    CLEAR_CAVES       (Kingdom.MINING, "Clear the Caves",        10,  250, 150, 7, 20, 30),
    SHORE_UP_TUNNELS  (Kingdom.MINING, "Shore Up the Tunnels",   15,  400, 250, 9, 30, 45),
    RETRIEVE_LOST_ORE (Kingdom.MINING, "Retrieve the Lost Ore",  20,  600, 400, 11, 40, 60),
    PURGE_INFESTATION (Kingdom.MINING, "Purge the Infestation",  25,  800, 575, 13, 50, 75),
    DEFEAT_CAVE_BEAST (Kingdom.MINING, "Defeat the Cave Beast",  30, 1000, 750, 15, 60, 90),

    // ── Combat Kingdom (Cursed Fortress) ─────────────────────────────────────
    DEFEND_WALLS     (Kingdom.COMBAT, "Defend the Walls",         5,  100,  50, 5, 10, 15),
    CLEAR_DUNGEON    (Kingdom.COMBAT, "Clear the Dungeon",       10,  250, 150, 7, 20, 30),
    PATROL_RAMPARTS  (Kingdom.COMBAT, "Patrol the Ramparts",     15,  400, 250, 9, 30, 45),
    RAID_ENCAMPMENT  (Kingdom.COMBAT, "Raid the Encampment",     20,  600, 400, 11, 40, 60),
    BREACH_THE_KEEP  (Kingdom.COMBAT, "Breach the Keep",         25,  800, 575, 13, 50, 75),
    HUNT_WARLORD     (Kingdom.COMBAT, "Hunt the Warlord",        30, 1000, 750, 15, 60, 90),

    // ── Crystal Grottoes (Prospecting) ───────────────────────────────────────
    GUARD_CRYSTAL_VEINS   (Kingdom.GRUTAS_DE_CRISTAL, "Guard the Crystal Veins",     5,  100,  50, 5, 10, 15),
    MAP_THE_GROTTO        (Kingdom.GRUTAS_DE_CRISTAL, "Map the Grotto",             10,  250, 150, 7, 20, 30),
    EXTRACT_GEODES        (Kingdom.GRUTAS_DE_CRISTAL, "Extract the Geodes",         15,  400, 250, 9, 30, 45),
    SEAL_THE_FISSURE      (Kingdom.GRUTAS_DE_CRISTAL, "Seal the Fissure",           20,  600, 400, 11, 40, 60),
    CLEANSE_CRYSTAL_HORROR(Kingdom.GRUTAS_DE_CRISTAL, "Cleanse the Crystal Horror", 25,  800, 575, 13, 50, 75),
    SLAY_CRYSTAL_BEAST    (Kingdom.GRUTAS_DE_CRISTAL, "Slay the Crystal Beast",     30, 1000, 750, 15, 60, 90),

    // ── Blessed Sea (sacred waters) ──────────────────────────────────────────
    CLEANSE_THE_TIDES (Kingdom.MAR_ABENCOADO, "Cleanse the Tides",      5,  100,  50, 5, 10, 15),
    BLESS_THE_SHALLOWS(Kingdom.MAR_ABENCOADO, "Bless the Shallows",    10,  250, 150, 7, 20, 30),
    ESCORT_PILGRIMS   (Kingdom.MAR_ABENCOADO, "Escort the Pilgrims",   15,  400, 250, 9, 30, 45),
    PURIFY_THE_REEF   (Kingdom.MAR_ABENCOADO, "Purify the Reef",       20,  600, 400, 11, 40, 60),
    BANISH_THE_DROWNED(Kingdom.MAR_ABENCOADO, "Banish the Drowned",    25,  800, 575, 13, 50, 75),
    GUARD_SACRED_REEF (Kingdom.MAR_ABENCOADO, "Guard the Sacred Reef", 30, 1000, 750, 15, 60, 90),

    // ── Quest RARA da Luna: aparece às vezes em qualquer reino; sem loto, só a chance de pet. [PETS] ──
    // (kingdom nominal FISHING; o start bypassa o check de reino; recompensas/estamina = 0)
    RESCUE_STRAY_DOG  (Kingdom.FISHING, "A Stray in Need", 5, 0, 0, 0, 0, 0);

    public final Kingdom  kingdom;
    public final String   displayName;
    public final int      durationMinutes;
    public final long     bronzeReward;
    public final long     expReward;
    public final int      staminaCost;
    public final int      dropChance;    // %
    public final int      monsterChance; // % chance de encontro de monstro na coleta (escala com a dificuldade)

    KingdomQuestType(Kingdom kingdom, String displayName, int durationMinutes,
                     long bronzeReward, long expReward, int staminaCost, int dropChance, int monsterChance) {
        this.kingdom         = kingdom;
        this.displayName     = displayName;
        this.durationMinutes = durationMinutes;
        this.bronzeReward    = bronzeReward;
        this.expReward       = expReward;
        this.staminaCost     = staminaCost;
        this.dropChance      = dropChance;
        this.monsterChance   = monsterChance;
    }
}
