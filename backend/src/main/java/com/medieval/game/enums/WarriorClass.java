package com.medieval.game.enums;

/**
 * Classe do personagem. Todo mundo nasce {@link #RECRUIT} (neutro) e, no Lv10,
 * faz a Path Trial pra virar {@link #WARRIOR} (tank) ou {@link #ARCHER} (crit/esquiva).
 * Permanente. Sem magia ainda — {@code INT} fica reservado p/ uma futura classe Mage.
 *
 * A diferença entre as classes é SÓ stats base + caps de atributo — o motor de combate
 * (BattleSimulator) não muda. STR/CON/DEF = perfil tank; DEX/LUK = perfil crit/esquiva. [CLASSES]
 */
public enum WarriorClass {

    //       displayName, atk, def,  hp, strCap, dexCap,           conCap, lukCap, intCap
    RECRUIT ("Recruit",    12,  10, 100,     40,     40, Integer.MAX_VALUE,     40,     30),
    WARRIOR ("Warrior",    15,  14, 130,     80,     30, Integer.MAX_VALUE,     40,     20),
    ARCHER  ("Archer",     18,   9,  95,     50,     55, Integer.MAX_VALUE,     70,     20);

    public final String displayName;
    public final int baseAttack;
    public final int baseDefense;
    public final int baseHealth;
    public final int strCap;
    public final int dexCap;
    public final int conCap;
    public final int lukCap;
    public final int intCap;

    WarriorClass(String displayName, int baseAttack, int baseDefense, int baseHealth,
                 int strCap, int dexCap, int conCap, int lukCap, int intCap) {
        this.displayName = displayName;
        this.baseAttack  = baseAttack;
        this.baseDefense = baseDefense;
        this.baseHealth  = baseHealth;
        this.strCap = strCap;
        this.dexCap = dexCap;
        this.conCap = conCap;
        this.lukCap = lukCap;
        this.intCap = intCap;
    }

    /** Cap do atributo {@code attr} pra esta classe (usado em WarriorService.spendPoint). */
    public int capFor(Attribute attr) {
        return switch (attr) {
            case STRENGTH     -> strCap;
            case DEXTERITY    -> dexCap;
            case CONSTITUTION -> conCap;
            case LUCK         -> lukCap;
            case INTELLECT    -> intCap;
        };
    }

    /** false só pra RECRUIT — ainda não escolheu o caminho na Trial. [CLASSES] */
    public boolean isSpecialized() { return this != RECRUIT; }
}
