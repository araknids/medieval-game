package com.medieval.game.enums;

/**
 * Tipo de arma com perfil de stats. Mesmo budget de poder, distribuição diferente —
 * ninguém é "mais forte", é tradeoff. [CLASSES_ARMAS]
 *
 * O tipo é inferido do NOME (EN+PT), então toda fonte (loja/loot/forja/starter/mail) se
 * auto-perfila só pelo nome+nível, sem mudar a assinatura do make(). Armas não dão HP
 * (identidade ofensiva); o secundário entra nos stats já existentes (STR=ATK+acerto,
 * DEX=AC, LUK=crit).
 */
public enum WeaponType {

    //          display,       categoria,               atk,  def,  str,  dex,  luk
    SWORD      ("Sword",       WeaponCategory.MELEE,   0.70, 0.30, 0.00, 0.00, 0.00),
    GREATSWORD ("Greatsword",  WeaponCategory.MELEE,   1.00, 0.00, 0.00, 0.00, 0.00),
    AXE        ("Axe",         WeaponCategory.MELEE,   0.75, 0.00, 0.00, 0.00, 0.25),
    SPEAR      ("Spear",       WeaponCategory.MELEE,   0.75, 0.00, 0.25, 0.00, 0.00),
    MACE       ("Mace",        WeaponCategory.MELEE,   0.78, 0.00, 0.22, 0.00, 0.00), // blunt: pancada certeira (ATK+STR) [MERCADOR]
    SHORTBOW   ("Short Bow",   WeaponCategory.RANGED,  0.75, 0.00, 0.00, 0.25, 0.00),
    LONGBOW    ("Long Bow",    WeaponCategory.RANGED,  1.00, 0.00, 0.00, 0.00, 0.00),
    CROSSBOW   ("Crossbow",    WeaponCategory.RANGED,  0.75, 0.00, 0.00, 0.00, 0.25);

    public final String         displayName;
    public final WeaponCategory category;
    public final double atkFrac, defFrac, strFrac, dexFrac, lukFrac;

    WeaponType(String displayName, WeaponCategory category,
               double atkFrac, double defFrac, double strFrac, double dexFrac, double lukFrac) {
        this.displayName = displayName;
        this.category = category;
        this.atkFrac = atkFrac; this.defFrac = defFrac;
        this.strFrac = strFrac; this.dexFrac = dexFrac; this.lukFrac = lukFrac;
    }

    private static double rarityMult(int rarity) {
        return switch (rarity) { case 2 -> 1.2; case 3 -> 1.45; case 4 -> 1.75; case 5 -> 2.1; default -> 1.0; };
    }

    /**
     * Stats da arma deste tipo p/ um item de {@code itemLevel}/{@code rarity}.
     * Budget de poder (ATK-equiv) = itemLevel × rarityMult × 0.6, distribuído pelas frações.
     * Retorna {@code {atk, def, hp(=0), str, dex, luk}}. ATK mínimo 1.
     */
    public int[] stats(int itemLevel, int rarity) {
        double budget = Math.max(1, itemLevel) * rarityMult(rarity) * 0.6;
        int atk = Math.max(1, (int) Math.round(budget * atkFrac));
        int def = (int) Math.round(budget * defFrac);
        int str = (int) Math.round(budget * strFrac);
        int dex = (int) Math.round(budget * dexFrac);
        int luk = (int) Math.round(budget * lukFrac);
        return new int[]{ atk, def, 0, str, dex, luk };
    }

    // Palavras-chave por tipo (EN+PT). Ordem de checagem importa: específicos antes de genéricos
    // (crossbow/longbow/shortbow antes de "bow"; greatsword antes de "sword").
    /** Infere o tipo da arma pelo nome. Sem casar → SWORD (melee, default seguro). */
    public static WeaponType fromName(String name) {
        if (name == null) return SWORD;
        String n = name.toLowerCase();
        // Ranged
        if (containsAny(n, "crossbow", "besta"))                       return CROSSBOW;
        if (containsAny(n, "long bow", "longbow", "arco longo"))       return LONGBOW;
        if (containsAny(n, "short bow", "shortbow", "arco curto"))     return SHORTBOW;
        if (containsAny(n, "bow", "arco"))                             return SHORTBOW; // arco genérico
        // Melee
        if (containsAny(n, "greatsword", "great sword", "two-handed", "montante", "espada longa", "espada de duas")) return GREATSWORD;
        if (containsAny(n, "axe", "machado"))                          return AXE;
        if (containsAny(n, "mace", "marreta", "maul", "hammer", "martelo", "club", "clava")) return MACE; // [MERCADOR]
        if (containsAny(n, "spear", "lance", "lança", "lanca", "pike")) return SPEAR;
        return SWORD; // sword/blade/espada/qualquer outro melee
    }

    private static boolean containsAny(String n, String... keys) {
        for (String k : keys) if (n.contains(k)) return true;
        return false;
    }
}
