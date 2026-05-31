package com.medieval.game.enums;

public enum Attribute {
    STRENGTH    ("Força",         "⚔",  "+1 ATK por ponto"),
    DEXTERITY   ("Destreza",      "🏹", "+1% evasão por ponto"),
    CONSTITUTION("Constituição",  "🛡",  "+5 HP e +0,5 DEF por ponto"),
    LUCK        ("Sorte",         "🍀", "+1% chance de drop por ponto");

    public final String displayName;
    public final String icon;
    public final String effect;

    Attribute(String displayName, String icon, String effect) {
        this.displayName = displayName;
        this.icon        = icon;
        this.effect      = effect;
    }
}
