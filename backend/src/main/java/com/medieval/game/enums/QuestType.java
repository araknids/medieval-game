package com.medieval.game.enums;

// Rewards in BRONZE (100 bronze = 1 silver, 100 silver = 1 gold)
// Combate V2 — nichos por tipo: CURTAS são reis de BRONZE/estamina (renda ativa);
// LONGAS são reis de XP/estamina (progressão). Nenhum tipo domina os dois eixos. [COMBATE_V2]
//                              (name,            min, bronze,  xp, stam)
public enum QuestType {

    PATROL  ("Patrol",          5,  180,   40, 10),  // bronze/stam 18 · xp/stam 4
    DUNGEON ("Dungeon",        10,  320,  110, 20),  // bronze/stam 16 · xp/stam 5.5
    RAID    ("Raid",            20,  480,  320, 35),  // bronze/stam 13.7 · xp/stam 9.1
    BOSS_HUNT("Boss Hunt",      30,  600,  750, 50);  // bronze/stam 12 · xp/stam 15

    public final String displayName;
    public final int durationMinutes;
    public final long bronzeReward;  // reward in bronze
    public final long expReward;
    public final int staminaCost;

    QuestType(String displayName, int durationMinutes,
              long bronzeReward, long expReward, int staminaCost) {
        this.displayName     = displayName;
        this.durationMinutes = durationMinutes;
        this.bronzeReward    = bronzeReward;
        this.expReward       = expReward;
        this.staminaCost     = staminaCost;
    }

    /** @deprecated use bronzeReward */
    public long getGoldReward() { return bronzeReward; }
}
