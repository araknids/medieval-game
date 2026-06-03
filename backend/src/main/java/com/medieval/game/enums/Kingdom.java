package com.medieval.game.enums;

public enum Kingdom {

    FISHING(
        "Desfiladeiro do Osso",
        "🎣",
        Territory.DESFILADEIRO_DO_OSSO,
        SkillType.FISHING,
        "A kingdom of tides and bounty. Master fishermen and sea hunters rule these waters."
    ),
    MINING(
        "Minas de Ferro Negro",
        "⛏",
        Territory.MINAS_DE_FERRO_NEGRO,
        SkillType.MINING,
        "Deep tunnels rich in rare minerals. Those who control the ores control the forge."
    ),
    COMBAT(
        "Fortaleza Maldita",
        "⚔",
        Territory.FORTALEZA_MALDITA,
        null,
        "An ancient fortress where warriors train and battle for glory. No mining, no fishing — only war."
    ),
    // Reinos V2 — reino de coleta SEM guerra de guild (territory = null). [PLANO_REINOS_V2]
    GRUTAS_DE_CRISTAL(
        "Grutas de Cristal",
        "🔎",
        null,                 // não é território de guild-war
        SkillType.GARIMPO,
        "Cavernas cintilantes onde garimpeiros escavam fragmentos de joias raras."
    );

    public final String displayName;
    public final String icon;
    public final Territory territory;
    public final SkillType primarySkill; // null for COMBAT
    public final String lore;

    Kingdom(String displayName, String icon, Territory territory,
            SkillType primarySkill, String lore) {
        this.displayName  = displayName;
        this.icon         = icon;
        this.territory    = territory;
        this.primarySkill = primarySkill;
        this.lore         = lore;
    }

    /** Returns the Kingdom linked to the given Territory, or null. */
    public static Kingdom ofTerritory(Territory t) {
        for (Kingdom k : values()) {
            if (k.territory == t) return k;
        }
        return null;
    }
}
