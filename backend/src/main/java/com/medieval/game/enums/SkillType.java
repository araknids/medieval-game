package com.medieval.game.enums;

public enum SkillType {
    FISHING ("Pesca",     "🎣"),
    MINING  ("Mineração", "⛏"),
    SMITHING("Forja",     "🔨");

    public final String displayName;
    public final String icon;

    SkillType(String displayName, String icon) {
        this.displayName = displayName;
        this.icon        = icon;
    }
}
