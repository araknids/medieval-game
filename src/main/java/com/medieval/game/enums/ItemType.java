package com.medieval.game.enums;

public enum ItemType {
    HELMET   ("Capacete"),
    ARMOR    ("Armadura"),
    WEAPON   ("Espada"),
    SHIELD   ("Escudo"),
    BOOTS    ("Bota"),
    GLOVES   ("Luva"),
    RING     ("Anel"),
    NECKLACE ("Colar"),
    SHOULDER ("Ombreira"),
    PANTS    ("Calça");

    public final String displayName;

    ItemType(String displayName) {
        this.displayName = displayName;
    }
}
