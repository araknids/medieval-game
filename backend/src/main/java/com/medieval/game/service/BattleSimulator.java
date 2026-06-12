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
 * D20-based combat simulator. [REBALANCE]
 *
 * Acerto por golpe [REBALANCE v2]: chance CONTÍNUA {@code hitChance(DEX_atk, AGI_def)} — cada ponto
 * de DEX/AGI conta (sem os degraus /5 e /8 que deixavam pontos "mortos"). Não há mais AC.
 * DEX = acerto, AGI = esquiva (defensor) + golpes extra (atacante), LUK = crit, STR = só dano (ATK).
 * Crit = chance CONTÍNUA {@code critChance(LUK)} — fura a esquiva e dá ×1.5. Natural 1 = fumble. Fortune Save (LUK).
 * AGI ofensivo: chance de golpe extra = {@code clamp(0,90,(AGI_atk−AGI_def)×1.5)} por round.
 * Elementos (roda RPS) aplicam ±25% por golpe. [ELEMENTOS]
 *
 * Habilidades ATIVAS [HABILIDADES]: cada lado leva um kit de {@link ActiveAbility} (effect +
 * cooldown fixo + magnitude já calculada do nível). O loop dispara no cooldown e escreve no log.
 * Kit vazio = combate idêntico ao anterior.
 */
@Component
public class BattleSimulator {

    /** [REBALANCE v2] Acerto-base com DEX=AGI=0 (cada ponto de DEX/AGI ajusta a partir daqui). Clamp 20–95%. */
    private static final int HIT_BASE = 50;
    private static final int HIT_MIN  = 20;
    private static final int HIT_MAX  = 95;
    /** [REBALANCE] Cada ponto de AGI a mais que o inimigo = +1% de chance de um golpe extra (cap 75%). */
    private static final double EXTRA_PER_AGI = 1.0;
    private static final int    EXTRA_CAP     = 75;
    /** [REBALANCE] Multiplicador do crítico (era 2.0 — matava de um golpe no nível alto). */
    private static final double CRIT_MULT     = 1.5;

    // [KITING] Arqueiro (ranged) vs corpo-a-corpo (melee): quando o melee "fecha a distância", o arqueiro
    // atira de perto com dano reduzido e depois PERDE um turno recuando pra reabrir espaço.
    /** Chance-base do melee colar no arqueiro num round (ajustada por AGI/3: melee rápido cola mais). */
    private static final int    MELEE_CLOSE_CHANCE = 60;
    /** Dano do "tiro de perto" do arqueiro encurralado (× no golpe). */
    private static final double ARCHER_CLOSE_DMG   = 0.5;

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
    /** [BATALHA_ANIMADA] Zona de cada BODY_PARTS (por índice) → "head"|"body"|"legs" p/ a animação. */
    private static final String[] BODY_ZONE = {
        "head", "body", "body", "head", "body", "body", "legs", "body", "body", "head",
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

    // [I18N] Sorteia um texto do array e o resolve no idioma do request (combat.<prefix><i>; EN = o
    // literal). Messages.tr é estático → new BattleSimulator() nos probes segue ok (MS null → EN).
    private static String pick(String[] arr, String prefix, Random rng) {
        int i = rng.nextInt(arr.length);
        return Messages.tr(prefix + i, arr[i]);
    }

    /** Rich battle outcome: log + winner flag + final HP of both fighters (for ambush HP carry). */
    public record BattleOutcome(List<String> log, List<BattleEvent> events, boolean firstWon, int firstHpFinal, int secondHpFinal) {}

    /** [BATALHA_ANIMADA] Evento estruturado p/ o replay animado (metadado de máquina — NÃO traduzido).
     *  type: spawn|attack|crit|miss|dodge|extra|volley|heal|berserk|backpedal|pointblank|pinned|victory.
     *  hitZone: head|body|legs (null se não-golpe). element: SUPER|RESIST|null. */
    public record BattleEvent(int round, String type, String actor, String target,
                              int damage, int targetHp, int targetMaxHp,
                              String element, String hitZone) {}

    /** Habilidade ativa pronta p/ o simulador: efeito, cooldown (rounds) e magnitude (já do nível). [HABILIDADES] */
    public record ActiveAbility(AbilityEffect effect, int cooldown, int magnitude) {}

    /** Lutador completo (stats + elementos + ativas + ranged). stats = [atk, def, hp, dex, agi, luk]. */
    public record Combatant(String name, int atk, int def, int hp, int dex, int agi, int luk,
                            Element weapon, Element armor, List<ActiveAbility> abilities, boolean ranged) {
        public static Combatant of(String name, int[] s, Element weapon, Element armor, List<ActiveAbility> abilities) {
            return of(name, s, weapon, armor, abilities, false);
        }
        /** [KITING] ranged=true p/ Arqueiro (arco) — sofre/aplica a dinâmica de distância vs melee. */
        public static Combatant of(String name, int[] s, Element weapon, Element armor, List<ActiveAbility> abilities, boolean ranged) {
            return new Combatant(name, s[0], s[1], s[2], s[3], s[4], s[5], weapon, armor,
                    abilities != null ? abilities : List.of(), ranged);
        }
    }

    // ── Wrappers compatíveis ───────────────────────────────────────────────────

    /** Backwards-compatible wrapper — returns just the log lines. */
    public List<String> simulate(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cAgi, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oAgi, int oLuk) {
        return simulateDetailed(
                cName, cAtk, cDef, cHp, cDex, cAgi, cLuk,
                oName, oAtk, oDef, oHp, oDex, oAgi, oLuk).log();
    }

    /** PvP default: no timeout (40 rounds), desempate por %HP restante. [COMBATE_V2] */
    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cAgi, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oAgi, int oLuk) {
        return simulateDetailed(cName, cAtk, cDef, cHp, cDex, cAgi, cLuk,
                                oName, oAtk, oDef, oHp, oDex, oAgi, oLuk, false);
    }

    /**
     * @param firstLosesOnTimeout PvE: se ninguém morrer em 40 rounds, o 1º combatente PERDE.
     */
    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cAgi, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oAgi, int oLuk,
            boolean firstLosesOnTimeout) {
        return simulateDetailed(cName, cAtk, cDef, cHp, cDex, cAgi, cLuk,
                oName, oAtk, oDef, oHp, oDex, oAgi, oLuk,
                firstLosesOnTimeout, false, false);
    }

    /** [KITING] Variante raw com flags ranged (Arqueiro). NPCs/melee = false. */
    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cAgi, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oAgi, int oLuk,
            boolean firstLosesOnTimeout, boolean cRanged, boolean oRanged) {
        return simulateDetailed(cName, cAtk, cDef, cHp, cDex, cAgi, cLuk,
                oName, oAtk, oDef, oHp, oDex, oAgi, oLuk,
                firstLosesOnTimeout, null, null, null, null, List.of(), List.of(), cRanged, oRanged);
    }

    /** Versão com ELEMENTOS (sem habilidades ativas). [ELEMENTOS] */
    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cAgi, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oAgi, int oLuk,
            boolean firstLosesOnTimeout,
            Element cWeapon, Element cArmor, Element oWeapon, Element oArmor) {
        return simulateDetailed(cName, cAtk, cDef, cHp, cDex, cAgi, cLuk,
                oName, oAtk, oDef, oHp, oDex, oAgi, oLuk,
                firstLosesOnTimeout, cWeapon, cArmor, oWeapon, oArmor, List.of(), List.of(), false, false);
    }

    /** Conveniência: dois {@link Combatant} (stats + elementos + ativas + ranged). [HABILIDADES] */
    public BattleOutcome simulate(Combatant a, Combatant b, boolean firstLosesOnTimeout) {
        return simulateDetailed(
                a.name(), a.atk(), a.def(), a.hp(), a.dex(), a.agi(), a.luk(),
                b.name(), b.atk(), b.def(), b.hp(), b.dex(), b.agi(), b.luk(),
                firstLosesOnTimeout, a.weapon(), a.armor(), b.weapon(), b.armor(),
                a.abilities(), b.abilities(), a.ranged(), b.ranged());
    }

    // ── Núcleo ──────────────────────────────────────────────────────────────────

    public BattleOutcome simulateDetailed(
            String cName, int cAtk, int cDef, int cHp, int cDex, int cAgi, int cLuk,
            String oName, int oAtk, int oDef, int oHp, int oDex, int oAgi, int oLuk,
            boolean firstLosesOnTimeout,
            Element cWeapon, Element cArmor, Element oWeapon, Element oArmor,
            List<ActiveAbility> cAbilities, List<ActiveAbility> oAbilities,
            boolean cRanged, boolean oRanged) {

        List<String> log = new ArrayList<>();
        List<BattleEvent> events = new ArrayList<>(); // [BATALHA_ANIMADA] eventos do replay (ao lado do log)
        Random rng = java.util.concurrent.ThreadLocalRandom.current();

        Side c = new Side(cName, cAtk, cDef, cHp, cDex, cAgi, cLuk, cWeapon, cArmor, cAbilities, cRanged);
        Side o = new Side(oName, oAtk, oDef, oHp, oDex, oAgi, oLuk, oWeapon, oArmor, oAbilities, oRanged);

        log.add(Messages.tr("combat.begins", "⚔ {0} vs {1} — The battle begins!", c.name, o.name)); // [I18N]
        log.add(Messages.tr("combat.hpline", "HP: [{0}: ❤ {1}] | [{2}: ❤ {3}]", c.name, c.maxHp, o.name, o.maxHp));
        log.add("─────────────────────────");
        // [BATALHA_ANIMADA] spawn dos dois lutadores (o front desenha as barras de HP a partir do maxHp).
        events.add(new BattleEvent(0, "spawn", c.name, null, 0, c.maxHp, c.maxHp, null, null));
        events.add(new BattleEvent(0, "spawn", o.name, null, 0, o.maxHp, o.maxHp, null, null));

        for (int round = 1; round <= 40 && c.hp > 0 && o.hp > 0; round++) {
            log.add(Messages.tr("combat.round", "— Round {0} —", round)); // [I18N]

            applySelfTriggers(c, log, events, round);
            applySelfTriggers(o, log, events, round);

            attackRound(c, o, HIT_TEXTS, log, rng, events, round);
            if (o.hp <= 0 || c.hp <= 0) { tick(c); tick(o); break; }

            attackRound(o, c, ENEMY_HIT_TEXTS, log, rng, events, round);

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
        log.add(Messages.tr("combat.victoryline", "🏆 {0} {1}", winner, pick(VICTORY_TEXTS, "combat.victory.", rng))); // [I18N]
        log.add("WINNER:" + winner + "|LOSER:" + loser); // tag de máquina (parseada) — NÃO traduzir
        events.add(new BattleEvent(0, "victory", winner, loser, 0, 0, 0, null, null)); // [BATALHA_ANIMADA]
        return new BattleOutcome(log, events, cWon, Math.max(0, c.hp), Math.max(0, o.hp));
    }

    /** Gatilhos de auto-buff/cura no início do round (Berserk, Second Wind). [HABILIDADES] */
    private void applySelfTriggers(Side s, List<String> log, List<BattleEvent> events, int round) {
        if (s.hp <= 0) return;
        // Second Wind: 1×/luta, cura ao cair abaixo de 30%.
        if (s.has(AbilityEffect.HEAL_LOW) && !s.secondWindUsed && s.hp < s.maxHp * 0.30) {
            int heal = Math.max(1, (int) Math.round(s.maxHp * s.mag(AbilityEffect.HEAL_LOW) / 100.0));
            s.hp = Math.min(s.maxHp, s.hp + heal);
            s.secondWindUsed = true;
            log.add(Messages.tr("combat.secondwind", "  ❤ {0} uses Second Wind — heals +{1} HP! ({2}/{3})", s.name, heal, s.hp, s.maxHp)); // [I18N]
            events.add(new BattleEvent(round, "heal", s.name, s.name, heal, s.hp, s.maxHp, null, null)); // [BATALHA_ANIMADA]
        }
        // Berserk: ao cair abaixo de 50%, +ATK% por 3 rounds (respeita cooldown).
        if (s.has(AbilityEffect.ATK_BUFF_LOW) && s.berserkRounds <= 0 && s.ready(AbilityEffect.ATK_BUFF_LOW)
                && s.hp < s.maxHp * 0.50) {
            s.berserkRounds = 3;
            s.trigger(AbilityEffect.ATK_BUFF_LOW);
            log.add(Messages.tr("combat.berserk", "  🔥 {0} enters a Berserk rage! +{1}% ATK", s.name, s.mag(AbilityEffect.ATK_BUFF_LOW))); // [I18N]
            events.add(new BattleEvent(round, "berserk", s.name, s.name, 0, s.hp, s.maxHp, null, null)); // [BATALHA_ANIMADA]
        }
    }

    /** Ação do atacante no round: kiting do arqueiro + golpe base + golpe EXTRA por AGI. [REBALANCE][KITING] */
    private void attackRound(Side atk, Side def, String[] hitTexts, List<String> log, Random rng, List<BattleEvent> events, int round) {
        double dmgMult = 1.0;
        // [KITING] Arqueiro (ranged) encurralado por um corpo-a-corpo (melee).
        if (atk.ranged && !def.ranged) {
            if (atk.pinned == 1) {            // recuando: PERDE o turno pra reabrir espaço
                atk.pinned = 0;
                log.add(Messages.tr("combat.backpedal", "  🏃 {0} backpedals to open up space — no clean shot this round.", atk.name)); // [I18N]
                events.add(new BattleEvent(round, "backpedal", atk.name, null, 0, atk.hp, atk.maxHp, null, null)); // [BATALHA_ANIMADA]
                return;
            } else if (atk.pinned == 2) {     // tiro de perto: dano REDUZIDO
                dmgMult = ARCHER_CLOSE_DMG;
                atk.pinned = 1;
                log.add(Messages.tr("combat.pointblank", "  🎯 {0} is forced into a point-blank shot — reduced power.", atk.name)); // [I18N]
                events.add(new BattleEvent(round, "pointblank", atk.name, def.name, 0, def.hp, def.maxHp, null, null)); // [BATALHA_ANIMADA]
            }
        }

        attack(atk, def, hitTexts, log, rng, dmgMult, events, round);
        if (def.hp <= 0 || atk.hp <= 0) return;

        int chance = (int) Math.round((atk.agi - def.agi) * EXTRA_PER_AGI);
        if (chance > EXTRA_CAP) chance = EXTRA_CAP;
        if (chance > 0 && rng.nextInt(100) < chance) {
            log.add(Messages.tr("combat.extrastrike", "  💨 {0} moves with blinding speed — an extra strike!", atk.name)); // [I18N]
            events.add(new BattleEvent(round, "extra", atk.name, def.name, 0, def.hp, def.maxHp, null, null)); // [BATALHA_ANIMADA]
            attack(atk, def, hitTexts, log, rng, dmgMult, events, round);
            if (def.hp <= 0 || atk.hp <= 0) return;
        }

        // [KITING] Um melee cola no arqueiro à distância (AGI: melee rápido cola mais, arqueiro ágil escapa).
        if (!atk.ranged && def.ranged && def.pinned == 0) {
            int close = Math.max(20, Math.min(85, MELEE_CLOSE_CHANCE + (atk.agi - def.agi) / 3));
            if (rng.nextInt(100) < close) {
                def.pinned = 2;
                log.add(Messages.tr("combat.pinned", "  ⚔ {0} closes the distance — {1} is pinned in melee range!", atk.name, def.name)); // [I18N]
                events.add(new BattleEvent(round, "pinned", atk.name, def.name, 0, def.hp, def.maxHp, null, null)); // [BATALHA_ANIMADA]
            }
        }
    }

    /** Um ataque de {@code atk} em {@code def}, com elementos + habilidades ativas. {@code dmgMult} = penalidade de kiting. */
    private void attack(Side atk, Side def, String[] hitTexts, List<String> log, Random rng, double dmgMult, List<BattleEvent> events, int round) {
        int roll = rng.nextInt(20) + 1;
        boolean precise = atk.ready(AbilityEffect.GUARANTEED_CRIT); // Precise Shot: hit + crit garantidos

        if (roll == 1 && !precise) {
            log.add(Messages.tr("combat.fumbleline", "  {0} {1}", atk.name, pick(FUMBLE_TEXTS, "combat.fumble.", rng))); // [I18N]
            events.add(new BattleEvent(round, "miss", atk.name, def.name, 0, def.hp, def.maxHp, null, null)); // [BATALHA_ANIMADA]
            return;
        }

        // [REBALANCE v2] Acerto CONTÍNUO: cada ponto de DEX (acerto) e AGI (esquiva) conta — sem os
        // degraus /5 e /8 que matavam pontos. Crit (LUK) = chance contínua e SEMPRE fura a esquiva.
        int hitPct = hitChance(atk.dex, def.agi);
        boolean isCrit = rng.nextInt(100) < atk.critChance;
        if (isCrit && !precise && def.fortuneSave > 0 && rng.nextInt(100) < def.fortuneSave) {
            isCrit = false;
            log.add(Messages.tr("combat.fortunesave", "  ✨ {0} gets a Fortune Save — critical negated!", def.name)); // [I18N]
        }

        boolean hit = precise || isCrit || rng.nextInt(100) < hitPct;
        if (!hit) {
            log.add(Messages.tr("combat.missline", "  {0} {1} [hit {2}% — DEX {3} vs AGI {4}]", // [I18N]
                    atk.name, pick(MISS_TEXTS, "combat.miss.", rng), hitPct, atk.dex, def.agi));
            events.add(new BattleEvent(round, "miss", atk.name, def.name, 0, def.hp, def.maxHp, null, null)); // [BATALHA_ANIMADA]
            return;
        }

        // Evasive Roll do DEFENSOR anula este golpe + reflete dano. [HABILIDADES]
        if (def.ready(AbilityEffect.DODGE_INCOMING)) {
            def.trigger(AbilityEffect.DODGE_INCOMING);
            int reflect = def.mag(AbilityEffect.DODGE_INCOMING);
            atk.hp -= reflect;
            log.add(Messages.tr("combat.evasive", "  🌀 {0} rolls aside — dodges the blow and reflects {1} damage!", def.name, reflect)); // [I18N]
            events.add(new BattleEvent(round, "dodge", def.name, atk.name, reflect, Math.max(0, atk.hp), atk.maxHp, null, null)); // [BATALHA_ANIMADA]
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
        String elemStr = elemMult > 1.0 ? "SUPER" : elemMult < 1.0 ? "RESIST" : null; // [BATALHA_ANIMADA]

        // Shield Bash: dano bônus no golpe. [HABILIDADES]
        String bashTag = "";
        if (atk.ready(AbilityEffect.BONUS_DAMAGE)) {
            int bonus = atk.mag(AbilityEffect.BONUS_DAMAGE);
            dmg += bonus;
            atk.trigger(AbilityEffect.BONUS_DAMAGE);
            bashTag = " 💥+" + bonus;
        }
        dmg += preciseBonus;
        if (isCrit) dmg = Math.max(1, (int) Math.round(dmg * CRIT_MULT)); // [REBALANCE] crit ×1.5 (era ×2)
        if (dmgMult != 1.0) dmg = Math.max(1, (int) Math.round(dmg * dmgMult)); // [KITING] tiro de perto = dano reduzido

        int defAfter = Math.max(0, def.hp - dmg);
        int bpIdx = rng.nextInt(BODY_PARTS.length);                          // [BATALHA_ANIMADA] mesmo consumo de RNG do pick()
        String bodyPart = Messages.tr("combat.body." + bpIdx, BODY_PARTS[bpIdx]); // [I18N]
        String hitZone  = BODY_ZONE[bpIdx];                                  // head|body|legs p/ o evento
        if (isCrit) {
            // [I18N] {3}=def.name reusado; {5}=note {6}=bashTag {7}=preciseTag (fragmentos), {8}/{9}=HP
            log.add(Messages.tr("combat.critline", "  💥 {0} {1} {2} of {3}! [-{4} HP{5}{6}{7}] {3} ❤ {8}/{9}",
                    atk.name, pick(CRIT_TEXTS, "combat.crit.", rng), bodyPart, def.name, dmg, note, bashTag, preciseTag, defAfter, def.maxHp));
            events.add(new BattleEvent(round, "crit", atk.name, def.name, dmg, defAfter, def.maxHp, elemStr, hitZone)); // [BATALHA_ANIMADA]
        } else {
            String hitKey = (hitTexts == HIT_TEXTS) ? "combat.hit." : "combat.enemyhit."; // [I18N] prefixo por array
            log.add(Messages.tr("combat.hitline", "  {0} {1} {2} of {3}! [-{4} HP{5}{6}] {3} ❤ {7}/{8}",
                    atk.name, pick(hitTexts, hitKey, rng), bodyPart, def.name, dmg, note, bashTag, defAfter, def.maxHp));
            events.add(new BattleEvent(round, "attack", atk.name, def.name, dmg, defAfter, def.maxHp, elemStr, hitZone)); // [BATALHA_ANIMADA]
        }
        def.hp -= dmg;

        // Volley: um ataque extra a X% do dano. [HABILIDADES]
        if (def.hp > 0 && atk.ready(AbilityEffect.EXTRA_ATTACK)) {
            atk.trigger(AbilityEffect.EXTRA_ATTACK);
            int extra = Math.max(1, (int) Math.round(
                    mitigatedDamage(atk.effAtk(), def.def) * elemMult * dmgMult * atk.mag(AbilityEffect.EXTRA_ATTACK) / 100.0));
            int after = Math.max(0, def.hp - extra);
            log.add(Messages.tr("combat.volley", "  ☄ {0} looses a Volley — extra hit! [-{1} HP] {2} ❤ {3}/{4}", atk.name, extra, def.name, after, def.maxHp)); // [I18N]
            events.add(new BattleEvent(round, "volley", atk.name, def.name, extra, after, def.maxHp, elemStr, hitZone)); // [BATALHA_ANIMADA]
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
        final int atk, def, dex, agi, luk, critChance, fortuneSave, maxHp;
        final boolean ranged;     // [KITING] Arqueiro (arco)
        final Element weapon, armor;
        final Map<AbilityEffect, ActiveAbility> abilities = new EnumMap<>(AbilityEffect.class);
        final Map<AbilityEffect, Integer> cooldowns = new EnumMap<>(AbilityEffect.class);
        int hp;
        int berserkRounds = 0;
        boolean secondWindUsed = false;
        // [KITING] 0 = à distância (tiro cheio); 2 = encurralado (tiro de perto fraco); 1 = recuando (perde o turno).
        int pinned = 0;

        Side(String name, int atk, int def, int hp, int dex, int agi, int luk,
             Element weapon, Element armor, List<ActiveAbility> kit, boolean ranged) {
            this.name = name; this.atk = atk; this.def = def; this.dex = dex;
            this.agi = agi; this.luk = luk; this.weapon = weapon; this.armor = armor;
            this.maxHp = hp; this.hp = hp; this.ranged = ranged;
            this.critChance = critChance(luk);
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

    /**
     * [REBALANCE v2] Chance de crítico (%) — CONTÍNUA por ponto de LUK (sem o degrau do d20 antigo, que
     * desperdiçava LUK acima de 45). {@code 5 + LUK/2}, cap 35%. Ex.: 0→5, 30→20, 50→30, 100→35.
     * Faz da build de crit um eixo real (antes LUK era dump-stat). Crit fura a esquiva e dá ×{@value #CRIT_MULT}.
     */
    public static int critChance(int luk) {
        return Math.max(5, Math.min(35, 5 + luk / 2));
    }

    /**
     * [REBALANCE v2] Chance de acerto (%) — CONTÍNUA: cada ponto de DEX (acerto) e AGI do defensor
     * (esquiva) conta, sem os degraus /5 e /8 antigos (pontos "mortos"). {@code 50 + DEX − AGI×0.6},
     * clamp 20–95%. Crit ignora isto (sempre acerta).
     */
    public static int hitChance(int dexAtk, int agiDef) {
        return Math.max(HIT_MIN, Math.min(HIT_MAX, HIT_BASE + dexAtk - (agiDef * 3) / 5));
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
        if (mult > 1.0) return Messages.tr("combat.super_effective", " ✨ super effective"); // [I18N]
        if (mult < 1.0) return Messages.tr("combat.resisted", " 🛡 resisted");
        return "";
    }
}
