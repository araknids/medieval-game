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

    // [QUESTS_LORE] descrições reescritas pra semear a verdade na entrelinha (docs/PLANO_QUESTS_LORE.md)
    FISHING(
        "Bone Gorge", "🎣", SkillType.FISHING,
        "Skeleton Warrior", 1.0, 1.0, 0.8, 20,   // npcDef, npcAtk, npcHp, exclusiveBonus (% fishing yield)
        "A drowned gorge where the tide gives more than any net should hold — and gives back more than the living. Fish here long enough and you learn not to look too closely at the catch."
    ),
    MINING(
        "Black Iron Mines", "⛏", SkillType.MINING,
        "Iron Golem", 0.8, 0.7, 2.0, 20,         // exclusiveBonus = % ore yield
        "The deeper the shaft, the richer the vein — and the warmer the stone. The old miners say the mountain has a pulse. The new ones learn not to mention it."
    ),
    COMBAT(
        "Cursed Fortress", "⚔", null,
        "Cursed Knight", 1.2, 1.0, 1.0, 10,      // exclusiveBonus = % quest XP
        "The old fortress in the Tower's shadow, where the corruption runs thickest. The soldiers sent to hold the line came back — but they came back wrong."
    ),
    // ── Reinos V2 — not guild-war by default (exclusiveBonus 0) ──
    GRUTAS_DE_CRISTAL(
        "Crystal Grottoes", "🔎", SkillType.GARIMPO,
        "Crystal Beast", 1.0, 1.0, 1.0, 0,
        "Caverns where gems grow like frost, beautiful past reason. Prospectors who linger too long stop digging — and start listening."
    ),
    MAR_ABENCOADO(
        "Blessed Sea", "🐟", SkillType.FISHING,   // same skill, LIFE fish pool
        "Tide Servant", 1.0, 1.0, 1.0, 0,
        "Sacred waters where the fish restore the life of those who eat them. No one asks how a dead-cold sea learned to heal. The drowned here do not rest — they reach for the deep."
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
