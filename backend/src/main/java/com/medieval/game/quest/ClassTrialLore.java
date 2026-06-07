package com.medieval.game.quest;

import com.medieval.game.enums.WarriorClass;

/**
 * Narrativa da Path Trial (Lv10): história ANTES do combate (intro) e o desfecho narrado
 * DEPOIS (victory/defeat), por caminho de classe. No estilo do {@link InteractiveQuests}.
 * Texto ao jogador em INGLÊS (i18n pro PT depois). Só conteúdo — não muda combate. [TRIAL_NARRATIVA]
 */
public final class ClassTrialLore {

    private ClassTrialLore() {}

    /** Intro (antes da luta) + desfecho de vitória + desfecho de derrota de um caminho. */
    public record TrialLore(String intro, String victory, String defeat) {}

    public static TrialLore forPath(WarriorClass path) {
        return switch (path) {
            case ARCHER   -> ARCHER_LORE;
            case MERCHANT -> MERCHANT_LORE;
            default       -> WARRIOR_LORE; // WARRIOR (e qualquer caminho de lâmina)
        };
    }

    // ── Warrior — Blade Guardian ────────────────────────────────────────────────
    private static final TrialLore WARRIOR_LORE = new TrialLore(
        "You climb to the training grounds above the city, where the Blade Guardian waits — a "
            + "scarred veteran sworn to test those who would walk the warrior's path. He draws his "
            + "sword without a word. \"Show me you can take a hit and answer it,\" he says. \"The "
            + "blade forgives nothing.\"",
        "Steel rang on steel until the Guardian lowered his blade and bowed his head. You fought "
            + "like a beast unchained — reading every swing, trading blow for blow without flinching. "
            + "\"You have the iron for it,\" he says. You are a Warrior now. The frontline is yours to hold.",
        "The Guardian's blade found every gap in your guard, and the world went dark. You wake "
            + "aching on the training-yard stones. \"Come back when your body remembers the lesson,\" "
            + "he says, sheathing his sword.");

    // ── Archer — Bow Guardian ───────────────────────────────────────────────────
    private static final TrialLore ARCHER_LORE = new TrialLore(
        "You follow the hunters' trail to a windswept ridge, where the Bow Guardian stands with her "
            + "string already drawn. She offers no greeting — only looses an arrow that splits the air "
            + "past your ear. \"Patience and a steady eye, or nothing at all,\" she calls. \"Let's see "
            + "which you are.\"",
        "You danced at the edge of her range, loosing shafts between her volleys until her quiver "
            + "ran dry and yours did not. She lowers her bow with the ghost of a smile. \"You read the "
            + "wind. Good.\" You are an Archer now. Strike from afar, and never let them close.",
        "Her arrows pinned you before you could find your rhythm, and you fell with an empty string "
            + "in your hand. \"Footwork and patience,\" she says, gathering her shafts. \"Come back when "
            + "you've learned both.\"");

    // ── Merchant — Caravan Guardian ─────────────────────────────────────────────
    private static final TrialLore MERCHANT_LORE = new TrialLore(
        "You find the Caravan Guardian in the trade-yard, counting coin atop a crate, a heavy axe "
            + "resting across his knees. \"Everyone wants the road's riches,\" he says without looking "
            + "up, \"but the road bites back.\" He rises, hefting the axe. \"Prove you can guard your "
            + "own purse.\"",
        "Blow for blow you matched him, axe against axe, until he raised an open hand and laughed. "
            + "\"You'll do. Coin's no good to a corpse — and you mean to live.\" You are a Merchant now. "
            + "Let your trade and your craft make you richer than any blade.",
        "He turned your own swings against you and laid you flat beside his crates. \"Strength alone "
            + "won't mind a caravan,\" he says, flipping you a single coin. \"Come back sharper.\"");
}
