package com.medieval.game.enums;

public enum Attribute {
    STRENGTH    ("Strength (STR)",     "⚔",  "+1 ATK/pt · Attack Bonus floor(STR/20) in d20 · cap 60"),
    DEXTERITY   ("Dexterity (DEX)",    "🛡",  "+1 AC/pt · AC = 10 + DEX · harder to hit · cap 40"),
    CONSTITUTION("Constitution (CON)", "❤",  "+8 HP/pt · no cap · reason to level past 95"),
    LUCK        ("Luck (LUK)",         "🍀", "+1% drop · widens crit window · Fortune Save · cap 50"),
    INTELLECT   ("Intellect (INT)",    "📚", "+0.5% Smithing · -0.2% training cost · +0.3% gathering yield · cap 40");

    public final String displayName;
    public final String icon;
    public final String effect;

    Attribute(String displayName, String icon, String effect) {
        this.displayName = displayName;
        this.icon        = icon;
        this.effect      = effect;
    }
}
