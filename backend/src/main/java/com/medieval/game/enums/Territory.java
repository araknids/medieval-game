package com.medieval.game.enums;

public enum Territory {

    FORTALEZA_MALDITA(
        "Fortaleza Maldita",
        "Uma fortaleza amaldiçoada por um lich ancião. Seus corredores guardam poder e maldição.",
        "Cavaleiro Amaldiçoado",
        1.2,   // npc def multiplier (high defense)
        1.0,   // npc atk multiplier
        1.0,   // npc hp multiplier
        10     // extra xp bonus % for quests
    ),

    MINAS_DE_FERRO_NEGRO(
        "Minas de Ferro Negro",
        "Minas ancestrais repletas de metal raro e perigo. Poucos que entram saem de mãos vazias.",
        "Golem de Ferro",
        0.8,   // npc def multiplier
        0.7,   // npc atk multiplier
        2.0,   // npc hp multiplier (very high HP)
        20     // extra mining yield bonus %
    ),

    DESFILADEIRO_DO_OSSO(
        "Desfiladeiro do Osso",
        "Uma passagem estratégica entre reinos, pavimentada com os ossos dos que ousaram cruzá-la.",
        "Esqueleto Guerreiro",
        1.0,   // npc def multiplier
        1.0,   // npc atk multiplier
        0.8,   // npc hp multiplier (lower hp, higher evasion via BattleSimulator)
        20     // extra fishing yield bonus %
    );

    public final String displayName;
    public final String lore;
    public final String npcName;
    public final double npcDefMult;
    public final double npcAtkMult;
    public final double npcHpMult;
    public final int exclusiveBonus; // % — meaning depends on territory

    Territory(String displayName, String lore, String npcName,
              double npcDefMult, double npcAtkMult, double npcHpMult,
              int exclusiveBonus) {
        this.displayName   = displayName;
        this.lore          = lore;
        this.npcName       = npcName;
        this.npcDefMult    = npcDefMult;
        this.npcAtkMult    = npcAtkMult;
        this.npcHpMult     = npcHpMult;
        this.exclusiveBonus = exclusiveBonus;
    }
}
