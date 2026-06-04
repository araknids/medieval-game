package com.medieval.game.enums;

// Rewards in BRONZE (100 bronze = 1 silver, 100 silver = 1 gold)
public enum QuestType {

    PATROL  ("Patrol",          5,  100,   50, 10),
    DUNGEON ("Dungeon",        10,  250,  150, 20),
    RAID    ("Raid",            20,  500,  300, 35),
    BOSS_HUNT("Boss Hunt",      30, 1000,  750, 50);

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
