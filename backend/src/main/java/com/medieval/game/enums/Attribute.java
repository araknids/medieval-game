package com.medieval.game.enums;

public enum Attribute {
    STRENGTH    ("Strength (STR)",     "⚔",  "+1 ATK per point — melee damage (sword/axe/mace)"),
    DEXTERITY   ("Dexterity (DEX)",    "🎯", "+1% hit chance per point — and bow damage for Archers"),
    CONSTITUTION("Constitution (CON)", "❤",  "Max HP — 8/pt up to 40, then 4/pt, then 2/pt (infinite, but diminishing)"),
    AGILITY     ("Agility (AGI)",      "💨", "Speed & evasion — extra strikes vs slower foes, dodges incoming hits"),
    LUCK        ("Luck (LUK)",         "🍀", "+1% drop · +1% crit per 2 points (cap 35%) · Fortune Save (negate crits)"),
    INTELLECT   ("Intellect (INT)",    "📚", "Reserved — no effect yet (a future Mage class will use it)");

    public final String displayName;
    public final String icon;
    public final String effect;

    Attribute(String displayName, String icon, String effect) {
        this.displayName = displayName;
        this.icon        = icon;
        this.effect      = effect;
    }
}
