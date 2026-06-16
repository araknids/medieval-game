package com.medieval.game.enums;

/**
 * Gênero do personagem — puramente cosmético (decide a base + as peças Male/Female do paper-doll
 * no cliente Godot). Não afeta stats nem combate. Escolhido na criação e trocável no Settings.
 * [OUTFITS_FEMALE]
 */
public enum Gender {
    MALE,
    FEMALE;

    /** Parse tolerante (case-insensitive; qualquer coisa que não seja FEMALE vira MALE). */
    public static Gender from(String s) {
        return s != null && s.trim().equalsIgnoreCase("FEMALE") ? FEMALE : MALE;
    }
}
