package com.medieval.game.enums;

/** Categorias de achievement/título. [TITULOS] */
public enum AchievementCategory {
    CLASS  ("Class"),
    LEVEL  ("Level"),
    ARENA  ("Arena"),
    TOWER  ("Tower"),
    WEALTH ("Wealth"),
    GUILD  ("Guild");

    public final String displayName;

    AchievementCategory(String displayName) {
        this.displayName = displayName;
    }
}
