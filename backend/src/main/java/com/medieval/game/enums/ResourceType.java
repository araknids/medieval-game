package com.medieval.game.enums;

public enum ResourceType {
    // ── Peixes de ESTAMINA (Reino Águas Calmas / FISHING) ──
    SMALL_FISH      ("Peixe Pequeno",       ResourceCategory.FISH,     1),
    SALMON          ("Salmão",              ResourceCategory.FISH,    20),
    TUNA            ("Atum",                ResourceCategory.FISH,    40),
    SHARK           ("Tubarão",             ResourceCategory.FISH,    60),
    LEGENDARY_FISH  ("Peixe Lendário",      ResourceCategory.FISH,    80),

    // ── Peixes de VIDA/HP (Reino Mar Abençoado) ── [REINOS_V2]
    CORAL_FISH      ("Peixe-Coral",         ResourceCategory.FISH,     1),
    ANGEL_FISH      ("Peixe-Anjo",          ResourceCategory.FISH,    20),
    SPIRIT_FISH     ("Peixe-Espírito",      ResourceCategory.FISH,    40),
    SACRED_FISH     ("Peixe-Sagrado",       ResourceCategory.FISH,    60),
    PHOENIX_FISH    ("Peixe-Fênix",         ResourceCategory.FISH,    80),

    // ── Minérios ──
    COPPER_ORE      ("Minério de Cobre",    ResourceCategory.ORE,      1),
    IRON_ORE        ("Minério de Ferro",    ResourceCategory.ORE,     20),
    SILVER_ORE      ("Minério de Prata",    ResourceCategory.ORE,     40),
    GOLD_ORE        ("Minério de Ouro",     ResourceCategory.ORE,     60),
    MITHRIL_ORE     ("Minério de Mithril",  ResourceCategory.ORE,     80),

    // ── Fragmentos de joias ──
    RUBY_FRAGMENT      ("Fragmento de Rubi",     ResourceCategory.FRAGMENT, 20),
    SAPPHIRE_FRAGMENT  ("Fragmento de Safira",   ResourceCategory.FRAGMENT, 40),
    EMERALD_FRAGMENT   ("Fragmento de Esmeralda",ResourceCategory.FRAGMENT, 60),
    DIAMOND_FRAGMENT   ("Fragmento de Diamante", ResourceCategory.FRAGMENT, 80),
    AMETHYST_FRAGMENT  ("Fragmento de Ametista", ResourceCategory.FRAGMENT,  1),

    // ── Barras ──
    COPPER_BAR      ("Barra de Cobre",      ResourceCategory.BAR,      1),
    IRON_BAR        ("Barra de Ferro",      ResourceCategory.BAR,     20),
    SILVER_BAR      ("Barra de Prata",      ResourceCategory.BAR,     40),
    GOLD_BAR        ("Barra de Ouro",       ResourceCategory.BAR,     60),
    MITHRIL_BAR     ("Barra de Mithril",    ResourceCategory.BAR,     80),

    // ── Joias ──
    RUBY      ("Rubi",     ResourceCategory.GEM, 1),
    SAPPHIRE  ("Safira",   ResourceCategory.GEM, 1),
    EMERALD   ("Esmeralda",ResourceCategory.GEM, 1),
    DIAMOND   ("Diamante", ResourceCategory.GEM, 1),
    AMETHYST  ("Ametista", ResourceCategory.GEM, 1),

    // ── Materiais ──
    LEATHER       ("Couro",            ResourceCategory.MATERIAL, 1),
    MONSTER_CORE  ("Núcleo de Fera",   ResourceCategory.MATERIAL, 1),   // drop do Covil das Feras [REINOS_V2]
    BEAST_HIDE    ("Pele de Fera",     ResourceCategory.MATERIAL, 1);   // drop raro do Covil das Feras

    public final String           displayName;
    public final ResourceCategory category;
    public final int              levelRequired;

    ResourceType(String displayName, ResourceCategory category, int levelRequired) {
        this.displayName   = displayName;
        this.category      = category;
        this.levelRequired = levelRequired;
    }

    public enum ResourceCategory { FISH, ORE, FRAGMENT, BAR, GEM, MATERIAL }
}
