package com.medieval.game.enums;

public enum BuffType {
    STRENGTH  ("Força",      "⚔", "+5 ATK",          30, 5, 0, 0,  0),
    AGILITY   ("Agilidade",  "🏃", "+5% evasão",       30, 0, 0, 0,  5),
    DEFENSE   ("Defesa",     "🛡", "+5 DEF",           30, 0, 5, 0,  0),
    VITALITY  ("Vitalidade", "❤", "+20 HP máximo",    30, 0, 0, 20, 0),
    LUCK      ("Sorte",      "🍀", "+5% drop chance",  50, 0, 0, 0,  0);

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
