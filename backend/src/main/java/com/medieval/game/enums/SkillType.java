package com.medieval.game.enums;

public enum SkillType {
    FISHING ("Fishing",     "🎣"),
    MINING  ("Mining",      "⛏"),
    GARIMPO ("Prospecting", "🔎"),   // gem-fragment gathering (Reinos V2 — Crystal Grottoes)
    SMITHING("Smithing",    "🔨");

    public final String displayName;
    public final String icon;

    SkillType(String displayName, String icon) {
        this.displayName = displayName;
        this.icon        = icon;
    }
}
