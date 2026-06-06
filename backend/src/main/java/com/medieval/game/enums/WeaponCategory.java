package com.medieval.game.enums;

/**
 * Categoria de arma p/ a trava por classe: Guerreiro/Recruit só usa {@link #MELEE}
 * (espada/machado/lança), Arqueiro só {@link #RANGED} (arco). [CLASSES_ARMAS]
 *
 * A categoria é derivada do NOME da arma (curado) dentro de {@code InventoryService.make()},
 * então todas as fontes (starter, loja, forja, loot, mail) já saem com a categoria certa
 * sem mudar a assinatura do make(). Nome com palavra de arco → RANGED; senão MELEE.
 */
public enum WeaponCategory {
    MELEE ("Melee",  "🗡"),
    RANGED("Ranged", "🏹");

    public final String displayName;
    public final String icon;

    WeaponCategory(String displayName, String icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    // Palavras (EN+PT) que marcam uma arma como à distância. Tudo que não casa = corpo-a-corpo.
    private static final String[] RANGED_KEYWORDS = {"bow", "crossbow", "arco", "besta"};

    /** Categoria de uma arma a partir do nome. Sem palavra de arco → MELEE (default seguro). */
    public static WeaponCategory fromWeaponName(String name) {
        if (name == null) return MELEE;
        String n = name.toLowerCase();
        for (String k : RANGED_KEYWORDS) if (n.contains(k)) return RANGED;
        return MELEE;
    }
}
