package com.medieval.game.enums;

// Recompensas em BRONZE (100 bronze = 1 prata, 100 prata = 1 ouro)
public enum QuestType {

    PATROL  ("Patrulha",         5,  100,   50, 10),
    DUNGEON ("Masmorra",        10,  250,  150, 20),
    RAID    ("Raid",            20,  500,  300, 35),
    BOSS_HUNT("Caça ao Chefe", 30, 1000,  750, 50);

    public final String displayName;
    public final int durationMinutes;
    public final long bronzeReward;  // recompensa em bronze
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
