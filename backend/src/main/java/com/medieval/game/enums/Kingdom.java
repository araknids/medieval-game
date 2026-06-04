package com.medieval.game.enums;

/**
 * Realm — the single world concept (Reinos V2). Unifies the old Kingdom + Territory:
 * each realm has its activity (gathering skill or combat), its battle data (NPC/mults,
 * used when the realm is a guild-war territory) and its exclusive bonus.
 *
 * Which realms are guild-war territories is decided by config
 * (app.kingdoms.war-territories) — see TerritoryService. [REINOS_V2 / BL-2]
 */
public enum Kingdom {

    FISHING(
        "Bone Gorge", "🎣", SkillType.FISHING,
        "Skeleton Warrior", 1.0, 1.0, 0.8, 20,   // npcDef, npcAtk, npcHp, exclusiveBonus (% fishing yield)
        "A kingdom of tides and bounty. Master fishermen and sea hunters rule these waters."
    ),
    MINING(
        "Black Iron Mines", "⛏", SkillType.MINING,
        "Iron Golem", 0.8, 0.7, 2.0, 20,         // exclusiveBonus = % ore yield
        "Deep tunnels rich in rare minerals. Those who control the ores control the forge."
    ),
    COMBAT(
        "Cursed Fortress", "⚔", null,
        "Cursed Knight", 1.2, 1.0, 1.0, 10,      // exclusiveBonus = % quest XP
        "An ancient fortress where warriors train, battle for glory, and hunt the beasts prowling its walls. No mining, no fishing — only war."
    ),
    // ── Reinos V2 — not guild-war by default (exclusiveBonus 0) ──
    GRUTAS_DE_CRISTAL(
        "Crystal Grottoes", "🔎", SkillType.GARIMPO,
        "Crystal Beast", 1.0, 1.0, 1.0, 0,
        "Glittering caverns where prospectors dig out fragments of rare gems."
    ),
    MAR_ABENCOADO(
        "Blessed Sea", "🐟", SkillType.FISHING,   // same skill, LIFE fish pool
        "Tide Servant", 1.0, 1.0, 1.0, 0,
        "Sacred waters where fish swim that restore the life of those who eat them."
    );

    public final String    displayName;
    public final String    icon;
    public final SkillType  primarySkill;   // null for combat realms
    public final String     npcName;
    public final double     npcDefMult;
    public final double     npcAtkMult;
    public final double     npcHpMult;
    public final int        exclusiveBonus; // % — meaning depends on the realm (fishing/ore/XP)
    public final String     lore;

    Kingdom(String displayName, String icon, SkillType primarySkill,
            String npcName, double npcDefMult, double npcAtkMult, double npcHpMult,
            int exclusiveBonus, String lore) {
        this.displayName    = displayName;
        this.icon           = icon;
        this.primarySkill   = primarySkill;
        this.npcName        = npcName;
        this.npcDefMult     = npcDefMult;
        this.npcAtkMult     = npcAtkMult;
        this.npcHpMult      = npcHpMult;
        this.exclusiveBonus = exclusiveBonus;
        this.lore           = lore;
    }
}
