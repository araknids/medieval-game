package com.medieval.game.enums;

/**
 * Cooking recipes (Cooking System). Each meal is both the recipe (fish consumed) and the combat buff it
 * grants when eaten. Buffs feed combat via WarriorStatsService.combatStats, in the "Well Fed" slot
 * (separate from the 2 Temple slots). Cooking is instant. [COZINHA]
 *
 * Bone Gorge line (stamina fish) = offensive; Blessed Sea line (life fish) = defensive.
 * `evasionBonus` is added as flat AC/dodge points in the d20 system (10 + DEX + evasionBonus).
 */
public enum Meal {

    // ── Offensive (stamina fish) ──
    GRILLED_SKEWER  ("Grilled Fish Skewer", "🍤", ResourceType.SMALL_FISH,     2,  8,  0,  0, 0, 30),
    SALMON_FILLET   ("Salmon Fillet",       "🐟", ResourceType.SALMON,         2, 10,  5,  0, 0, 40),
    TUNA_BANQUET    ("Tuna Banquet",        "🍣", ResourceType.TUNA,           2, 12,  0, 20, 0, 45),
    SHARK_STEAK     ("Shark Steak",         "🦈", ResourceType.SHARK,          1, 15, 10,  0, 0, 45),
    LEGENDARY_PLATTER("Legendary Platter",  "🌟", ResourceType.LEGENDARY_FISH, 1, 18, 12, 40, 0, 60),

    // ── Defensive (life fish) ──
    CORAL_SOUP      ("Coral Soup",          "🥣", ResourceType.CORAL_FISH,   2,  0,  0,  30, 0, 30),
    ANGEL_BROTH     ("Angelic Broth",       "🐠", ResourceType.ANGEL_FISH,   2,  0,  0,  50, 5, 40),
    SPIRIT_STEW     ("Spirit Stew",         "🍲", ResourceType.SPIRIT_FISH,  1,  0,  8,  60, 5, 45),
    SACRED_FEAST    ("Sacred Feast",        "✨", ResourceType.SACRED_FISH,  1,  0, 12,  80, 0, 50),
    PHOENIX_ROAST   ("Phoenix Roast",       "🔥", ResourceType.PHOENIX_FISH, 1,  0, 15, 100, 8, 60);

    public final String       displayName;
    public final String       icon;
    public final ResourceType fishIngredient;
    public final int          fishQty;
    public final int          atkBonus;
    public final int          defBonus;
    public final int          hpBonus;
    public final int          evasionBonus;
    public final int          durationMinutes;

    Meal(String displayName, String icon, ResourceType fishIngredient, int fishQty,
         int atkBonus, int defBonus, int hpBonus, int evasionBonus, int durationMinutes) {
        this.displayName     = displayName;
        this.icon            = icon;
        this.fishIngredient  = fishIngredient;
        this.fishQty         = fishQty;
        this.atkBonus        = atkBonus;
        this.defBonus        = defBonus;
        this.hpBonus         = hpBonus;
        this.evasionBonus    = evasionBonus;
        this.durationMinutes = durationMinutes;
    }

    public String effectText() {
        StringBuilder sb = new StringBuilder();
        if (atkBonus > 0)     sb.append("+").append(atkBonus).append(" ATK ");
        if (defBonus > 0)     sb.append("+").append(defBonus).append(" DEF ");
        if (hpBonus > 0)      sb.append("+").append(hpBonus).append(" HP ");
        if (evasionBonus > 0) sb.append("+").append(evasionBonus).append(" evasion ");
        return sb.toString().trim();
    }
}
