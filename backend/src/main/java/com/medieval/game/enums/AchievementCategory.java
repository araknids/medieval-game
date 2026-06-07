package com.medieval.game.enums;

/** Categorias de achievement/título. [TITULOS] */
public enum AchievementCategory {
    CLASS  ("Class"),
    LEVEL  ("Level"),
    ARENA  ("Arena"),
    TOWER  ("Tower"),
    WEALTH ("Wealth"),
    GUILD  ("Guild"),
    STORY  ("Story"); // [TITULOS] marcos de história (ex.: a escolha no topo da Torre) — costumam ser ocultos

    public final String displayName;

    AchievementCategory(String displayName) {
        this.displayName = displayName;
    }
}
