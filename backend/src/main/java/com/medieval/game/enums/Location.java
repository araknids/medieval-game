package com.medieval.game.enums;

public enum Location {
    TAVERN  ("Taverna"),
    COMMERCE("Comércio"),
    ARENA   ("Arena");

    public final String displayName;

    Location(String displayName) {
        this.displayName = displayName;
    }
}
