package com.medieval.game.enums;

/**
 * Catálogo de montarias do Estábulo. Equipada, reduz o custo de estamina das ações.
 * Os 5 primeiros compram-se com gold no Estábulo; o Celestial é VIP-only e compra-se
 * com SoulStones na VIP Shop. Bônus de stats ficam pra uma fase futura. Ver docs/PLANO_ESTABULO.md.
 */
public enum MountType {
    PACK_HORSE      ("Cavalo de Carga",    "🐴",  3,   10, 0,  false),
    RIDING_HORSE    ("Cavalo de Montaria", "🐎",  6,   30, 0,  false),
    WAR_STEED       ("Corcel de Guerra",   "🐎",  9,   75, 0,  false),
    ROYAL_STEED     ("Corcel Real",        "🐎", 12,  150, 0,  false),
    LEGENDARY_STEED ("Corcel Lendário",    "🏇", 15,  300, 0,  false),
    CELESTIAL_MOUNT ("Montaria Celestial", "💎", 20,    0, 12, true);

    /** Nome exibido. */
    public final String displayName;
    /** Ícone (emoji). */
    public final String icon;
    /** Redução de estamina em % (0-100) ao equipar. */
    public final int staminaReductionPct;
    /** Preço em gold (0 se vipOnly/soulstone). */
    public final long priceGold;
    /** Preço em SoulStones (0 se compra-se com gold). */
    public final int priceSoulStones;
    /** Só comprável por VIP (na VIP Shop). */
    public final boolean vipOnly;

    MountType(String displayName, String icon, int staminaReductionPct,
              long priceGold, int priceSoulStones, boolean vipOnly) {
        this.displayName         = displayName;
        this.icon                = icon;
        this.staminaReductionPct = staminaReductionPct;
        this.priceGold           = priceGold;
        this.priceSoulStones     = priceSoulStones;
        this.vipOnly             = vipOnly;
    }
}
