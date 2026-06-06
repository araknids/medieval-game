package com.medieval.game.enums;

/**
 * Pet equipável (slot próprio, igual à Montaria). Dá um bônus % de HP em combate. [PETS]
 * Extensível: novos pets entram aqui com seu próprio hpBonusPercent.
 */
public enum PetType {

    LUNA("Luna", "🐶", 10); // +10% HP — obtida pela quest rara da cachorra

    public final String displayName;
    public final String icon;
    public final int    hpBonusPercent;

    PetType(String displayName, String icon, int hpBonusPercent) {
        this.displayName    = displayName;
        this.icon           = icon;
        this.hpBonusPercent = hpBonusPercent;
    }
}
