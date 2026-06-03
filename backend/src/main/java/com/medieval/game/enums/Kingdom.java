package com.medieval.game.enums;

/**
 * Reino — conceito único do mundo (Reinos V2). Unifica o antigo Kingdom + Territory:
 * cada reino tem sua atividade (skill de coleta ou combate), seus dados de batalha
 * (NPC/mults, usados quando o reino é território de guild-war) e seu bônus exclusivo.
 *
 * Quais reinos são território de guild-war é decidido por config
 * (app.kingdoms.war-territories) — ver TerritoryService. [REINOS_V2 / BL-2]
 */
public enum Kingdom {

    FISHING(
        "Desfiladeiro do Osso", "🎣", SkillType.FISHING,
        "Esqueleto Guerreiro", 1.0, 1.0, 0.8, 20,   // npcDef, npcAtk, npcHp, exclusiveBonus (% pescado)
        "A kingdom of tides and bounty. Master fishermen and sea hunters rule these waters."
    ),
    MINING(
        "Minas de Ferro Negro", "⛏", SkillType.MINING,
        "Golem de Ferro", 0.8, 0.7, 2.0, 20,        // exclusiveBonus = % minério
        "Deep tunnels rich in rare minerals. Those who control the ores control the forge."
    ),
    COMBAT(
        "Fortaleza Maldita", "⚔", null,
        "Cavaleiro Amaldiçoado", 1.2, 1.0, 1.0, 10, // exclusiveBonus = % XP de quest
        "An ancient fortress where warriors train, battle for glory, and hunt the beasts prowling its walls. No mining, no fishing — only war."
    ),
    // ── Reinos V2 — não são guild-war por padrão (exclusiveBonus 0) ──
    GRUTAS_DE_CRISTAL(
        "Grutas de Cristal", "🔎", SkillType.GARIMPO,
        "Fera de Cristal", 1.0, 1.0, 1.0, 0,
        "Cavernas cintilantes onde garimpeiros escavam fragmentos de joias raras."
    ),
    MAR_ABENCOADO(
        "Mar Abençoado", "🐟", SkillType.FISHING,   // mesma skill, pool de peixe de VIDA
        "Servo das Marés", 1.0, 1.0, 1.0, 0,
        "Águas sagradas onde nadam peixes que restauram a vida de quem os consome."
    );

    public final String    displayName;
    public final String    icon;
    public final SkillType  primarySkill;   // null para reinos de combate
    public final String     npcName;
    public final double     npcDefMult;
    public final double     npcAtkMult;
    public final double     npcHpMult;
    public final int        exclusiveBonus; // % — significado depende do reino (pescado/minério/XP)
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
