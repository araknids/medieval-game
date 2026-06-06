package com.medieval.game.enums;

/**
 * Combat posture: an ATK/DEF tradeoff the player picks, applied to ALL combat (PvE & PvP) via
 * {@code WarriorStatsService.combatStats}. Free toggle. Only touches ATK and DEF. [POSTURE]
 */
public enum CombatPosture {

    OFFENSIVE("⚔️ Offensive", 1.20, 0.85), // more damage, softer defense
    DEFENSIVE("🛡️ Defensive", 0.85, 1.20), // tankier, hits softer
    BALANCED ("⚖️ Balanced",  1.05, 1.05); // small all-round (default)

    public final String displayName;
    private final double atkMult;
    private final double defMult;

    CombatPosture(String displayName, double atkMult, double defMult) {
        this.displayName = displayName;
        this.atkMult     = atkMult;
        this.defMult     = defMult;
    }

    public double atkMult() { return atkMult; }
    public double defMult() { return defMult; }
}
