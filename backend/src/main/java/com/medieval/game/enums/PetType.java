package com.medieval.game.enums;

/**
 * Pet equipável (slot próprio, igual à Montaria). Dá bônus de combate. [PETS]
 * Cada pet pode dar % de HP e/ou AGI (DEX) plana. soulStoneCost=0 → não comprável (vem de quest).
 */
public enum PetType {

    LUNA  ("Luna",   "🐶", 10, 0,  0),  // +10% HP — vem da quest rara (não é comprada)
    SHADOW("Bandit Cat", "🐱",  0, 6, 10);  // +6 AGI (DEX) — comprado no mercado VIP (10 SoulStones)

    public final String displayName;
    public final String icon;
    public final int    hpBonusPercent; // % no HP final de combate
    public final int    dexBonus;       // AGI plana (AC = 10 + dex; entra no d20)
    public final int    soulStoneCost;  // 0 = não comprável (só por quest/grant)

    PetType(String displayName, String icon, int hpBonusPercent, int dexBonus, int soulStoneCost) {
        this.displayName    = displayName;
        this.icon           = icon;
        this.hpBonusPercent = hpBonusPercent;
        this.dexBonus       = dexBonus;
        this.soulStoneCost  = soulStoneCost;
    }
}
