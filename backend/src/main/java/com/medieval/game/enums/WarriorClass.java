package com.medieval.game.enums;

/**
 * Classe do personagem. Todo mundo nasce {@link #RECRUIT} (neutro) e, no Lv10,
 * faz a Path Trial pra virar {@link #WARRIOR} (tank) ou {@link #ARCHER} (crit/esquiva).
 * Permanente. Sem magia ainda — {@code INT} fica reservado p/ uma futura classe Mage.
 *
 * A diferença entre as classes é SÓ stats base + caps de atributo — o motor de combate
 * (BattleSimulator) não muda. [REBALANCE] Papéis: STR=dano, DEX=acerto, AGI=golpes extra+esquiva,
 * LUK=crit, CON=HP. Warrior=dano+HP, Archer=acerto+velocidade+crit, Merchant=equilíbrio+sorte. [CLASSES]
 */
public enum WarriorClass {

    //        displayName,  atk, def,  hp, strCap, dexCap,           conCap, agiCap, lukCap, intCap
    RECRUIT  ("Recruit",     12,  10, 100,     40,     40, Integer.MAX_VALUE,     40,     40,     30),
    WARRIOR  ("Warrior",     15,  14, 130,     80,     30, Integer.MAX_VALUE,     25,     30,     20),
    ARCHER   ("Archer",      12,   9,  95,     45,     45, Integer.MAX_VALUE,     40,     70,     20), // [REBALANCE] DEX escala o dano → base ATK menor
    MERCHANT ("Merchant",    15,  11, 115,     55,     40, Integer.MAX_VALUE,     35,     60,     20); // classe de economia (machado/marreta) [MERCADOR]

    public final String displayName;
    public final int baseAttack;
    public final int baseDefense;
    public final int baseHealth;
    public final int strCap;
    public final int dexCap;
    public final int conCap;
    public final int agiCap; // [REBALANCE] Agilidade: ataques extra + esquiva
    public final int lukCap;
    public final int intCap;

    WarriorClass(String displayName, int baseAttack, int baseDefense, int baseHealth,
                 int strCap, int dexCap, int conCap, int agiCap, int lukCap, int intCap) {
        this.displayName = displayName;
        this.baseAttack  = baseAttack;
        this.baseDefense = baseDefense;
        this.baseHealth  = baseHealth;
        this.strCap = strCap;
        this.dexCap = dexCap;
        this.conCap = conCap;
        this.agiCap = agiCap;
        this.lukCap = lukCap;
        this.intCap = intCap;
    }

    /** Cap do atributo {@code attr} pra esta classe (usado em WarriorService.spendPoint). */
    public int capFor(Attribute attr) {
        return switch (attr) {
            case STRENGTH     -> strCap;
            case DEXTERITY    -> dexCap;
            case CONSTITUTION -> conCap;
            case AGILITY      -> agiCap;
            case LUCK         -> lukCap;
            case INTELLECT    -> intCap;
        };
    }

    /** false só pra RECRUIT — ainda não escolheu o caminho na Trial. [CLASSES] */
    public boolean isSpecialized() { return this != RECRUIT; }

    /**
     * @deprecated [CLASSES_ARMAS] O atributo de dano agora segue a ARMA equipada (arco→DEX, melee→STR),
     * não a classe — ver {@code WarriorStatsService.isRangedWeaponEquipped} + {@code Warrior.getTotalBaseAttack(boolean)}.
     * Mantido só por referência.
     */
    @Deprecated
    public Attribute damageAttribute() { return this == ARCHER ? Attribute.DEXTERITY : Attribute.STRENGTH; }

    /**
     * @deprecated [KITING][CLASSES_ARMAS] O kiting agora segue a ARMA equipada (arco→ranged), não a
     * classe — ver {@code WarriorStatsService.isRangedWeaponEquipped}. Mantido só por referência.
     */
    @Deprecated
    public boolean isRanged() { return this == ARCHER; }

    /** Categoria de arma que a classe pode equipar: Archer = RANGED, resto = MELEE. [CLASSES_ARMAS] */
    public WeaponCategory weaponCategory() {
        return this == ARCHER ? WeaponCategory.RANGED : WeaponCategory.MELEE;
    }

    /**
     * Pode equipar este TIPO de arma? [CLASSES_ARMAS] Restrição REMOVIDA — qualquer classe equipa
     * qualquer arma (o tradeoff vira só o perfil de stats da arma vs o atributo de dano da classe:
     * Archer escala em DEX, melee em STR — ver {@link #damageAttribute()}). Mantido como hook caso
     * se queira reintroduzir alguma trava no futuro.
     */
    public boolean canEquip(WeaponType type) {
        return true;
    }
}
