package com.medieval.game.enums;

public enum ItemType {
    HELMET   ("Helmet"),
    ARMOR    ("Armor"),
    WEAPON   ("Weapon"),
    SHIELD   ("Shield"),
    BOOTS    ("Boots"),
    GLOVES   ("Gloves"),
    RING     ("Ring"),
    NECKLACE ("Necklace"),
    SHOULDER ("Shoulder"),
    PANTS    ("Pants");

    public final String displayName;

    ItemType(String displayName) {
        this.displayName = displayName;
    }
}
