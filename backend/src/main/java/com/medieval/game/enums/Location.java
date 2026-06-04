package com.medieval.game.enums;

public enum Location {
    TAVERN  ("Tavern"),
    COMMERCE("Commerce"),
    ARENA   ("Arena");

    public final String displayName;

    Location(String displayName) {
        this.displayName = displayName;
    }
}
