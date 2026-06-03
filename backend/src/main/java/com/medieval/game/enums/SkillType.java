package com.medieval.game.enums;

public enum SkillType {
    FISHING ("Pesca",     "🎣"),
    MINING  ("Mineração", "⛏"),
    GARIMPO ("Garimpo",   "🔎"),   // coleta de fragmentos de joia (Reinos V2 — Grutas de Cristal)
    SMITHING("Forja",     "🔨");

    public final String displayName;
    public final String icon;

    SkillType(String displayName, String icon) {
        this.displayName = displayName;
        this.icon        = icon;
    }
}
