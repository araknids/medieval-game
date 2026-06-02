package com.medieval.game.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
public class BattleSimulator {

    private static final String[] WARRIOR_ATTACKS = {
        "charges fiercely and lands a precise blow",
        "launches a swift and accurate attack",
        "rushes in with full force",
        "executes a devastating chain of strikes",
        "leaps and strikes with precision",
        "exploits an opening and attacks",
        "unleashes a powerful blow",
        "moves quickly and strikes",
        "finds a weak spot and hits",
        "steps back and counter-attacks furiously",
    };

    private static final String[] ENEMY_ATTACKS = {
        "retaliates violently",
        "launches a savage attack",
        "responds with a heavy blow",
        "charges forward and strikes",
        "attempts to crush with brute force",
        "delivers a treacherous blow",
        "finds a gap and attacks",
        "rushes in without hesitation",
        "lands an unexpected strike",
        "hits with a brutal blow",
    };

    private static final String[] BODY_PARTS = {
        "on the neck", "on the shoulder", "in the chest", "on the head",
        "on the arm", "in the side", "on the legs", "in the abdomen",
        "on the back", "across the face",
    };

    private static final String[] EVASIONS = {
        "dodges at the last second",
        "steps back skillfully",
        "blocks the blow with their weapon",
        "rolls to the side",
        "evades with incredible agility",
        "stops the impact with their shield",
        "ducks quickly",
        "dances out of reach",
    };

    private static final String[] VICTORY_TEXTS = {
        "wins the battle!",
        "emerges victorious!",
        "prevails in the fight!",
        "defeats the opponent!",
    };

    public List<String> simulate(
            String cName, int cAtk, int cDef, int cHp, int cEvasion,
            String oName, int oAtk, int oDef, int oHp, int oEvasion) {

        List<String> log = new ArrayList<>();
        Random rng = new Random();
        int cCurrentHp = cHp;
        int oCurrentHp = oHp;

        log.add("⚔ " + cName + " vs " + oName + " — The battle begins!");
        log.add("HP: [" + cName + ": ❤ " + cHp + "] | [" + oName + ": ❤ " + oHp + "]");
        log.add("─────────────────────────");

        for (int round = 1; round <= 30 && cCurrentHp > 0 && oCurrentHp > 0; round++) {
            log.add("— Round " + round + " —");

            // Attacker attacks opponent
            if (rng.nextInt(100) < oEvasion) {
                String evade = EVASIONS[rng.nextInt(EVASIONS.length)];
                log.add("  " + oName + " " + evade + "!");
            } else {
                String attack   = WARRIOR_ATTACKS[rng.nextInt(WARRIOR_ATTACKS.length)];
                String bodyPart = BODY_PARTS[rng.nextInt(BODY_PARTS.length)];
                int dmg = Math.max(1, cAtk - rng.nextInt(Math.max(1, oDef / 2 + 1)));
                oCurrentHp -= dmg;
                log.add("  " + cName + " " + attack + " " + bodyPart + " of "
                        + oName + "! [-" + dmg + " HP] ❤ " + Math.max(0, oCurrentHp));
            }
            if (oCurrentHp <= 0) break;

            // Opponent attacks attacker
            if (rng.nextInt(100) < cEvasion) {
                String evade = EVASIONS[rng.nextInt(EVASIONS.length)];
                log.add("  " + cName + " " + evade + "!");
            } else {
                String attack   = ENEMY_ATTACKS[rng.nextInt(ENEMY_ATTACKS.length)];
                String bodyPart = BODY_PARTS[rng.nextInt(BODY_PARTS.length)];
                int dmg = Math.max(1, oAtk - rng.nextInt(Math.max(1, cDef / 2 + 1)));
                cCurrentHp -= dmg;
                log.add("  " + oName + " " + attack + " " + bodyPart + " of "
                        + cName + "! [-" + dmg + " HP] ❤ " + Math.max(0, cCurrentHp));
            }
        }

        log.add("─────────────────────────");
        boolean cWon = cCurrentHp > oCurrentHp;
        String winner = cWon ? cName : oName;
        String loser  = cWon ? oName : cName;
        String victoryText = VICTORY_TEXTS[rng.nextInt(VICTORY_TEXTS.length)];
        log.add("🏆 " + winner + " " + victoryText);
        log.add("WINNER:" + winner + "|LOSER:" + loser);
        return log;
    }
}
