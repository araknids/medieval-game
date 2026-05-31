package com.medieval.game.enums;

public enum QuestType {

    PATROL  ("Patrulha",         5,  100,   50, 10),
    DUNGEON ("Masmorra",        10,  300,  150, 20),
    RAID    ("Raid",            20,  600,  300, 35),
    BOSS_HUNT("Caça ao Chefe", 30, 1500,  750, 50);

    public final String displayName;
    public final int durationMinutes;
    public final long goldReward;
    public final long expReward;
    public final int staminaCost;

    QuestType(String displayName, int durationMinutes, long goldReward, long expReward, int staminaCost) {
        this.displayName     = displayName;
        this.durationMinutes = durationMinutes;
        this.goldReward      = goldReward;
        this.expReward       = expReward;
        this.staminaCost     = staminaCost;
    }
}
