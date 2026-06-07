package com.medieval.game.enums;

public enum Attribute {
    STRENGTH    ("Strength (STR)",     "⚔",  "+1 ATK per point — melee damage (sword/axe/mace)"),
    DEXTERITY   ("Dexterity (DEX)",    "🎯", "Accuracy (d20 + DEX/5) — and bow damage for Archers"),
    CONSTITUTION("Constitution (CON)", "❤",  "+8 max HP per point · no cap"),
    AGILITY     ("Agility (AGI)",      "💨", "Speed & evasion — extra strikes vs slower foes, dodges incoming hits"),
    LUCK        ("Luck (LUK)",         "🍀", "+1% drop · widens crit window · Fortune Save (negate crits)"),
    INTELLECT   ("Intellect (INT)",    "📚", "+0.5% Smithing · -0.2% training cost · +0.3% gathering yield (reserved for Mage)");

    public final String displayName;
    public final String icon;
    public final String effect;

    Attribute(String displayName, String icon, String effect) {
        this.displayName = displayName;
        this.icon        = icon;
        this.effect      = effect;
    }
}
