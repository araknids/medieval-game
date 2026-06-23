package com.medieval.game.enums;

public enum Location {
    TAVERN  ("Tavern"); // [VARREDURA] COMMERCE/ARENA removidos (write-dead; remap no SchemaMigrator)

    public final String displayName;

    Location(String displayName) {
        this.displayName = displayName;
    }
}
