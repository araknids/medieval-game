package com.medieval.game.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * D20-based combat simulator (D&D Bounded Accuracy).
 *
 * Each round: attacker rolls d20 + strBonus and must meet or beat defender's AC (10 + dex).
 * Natural 20 = Critical hit (double damage).
 * Natural 1  = Fumble (automatic miss regardless of bonuses).
 * LUK expands crit window and provides Fortune Save against incoming crits.
 *
 * Signature: simulate(name, atk, def, hp, dex, strBonus, luk,
 *                     name2, atk2, def2, hp2, dex2, strBonus2, luk2)
 *   - dex      : DEX attribute (AC = 10 + dex)
 *   - strBonus : floor(STR / 20) → attack roll bonus (0-3)
 *   - luk      : LUK attribute for crit window and Fortune Save
 */
@Component
public class BattleSimulator {

    private static final String[] HIT_TEXTS = {
        "charges fiercely and lands a precise blow",
        "launches a swift and accurate attack",
        "executes a devastating strike",
        "finds an opening and attacks",
        "unleashes a powerful blow",
        "moves quickly and connects",
        "steps back and counter-attacks furiously",
        "exploits a weak spot",
        "delivers a crushing blow",
        "strikes with deadly precision",
    };

    private static final String[] ENEMY_HIT_TEXTS = {
        "retaliates violently",
        "launches a savage attack",
        "responds with a heavy blow",
        "charges and strikes",
        "delivers a treacherous blow",
        "rushes in without hesitation",
        "lands an unexpected hit",
        "strikes with brute force",
        "finds a gap and attacks",
        "hits with a brutal blow",
    };

    private static final String[] BODY_PARTS = {
        "on the neck", "on the shoulder", "in the chest", "on the head",
        "on the arm", "in the side", "on the legs", "in the abdomen",
        "on the back", "across the face",
    };

    private static final String[] MISS_TEXTS = {
        "misses — the attack sails wide",
        "fails to connect",
        "swings and misses",
        "the blow finds no target",
        "strikes air",
        "is deflected by the armor",
    };

    private static final String[] FUMBLE_TEXTS = {
        "fumbles badly — lost balance!",
        "trips and misses completely!",
        "swings wildly and hits nothing!",
        "drops their guard at the worst moment!",
    };

    private static final String[] CRIT_TEXTS = {
        "lands a CRITICAL HIT",
        "delivers a DEVASTATING BLOW",
        "strikes a CRITICAL WEAK SPOT",
        "executes a PERFECT STRIKE",
    };

    private static final String[] VICTORY_TEXTS = {
        "wins the battle!",
        "emerges victorious!",
        "prevails in the fight!",
        "defeats the opponent!",
    };

    /**
     * Simulate a full fight between two combatants.
     *
     * @param dex      DEX attribute → AC = 10 + dex
     * @param strBonus floor(STR / 20) → d20 attack roll bonus
     * @param luk      LUK attribute → crit expansion + Fortune Save
     */
    /** Rich battle outcome: log + winner flag + final HP of both fighters (for ambush HP carry). */
    public record BattleOutcome(List<String> log, boolean firstWon, int firstHpFinal, int secondHpFinal) {}

    /** Backwards-compatible wrapper — returns just the log lines. */
    public List<String> simulate(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cStrBonus, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oStrBonus, int oLuk) {
        return simulateDetailed(
                cName, cAtk, cDef, cHp, cDex, cStrBonus, cLuk,
                oName, oAtk, oDef, oHp, oDex, oStrBonus, oLuk).log();
    }

    /** Full fight to the death. Returns log + winner + final HP of both (clamped ≥ 0). */
    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cStrBonus, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oStrBonus, int oLuk) {

        List<String> log = new ArrayList<>();
        Random rng = new Random();

        int cCurrentHp = cHp;
        int oCurrentHp = oHp;
        int cAc = 10 + cDex;
        int oAc = 10 + oDex;

        // Crit windows: roll >= threshold
        int cCritThreshold = critThreshold(cLuk);
        int oCritThreshold = critThreshold(oLuk);
        // Fortune Save chance (0-10%)
        int cFortuneSave = cLuk / 10;
        int oFortuneSave = oLuk / 10;

        log.add("⚔ " + cName + " vs " + oName + " — The battle begins!");
        log.add("HP: [" + cName + ": ❤ " + cHp + " | AC " + cAc + "] | [" + oName + ": ❤ " + oHp + " | AC " + oAc + "]");
        log.add("─────────────────────────");

        for (int round = 1; round <= 40 && cCurrentHp > 0 && oCurrentHp > 0; round++) {
            log.add("— Round " + round + " —");

            // ── C attacks O ──
            int cRoll = rng.nextInt(20) + 1; // d20
            if (cRoll == 1) {
                // Fumble
                log.add("  " + cName + " " + FUMBLE_TEXTS[rng.nextInt(FUMBLE_TEXTS.length)]);
            } else {
                int total = cRoll + cStrBonus;
                boolean isCrit = cRoll >= cCritThreshold;
                if (isCrit && oFortuneSave > 0 && rng.nextInt(100) < oFortuneSave) {
                    isCrit = false; // Fortune Save cancelled the crit
                    log.add("  ✨ " + oName + " gets a Fortune Save — critical negated!");
                }
                if (total >= oAc || isCrit) {
                    int dmg = Math.max(1, cAtk - oDef);
                    String bodyPart = BODY_PARTS[rng.nextInt(BODY_PARTS.length)];
                    int oAfter = Math.max(0, oCurrentHp - dmg);
                    if (isCrit) {
                        dmg *= 2;
                        oAfter = Math.max(0, oCurrentHp - dmg);
                        log.add("  💥 " + cName + " " + CRIT_TEXTS[rng.nextInt(CRIT_TEXTS.length)]
                                + " " + bodyPart + " of " + oName + "! [-" + dmg + " HP]"
                                + " " + oName + " ❤ " + oAfter + "/" + oHp);
                    } else {
                        log.add("  " + cName + " " + HIT_TEXTS[rng.nextInt(HIT_TEXTS.length)]
                                + " " + bodyPart + " of " + oName + "! [-" + dmg + " HP]"
                                + " " + oName + " ❤ " + oAfter + "/" + oHp);
                    }
                    oCurrentHp -= dmg;
                } else {
                    log.add("  " + cName + " " + MISS_TEXTS[rng.nextInt(MISS_TEXTS.length)] + " [Roll: " + cRoll + "+" + cStrBonus + " vs AC " + oAc + "]");
                }
            }
            if (oCurrentHp <= 0) break;

            // ── O attacks C ──
            int oRoll = rng.nextInt(20) + 1;
            if (oRoll == 1) {
                log.add("  " + oName + " " + FUMBLE_TEXTS[rng.nextInt(FUMBLE_TEXTS.length)]);
            } else {
                int total = oRoll + oStrBonus;
                boolean isCrit = oRoll >= oCritThreshold;
                if (isCrit && cFortuneSave > 0 && rng.nextInt(100) < cFortuneSave) {
                    isCrit = false;
                    log.add("  ✨ " + cName + " gets a Fortune Save — critical negated!");
                }
                if (total >= cAc || isCrit) {
                    int dmg = Math.max(1, oAtk - cDef);
                    if (isCrit) dmg *= 2;
                    String bodyPart = BODY_PARTS[rng.nextInt(BODY_PARTS.length)];
                    int cAfter = Math.max(0, cCurrentHp - dmg);
                    if (isCrit) {
                        log.add("  💥 " + oName + " " + CRIT_TEXTS[rng.nextInt(CRIT_TEXTS.length)]
                                + " " + bodyPart + " of " + cName + "! [-" + dmg + " HP]"
                                + " " + cName + " ❤ " + cAfter + "/" + cHp);
                    } else {
                        log.add("  " + oName + " " + ENEMY_HIT_TEXTS[rng.nextInt(ENEMY_HIT_TEXTS.length)]
                                + " " + bodyPart + " of " + cName + "! [-" + dmg + " HP]"
                                + " " + cName + " ❤ " + cAfter + "/" + cHp);
                    }
                    cCurrentHp -= dmg;
                } else {
                    log.add("  " + oName + " " + MISS_TEXTS[rng.nextInt(MISS_TEXTS.length)] + " [Roll: " + oRoll + "+" + oStrBonus + " vs AC " + cAc + "]");
                }
            }
        }

        log.add("─────────────────────────");
        boolean cWon = cCurrentHp > oCurrentHp;
        String winner = cWon ? cName : oName;
        String loser  = cWon ? oName : cName;
        log.add("🏆 " + winner + " " + VICTORY_TEXTS[rng.nextInt(VICTORY_TEXTS.length)]);
        log.add("WINNER:" + winner + "|LOSER:" + loser);
        return new BattleOutcome(log, cWon, Math.max(0, cCurrentHp), Math.max(0, oCurrentHp));
    }

    /** d20 roll >= this threshold = critical hit. LUK expands window down from 20. */
    public static int critThreshold(int luk) {
        return Math.max(17, 20 - (luk / 15)); // 0 luk=20, 15=19, 30=18, 45+=17 (cap 20%)
    }
}
