package com.medieval.game.enums;

/**
 * Receitas de cozinha (Sistema de Cozinha). Cada refeição é, ao mesmo tempo, a receita (peixe consumido)
 * e o buff de combate que concede ao ser comida. Buffs entram no combate via WarriorStatsService.combatStats,
 * no slot "Bem Alimentado" (separado dos 2 slots do Templo). Cozinhar é instantâneo. [COZINHA]
 *
 * Linha do Desfiladeiro (peixe de estamina) = ofensivas; linha do Mar Abençoado (peixe de vida) = defensivas.
 * `evasionBonus` entra como pontos planos de AC/esquiva no d20 (10 + DEX + evasionBonus).
 */
public enum Meal {

    // ── Ofensivas (peixe de estamina) ──
    GRILLED_SKEWER  ("Espetinho de Peixe", "🍤", ResourceType.SMALL_FISH,     2,  8,  0,  0, 0, 30),
    SALMON_FILLET   ("Filé de Salmão",     "🐟", ResourceType.SALMON,         2, 10,  5,  0, 0, 40),
    TUNA_BANQUET    ("Banquete de Atum",   "🍣", ResourceType.TUNA,           2, 12,  0, 20, 0, 45),
    SHARK_STEAK     ("Bife de Tubarão",    "🦈", ResourceType.SHARK,          1, 15, 10,  0, 0, 45),
    LEGENDARY_PLATTER("Prato Lendário",    "🌟", ResourceType.LEGENDARY_FISH, 1, 18, 12, 40, 0, 60),

    // ── Defensivas (peixe de vida) ──
    CORAL_SOUP      ("Sopa de Coral",       "🥣", ResourceType.CORAL_FISH,   2,  0,  0,  30, 0, 30),
    ANGEL_BROTH     ("Caldo Angelical",     "🐠", ResourceType.ANGEL_FISH,   2,  0,  0,  50, 5, 40),
    SPIRIT_STEW     ("Ensopado Espiritual", "🍲", ResourceType.SPIRIT_FISH,  1,  0,  8,  60, 5, 45),
    SACRED_FEAST    ("Festim Sagrado",      "✨", ResourceType.SACRED_FISH,  1,  0, 12,  80, 0, 50),
    PHOENIX_ROAST   ("Assado da Fênix",     "🔥", ResourceType.PHOENIX_FISH, 1,  0, 15, 100, 8, 60);

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
        if (evasionBonus > 0) sb.append("+").append(evasionBonus).append(" evasão ");
        return sb.toString().trim();
    }
}
