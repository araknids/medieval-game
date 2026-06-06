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

    /** Categoria de uma arma a partir do nome — delega ao {@link WeaponType} (fonte única). */
    public static WeaponCategory fromWeaponName(String name) {
        return WeaponType.fromName(name).category;
    }
}
