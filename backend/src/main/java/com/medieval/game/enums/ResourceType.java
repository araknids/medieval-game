package com.medieval.game.enums;

public enum ResourceType {
    // ── STAMINA fish (Calm Waters realm / FISHING) ──
    SMALL_FISH      ("Small Fish",          ResourceCategory.FISH,     1),
    SALMON          ("Salmon",              ResourceCategory.FISH,    20),
    TUNA            ("Tuna",                ResourceCategory.FISH,    40),
    SHARK           ("Shark",               ResourceCategory.FISH,    60),
    LEGENDARY_FISH  ("Legendary Fish",      ResourceCategory.FISH,    80),

    // ── LIFE/HP fish (Blessed Sea realm) ── [REINOS_V2]
    CORAL_FISH      ("Coral Fish",          ResourceCategory.FISH,     1),
    ANGEL_FISH      ("Angelfish",           ResourceCategory.FISH,    20),
    SPIRIT_FISH     ("Spirit Fish",         ResourceCategory.FISH,    40),
    SACRED_FISH     ("Sacred Fish",         ResourceCategory.FISH,    60),
    PHOENIX_FISH    ("Phoenix Fish",        ResourceCategory.FISH,    80),

    // ── Ores ──
    COPPER_ORE      ("Copper Ore",          ResourceCategory.ORE,      1),
    IRON_ORE        ("Iron Ore",            ResourceCategory.ORE,     20),
    SILVER_ORE      ("Silver Ore",          ResourceCategory.ORE,     40),
    GOLD_ORE        ("Gold Ore",            ResourceCategory.ORE,     60),
    MITHRIL_ORE     ("Mithril Ore",         ResourceCategory.ORE,     80),

    // ── Gem fragments ──
    RUBY_FRAGMENT      ("Ruby Fragment",     ResourceCategory.FRAGMENT, 20),
    SAPPHIRE_FRAGMENT  ("Sapphire Fragment", ResourceCategory.FRAGMENT, 40),
    EMERALD_FRAGMENT   ("Emerald Fragment",  ResourceCategory.FRAGMENT, 60),
    DIAMOND_FRAGMENT   ("Diamond Fragment",  ResourceCategory.FRAGMENT, 80),
    AMETHYST_FRAGMENT  ("Amethyst Fragment", ResourceCategory.FRAGMENT,  1),

    // ── Bars ──
    COPPER_BAR      ("Copper Bar",          ResourceCategory.BAR,      1),
    IRON_BAR        ("Iron Bar",            ResourceCategory.BAR,     20),
    SILVER_BAR      ("Silver Bar",          ResourceCategory.BAR,     40),
    GOLD_BAR        ("Gold Bar",            ResourceCategory.BAR,     60),
    MITHRIL_BAR     ("Mithril Bar",         ResourceCategory.BAR,     80),

    // ── Gems ──
    RUBY      ("Ruby",      ResourceCategory.GEM, 1),
    SAPPHIRE  ("Sapphire",  ResourceCategory.GEM, 1),
    EMERALD   ("Emerald",   ResourceCategory.GEM, 1),
    DIAMOND   ("Diamond",   ResourceCategory.GEM, 1),
    AMETHYST  ("Amethyst",  ResourceCategory.GEM, 1),

    // ── Materials ──
    LEATHER       ("Leather",        ResourceCategory.MATERIAL, 1),
    MONSTER_CORE  ("Monster Core",   ResourceCategory.MATERIAL, 1),   // drop from the Beast Den [REINOS_V2]
    BEAST_HIDE    ("Beast Hide",     ResourceCategory.MATERIAL, 1),   // rare drop from the Beast Den

    // ── Essências elementais (drop das áreas de elemento; material do encantamento no Templo) [ELEMENTOS] ──
    FIRE_ESSENCE  ("Fire Essence",   ResourceCategory.ESSENCE, 1),
    WATER_ESSENCE ("Water Essence",  ResourceCategory.ESSENCE, 1),
    EARTH_ESSENCE ("Earth Essence",  ResourceCategory.ESSENCE, 1),
    AIR_ESSENCE   ("Air Essence",    ResourceCategory.ESSENCE, 1);

    public final String           displayName;
    public final ResourceCategory category;
    public final int              levelRequired;

    ResourceType(String displayName, ResourceCategory category, int levelRequired) {
        this.displayName   = displayName;
        this.category      = category;
        this.levelRequired = levelRequired;
    }

    public enum ResourceCategory { FISH, ORE, FRAGMENT, BAR, GEM, MATERIAL, ESSENCE }
}
