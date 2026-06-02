package com.medieval.game.enums;

public enum BuffType {
    STRENGTH  ("Strength",  "⚔", "+5 ATK",        30, 5, 0, 0,  0),
    AGILITY   ("Agility",   "🏃", "+5% evasion",   30, 0, 0, 0,  5),
    DEFENSE   ("Defense",   "🛡", "+5 DEF",        30, 0, 5, 0,  0),
    VITALITY  ("Vitality",  "❤", "+20 max HP",    30, 0, 0, 20, 0),
    LUCK      ("Luck",      "🍀", "+5% drop chance", 50, 0, 0, 0,  0);

    public final String displayName;
    public final String icon;
    public final String effect;
    public final long   bronzeCost;
    public final int    atkBonus;
    public final int    defBonus;
    public final int    hpBonus;
    public final int    evasionBonus;

    BuffType(String displayName, String icon, String effect, long bronzeCost,
             int atkBonus, int defBonus, int hpBonus, int evasionBonus) {
        this.displayName = displayName;
        this.icon        = icon;
        this.effect      = effect;
        this.bronzeCost  = bronzeCost;
        this.atkBonus    = atkBonus;
        this.defBonus    = defBonus;
        this.hpBonus     = hpBonus;
        this.evasionBonus= evasionBonus;
    }
}
