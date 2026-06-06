package com.medieval.game.enums;

/**
 * Elementos (D&D básico) p/ encantamento e combate. Roda RPS: cada um vence um e perde pra outro.
 * FOGO→AR→TERRA→ÁGUA→FOGO. Opostos neutros (Fogo↔Terra, Ar↔Água). [ELEMENTOS]
 *
 * No combate: arma do atacante vs armadura do defensor → ×1.25 se a arma vence, ×0.75 se perde,
 * ×1.0 neutro/mesmo/sem encantamento. Monstro usa 1 elemento como arma E armadura.
 */
public enum Element {
    FIRE ("Fire",  "🔥"),
    WATER("Water", "💧"),
    EARTH("Earth", "🪨"),
    AIR  ("Air",   "💨");

    public final String displayName;
    public final String icon;

    Element(String displayName, String icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    /** O elemento que ESTE vence (roda RPS). */
    public Element beatsTarget() {
        return switch (this) {
            case FIRE  -> AIR;
            case AIR   -> EARTH;
            case EARTH -> WATER;
            case WATER -> FIRE;
        };
    }

    public boolean beats(Element other) { return other != null && beatsTarget() == other; }

    /** Essência (ResourceType) usada p/ encantar com este elemento. */
    public ResourceType essence() {
        return switch (this) {
            case FIRE  -> ResourceType.FIRE_ESSENCE;
            case WATER -> ResourceType.WATER_ESSENCE;
            case EARTH -> ResourceType.EARTH_ESSENCE;
            case AIR   -> ResourceType.AIR_ESSENCE;
        };
    }

    /**
     * Multiplicador de dano: arma do atacante × armadura do defensor.
     * Vence → +25% (×1.25). Perde → −25% (×0.75). Neutro/mesmo/sem encanto → ×1.0.
     */
    public static double multiplier(Element attackerWeapon, Element defenderArmor) {
        if (attackerWeapon == null || defenderArmor == null) return 1.0;
        if (attackerWeapon.beats(defenderArmor)) return 1.25;
        if (defenderArmor.beats(attackerWeapon)) return 0.75;
        return 1.0;
    }
}
