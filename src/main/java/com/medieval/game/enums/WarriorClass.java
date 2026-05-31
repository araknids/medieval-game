package com.medieval.game.enums;

public enum WarriorClass {

    WARRIOR("Guerreiro", 15, 12, 110);

    public final String displayName;
    public final int baseAttack;
    public final int baseDefense;
    public final int baseHealth;

    WarriorClass(String displayName, int baseAttack, int baseDefense, int baseHealth) {
        this.displayName = displayName;
        this.baseAttack = baseAttack;
        this.baseDefense = baseDefense;
        this.baseHealth = baseHealth;
    }
}
