package com.medieval.game.enums;

// goldPerHour agora representa bronze por hora
public enum WorkType {

    TAVERN_HELPER  ("Tavern Helper",      "Serves tables and washes dishes at the local tavern.",          15,  0,  3),
    STABLE_KEEPER  ("Stable Keeper",     "Feeds and cares for the city's horses.",                         20,  0,  4),
    GOODS_CARRIER  ("Goods Carrier",     "Transports goods through the city market.",                      30,  1,  6),
    SMITH_ASSISTANT("Smith's Assistant", "Assists the blacksmith in forging tools and armor.",              45,  2,  8),
    NOBLE_GUARD    ("Noble Guard",       "Protects the properties and routes of the local nobility.",       65,  3, 12),
    LOCAL_MERCENARY("Local Mercenary",   "Performs military services for wealthy merchants.",              100,  5, 18);

    public final String displayName;
    public final String description;
    public final int goldPerHour;   // bronze por hora
    public final int minWorkLevel;
    public final int xpPerHour;

    WorkType(String displayName, String description, int goldPerHour, int minWorkLevel, int xpPerHour) {
        this.displayName  = displayName;
        this.description  = description;
        this.goldPerHour  = goldPerHour;
        this.minWorkLevel = minWorkLevel;
        this.xpPerHour    = xpPerHour;
    }
}
