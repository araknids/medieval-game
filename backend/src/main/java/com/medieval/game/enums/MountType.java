package com.medieval.game.enums;

/**
 * Catálogo de montarias do Estábulo. Equipada, reduz o custo de estamina das ações e dá
 * bônus de stats (ATK/DEF/HP) com perfis diferenciados. Os 5 de gold compram-se no Estábulo;
 * o Celestial é VIP-only (SoulStones na VIP Shop) e NÃO dá stats — é a montaria 100% estamina.
 * Ver docs/PLANO_ESTABULO.md.
 */
public enum MountType {
    //               displayName            icon  ⚡%  ATK DEF  HP   gold  💎  vip
    PACK_HORSE      ("Cavalo de Carga",    "🐴",  3,   0,  2,  15,   10,  0, false), // tanque leve
    RIDING_HORSE    ("Cavalo de Montaria", "🐎",  6,   4,  4,  20,   30,  0, false), // equilibrado
    WAR_STEED       ("Corcel de Guerra",   "🐎",  9,  12,  3,  20,   75,  0, false), // ofensivo
    ROYAL_STEED     ("Corcel Real",        "🐎", 12,   6, 12,  40,  150,  0, false), // defensivo
    LEGENDARY_STEED ("Corcel Lendário",    "🏇", 15,  14, 12,  60,  300,  0, false), // completo
    CELESTIAL_MOUNT ("Montaria Celestial", "💎", 20,   0,  0,   0,    0, 12, true);  // só estamina (VIP)

    /** Nome exibido. */
    public final String displayName;
    /** Ícone (emoji). */
    public final String icon;
    /** Redução de estamina em % (0-100) ao equipar. */
    public final int staminaReductionPct;
    /** Bônus de ataque ao equipar. */
    public final int attackBonus;
    /** Bônus de defesa ao equipar. */
    public final int defenseBonus;
    /** Bônus de vida ao equipar. */
    public final int healthBonus;
    /** Preço em gold (0 se vipOnly/soulstone). */
    public final long priceGold;
    /** Preço em SoulStones (0 se compra-se com gold). */
    public final int priceSoulStones;
    /** Só comprável por VIP (na VIP Shop). */
    public final boolean vipOnly;

    MountType(String displayName, String icon, int staminaReductionPct,
              int attackBonus, int defenseBonus, int healthBonus,
              long priceGold, int priceSoulStones, boolean vipOnly) {
        this.displayName         = displayName;
        this.icon                = icon;
        this.staminaReductionPct = staminaReductionPct;
        this.attackBonus         = attackBonus;
        this.defenseBonus        = defenseBonus;
        this.healthBonus         = healthBonus;
        this.priceGold           = priceGold;
        this.priceSoulStones     = priceSoulStones;
        this.vipOnly             = vipOnly;
    }
}
