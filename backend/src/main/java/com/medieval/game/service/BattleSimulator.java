package com.medieval.game.service;

import com.medieval.game.enums.AbilityEffect;
import com.medieval.game.enums.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * D20-based combat simulator (D&D Bounded Accuracy).
 *
 * Each round: attacker rolls d20 + strBonus and must meet or beat defender's AC (10 + dex).
 * Natural 20 = Critical hit (double damage). Natural 1 = Fumble. LUK expands crit window +
 * Fortune Save. Elementos (roda RPS) aplicam ±25% por golpe. [ELEMENTOS]
 *
 * Habilidades ATIVAS [HABILIDADES]: cada lado leva um kit de {@link ActiveAbility} (effect +
 * cooldown fixo + magnitude já calculada do nível). O loop dispara no cooldown e escreve no log.
 * Kit vazio = combate idêntico ao anterior.
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

    /** Rich battle outcome: log + winner flag + final HP of both fighters (for ambush HP carry). */
    public record BattleOutcome(List<String> log, boolean firstWon, int firstHpFinal, int secondHpFinal) {}

    /** Habilidade ativa pronta p/ o simulador: efeito, cooldown (rounds) e magnitude (já do nível). [HABILIDADES] */
    public record ActiveAbility(AbilityEffect effect, int cooldown, int magnitude) {}

    /** Lutador completo (stats + elementos + ativas). stats = [atk, def, hp, dex, strBonus, luk]. */
    public record Combatant(String name, int atk, int def, int hp, int dex, int strBonus, int luk,
                            Element weapon, Element armor, List<ActiveAbility> abilities) {
        public static Combatant of(String name, int[] s, Element weapon, Element armor, List<ActiveAbility> abilities) {
            return new Combatant(name, s[0], s[1], s[2], s[3], s[4], s[5], weapon, armor,
                    abilities != null ? abilities : List.of());
        }
    }

    // ── Wrappers compatíveis ───────────────────────────────────────────────────

    /** Backwards-compatible wrapper — returns just the log lines. */
    public List<String> simulate(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cStrBonus, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oStrBonus, int oLuk) {
        return simulateDetailed(
                cName, cAtk, cDef, cHp, cDex, cStrBonus, cLuk,
                oName, oAtk, oDef, oHp, oDex, oStrBonus, oLuk).log();
    }

    /** PvP default: no timeout (40 rounds), desempate por %HP restante. [COMBATE_V2] */
    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cStrBonus, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oStrBonus, int oLuk) {
        return simulateDetailed(cName, cAtk, cDef, cHp, cDex, cStrBonus, cLuk,
                                oName, oAtk, oDef, oHp, oDex, oStrBonus, oLuk, false);
    }

    /**
     * @param firstLosesOnTimeout PvE: se ninguém morrer em 40 rounds, o 1º combatente PERDE.
     */
    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cStrBonus, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oStrBonus, int oLuk,
            boolean firstLosesOnTimeout) {
        return simulateDetailed(cName, cAtk, cDef, cHp, cDex, cStrBonus, cLuk,
                oName, oAtk, oDef, oHp, oDex, oStrBonus, oLuk,
                firstLosesOnTimeout, null, null, null, null);
    }

    /** Versão com ELEMENTOS (sem habilidades ativas). [ELEMENTOS] */
    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cStrBonus, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oStrBonus, int oLuk,
            boolean firstLosesOnTimeout,
            Element cWeapon, Element cArmor, Element oWeapon, Element oArmor) {
        return simulateDetailed(cName, cAtk, cDef, cHp, cDex, cStrBonus, cLuk,
                oName, oAtk, oDef, oHp, oDex, oStrBonus, oLuk,
                firstLosesOnTimeout, cWeapon, cArmor, oWeapon, oArmor, List.of(), List.of());
    }

    /** Conveniência: dois {@link Combatant} (stats + elementos + ativas). [HABILIDADES] */
    public BattleOutcome simulate(Combatant a, Combatant b, boolean firstLosesOnTimeout) {
        return simulateDetailed(
                a.name(), a.atk(), a.def(), a.hp(), a.dex(), a.strBonus(), a.luk(),
                b.name(), b.atk(), b.def(), b.hp(), b.dex(), b.strBonus(), b.luk(),
                firstLosesOnTimeout, a.weapon(), a.armor(), b.weapon(), b.armor(),
                a.abilities(), b.abilities());
    }

    // ── Núcleo ──────────────────────────────────────────────────────────────────

    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cStrBonus, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oStrBonus, int oLuk,
            boolean firstLosesOnTimeout,
            Element cWeapon, Element cArmor, Element oWeapon, Element oArmor,
            List<ActiveAbility> cAbilities, List<ActiveAbility> oAbilities) {

        List<String> log = new ArrayList<>();
        Random rng = java.util.concurrent.ThreadLocalRandom.current();

        Side c = new Side(cName, cAtk, cDef, cHp, cDex, cStrBonus, cLuk, cWeapon, cArmor, cAbilities);
        Side o = new Side(oName, oAtk, oDef, oHp, oDex, oStrBonus, oLuk, oWeapon, oArmor, oAbilities);

        log.add("⚔ " + c.name + " vs " + o.name + " — The battle begins!");
        log.add("HP: [" + c.name + ": ❤ " + c.maxHp + " | AC " + c.ac + "] | [" + o.name + ": ❤ " + o.maxHp + " | AC " + o.ac + "]");
        log.add("─────────────────────────");

        for (int round = 1; round <= 40 && c.hp > 0 && o.hp > 0; round++) {
            log.add("— Round " + round + " —");

            applySelfTriggers(c, log);
            applySelfTriggers(o, log);

            attack(c, o, HIT_TEXTS, log, rng);
            if (o.hp <= 0 || c.hp <= 0) { tick(c); tick(o); break; }

            attack(o, c, ENEMY_HIT_TEXTS, log, rng);

            tick(c);
            tick(o);
        }

        log.add("─────────────────────────");
        boolean cWon;
        if (o.hp <= 0)      cWon = true;
        else if (c.hp <= 0) cWon = false;
        else cWon = firstLosesOnTimeout ? false
                  : ((double) c.hp / c.maxHp) > ((double) o.hp / o.maxHp);
        String winner = cWon ? c.name : o.name;
        String loser  = cWon ? o.name : c.name;
        log.add("🏆 " + winner + " " + VICTORY_TEXTS[rng.nextInt(VICTORY_TEXTS.length)]);
        log.add("WINNER:" + winner + "|LOSER:" + loser);
        return new BattleOutcome(log, cWon, Math.max(0, c.hp), Math.max(0, o.hp));
    }

    /** Gatilhos de auto-buff/cura no início do round (Berserk, Second Wind). [HABILIDADES] */
    private void applySelfTriggers(Side s, List<String> log) {
        if (s.hp <= 0) return;
        // Second Wind: 1×/luta, cura ao cair abaixo de 30%.
        if (s.has(AbilityEffect.HEAL_LOW) && !s.secondWindUsed && s.hp < s.maxHp * 0.30) {
            int heal = Math.max(1, (int) Math.round(s.maxHp * s.mag(AbilityEffect.HEAL_LOW) / 100.0));
            s.hp = Math.min(s.maxHp, s.hp + heal);
            s.secondWindUsed = true;
            log.add("  ❤ " + s.name + " uses Second Wind — heals +" + heal + " HP! (" + s.hp + "/" + s.maxHp + ")");
        }
        // Berserk: ao cair abaixo de 50%, +ATK% por 3 rounds (respeita cooldown).
        if (s.has(AbilityEffect.ATK_BUFF_LOW) && s.berserkRounds <= 0 && s.ready(AbilityEffect.ATK_BUFF_LOW)
                && s.hp < s.maxHp * 0.50) {
            s.berserkRounds = 3;
            s.trigger(AbilityEffect.ATK_BUFF_LOW);
            log.add("  🔥 " + s.name + " enters a Berserk rage! +" + s.mag(AbilityEffect.ATK_BUFF_LOW) + "% ATK");
        }
    }

    /** Um ataque de {@code atk} em {@code def}, com elementos + habilidades ativas. */
    private void attack(Side atk, Side def, String[] hitTexts, List<String> log, Random rng) {
        int roll = rng.nextInt(20) + 1;
        boolean precise = atk.ready(AbilityEffect.GUARANTEED_CRIT); // Precise Shot: hit + crit garantidos

        if (roll == 1 && !precise) {
            log.add("  " + atk.name + " " + FUMBLE_TEXTS[rng.nextInt(FUMBLE_TEXTS.length)]);
            return;
        }

        int total = roll + atk.strBonus;
        boolean isCrit = roll >= atk.critThreshold;
        if (isCrit && !precise && def.fortuneSave > 0 && rng.nextInt(100) < def.fortuneSave) {
            isCrit = false;
            log.add("  ✨ " + def.name + " gets a Fortune Save — critical negated!");
        }

        boolean hit = precise || total >= def.ac || isCrit;
        if (!hit) {
            log.add("  " + atk.name + " " + MISS_TEXTS[rng.nextInt(MISS_TEXTS.length)]
                    + " [Roll: " + roll + "+" + atk.strBonus + " vs AC " + def.ac + "]");
            return;
        }

        // Evasive Roll do DEFENSOR anula este golpe + reflete dano. [HABILIDADES]
        if (def.ready(AbilityEffect.DODGE_INCOMING)) {
            def.trigger(AbilityEffect.DODGE_INCOMING);
            int reflect = def.mag(AbilityEffect.DODGE_INCOMING);
            atk.hp -= reflect;
            log.add("  🌀 " + def.name + " rolls aside — dodges the blow and reflects " + reflect + " damage!");
            return;
        }

        int preciseBonus = 0;
        String preciseTag = "";
        if (precise) {
            isCrit = true;
            preciseBonus = atk.mag(AbilityEffect.GUARANTEED_CRIT);
            atk.trigger(AbilityEffect.GUARANTEED_CRIT);
            preciseTag = " 🎯";
        }

        double elemMult = Element.multiplier(atk.weapon, def.armor);
        int dmg = Math.max(1, (int) Math.round(mitigatedDamage(atk.effAtk(), def.def) * elemMult));
        String note = elementNote(elemMult);

        // Shield Bash: dano bônus no golpe. [HABILIDADES]
        String bashTag = "";
        if (atk.ready(AbilityEffect.BONUS_DAMAGE)) {
            int bonus = atk.mag(AbilityEffect.BONUS_DAMAGE);
            dmg += bonus;
            atk.trigger(AbilityEffect.BONUS_DAMAGE);
            bashTag = " 💥+" + bonus;
        }
        dmg += preciseBonus;
        if (isCrit) dmg *= 2;

        int defAfter = Math.max(0, def.hp - dmg);
        String bodyPart = BODY_PARTS[rng.nextInt(BODY_PARTS.length)];
        if (isCrit) {
            log.add("  💥 " + atk.name + " " + CRIT_TEXTS[rng.nextInt(CRIT_TEXTS.length)]
                    + " " + bodyPart + " of " + def.name + "! [-" + dmg + " HP" + note + bashTag + preciseTag + "]"
                    + " " + def.name + " ❤ " + defAfter + "/" + def.maxHp);
        } else {
            log.add("  " + atk.name + " " + hitTexts[rng.nextInt(hitTexts.length)]
                    + " " + bodyPart + " of " + def.name + "! [-" + dmg + " HP" + note + bashTag + "]"
                    + " " + def.name + " ❤ " + defAfter + "/" + def.maxHp);
        }
        def.hp -= dmg;

        // Volley: um ataque extra a X% do dano. [HABILIDADES]
        if (def.hp > 0 && atk.ready(AbilityEffect.EXTRA_ATTACK)) {
            atk.trigger(AbilityEffect.EXTRA_ATTACK);
            int extra = Math.max(1, (int) Math.round(
                    mitigatedDamage(atk.effAtk(), def.def) * elemMult * atk.mag(AbilityEffect.EXTRA_ATTACK) / 100.0));
            int after = Math.max(0, def.hp - extra);
            log.add("  ☄ " + atk.name + " looses a Volley — extra hit! [-" + extra + " HP] " + def.name + " ❤ " + after + "/" + def.maxHp);
            def.hp -= extra;
        }
    }

    private void tick(Side s) {
        s.cooldowns.replaceAll((e, v) -> Math.max(0, v - 1));
        if (s.berserkRounds > 0) s.berserkRounds--;
    }

    /** Estado mutável de um lado no combate. */
    private static final class Side {
        final String name;
        final int atk, def, dex, strBonus, luk, ac, critThreshold, fortuneSave, maxHp;
        final Element weapon, armor;
        final Map<AbilityEffect, ActiveAbility> abilities = new EnumMap<>(AbilityEffect.class);
        final Map<AbilityEffect, Integer> cooldowns = new EnumMap<>(AbilityEffect.class);
        int hp;
        int berserkRounds = 0;
        boolean secondWindUsed = false;

        Side(String name, int atk, int def, int hp, int dex, int strBonus, int luk,
             Element weapon, Element armor, List<ActiveAbility> kit) {
            this.name = name; this.atk = atk; this.def = def; this.dex = dex;
            this.strBonus = strBonus; this.luk = luk; this.weapon = weapon; this.armor = armor;
            this.maxHp = hp; this.hp = hp;
            this.ac = 10 + dex;
            this.critThreshold = critThreshold(luk);
            this.fortuneSave = luk / 10;
            if (kit != null) for (ActiveAbility a : kit) {
                abilities.put(a.effect(), a);
                cooldowns.put(a.effect(), 0); // pronto no round 1
            }
        }

        boolean has(AbilityEffect e)   { return abilities.containsKey(e); }
        boolean ready(AbilityEffect e) { return has(e) && cooldowns.getOrDefault(e, 0) <= 0; }
        void    trigger(AbilityEffect e) { cooldowns.put(e, abilities.get(e).cooldown()); }
        int     mag(AbilityEffect e)   { return abilities.get(e).magnitude(); }
        /** ATK efetivo (com Berserk ativo). */
        int     effAtk() {
            if (berserkRounds > 0 && has(AbilityEffect.ATK_BUFF_LOW))
                return (int) Math.round(atk * (1 + mag(AbilityEffect.ATK_BUFF_LOW) / 100.0));
            return atk;
        }
    }

    /** d20 roll >= this threshold = critical hit. LUK expands window down from 20. */
    public static int critThreshold(int luk) {
        return Math.max(17, 20 - (luk / 15)); // 0 luk=20, 15=19, 30=18, 45+=17 (cap 20%)
    }

    /**
     * Mitigação de dano por % (Combate V2): {@code dano = ATK × 100/(100+DEF)}, mínimo 1.
     */
    public static int mitigatedDamage(int atk, int def) {
        int d = Math.max(0, def);
        return Math.max(1, (int) Math.round(atk * 100.0 / (100 + d)));
    }

    /** Nota de elemento no log do golpe: ✨ super eficaz (×1.25), 🛡 resistido (×0.75), nada se neutro. [ELEMENTOS] */
    private static String elementNote(double mult) {
        if (mult > 1.0) return " ✨ super effective";
        if (mult < 1.0) return " 🛡 resisted";
        return "";
    }
}
