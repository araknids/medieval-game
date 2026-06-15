package com.medieval.game.service;

import com.medieval.game.enums.Element;
import com.medieval.game.service.BattleSimulator.Combatant;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * [GUERRA_GAUNTLET] Guerra de território 15v15 lutada em ONDAS de 3v3 (Modelo B).
 *
 * Regra (escolhida com o jogador — docs/PLANO_GUERRA_GAUNTLET.md):
 *  - Cada lado tem uma FILA de até 15; o campo comporta 3.
 *  - Uma ONDA = um 3v3 até a morte de TODOS os 3 de um lado.
 *  - O lado DERROTADO (campo zerado) repõe 3 frescos da fila.
 *  - O VENCEDOR mantém os sobreviventes (1–3) com o HP que sobrou — NÃO repõe (pode seguir curto).
 *  - Acaba quando um time perde os 15 (campo vazio E fila vazia) → o outro vence.
 *
 * 3v3: ordem de iniciativa por AGI (desempate DEX); cada um foca o inimigo vivo de MENOR HP
 * (cercar/flanquear). Golpe reusa a matemática do {@link BattleSimulator}: acerto {@code hitChance},
 * crit {@code critChance} (×1.5), mitigação {@code mitigatedDamage}, elementos ±25%.
 *
 * Eventos próprios ({@link WarEvent}, com side/wave) — não toca no {@code BattleEvent} 1v1.
 * v1: SEM habilidades ativas e SEM kiting (o duelo 1v1 segue completo) — adicionar depois.
 */
@Component
public class GauntletWarSimulator {

    private static final double CRIT_MULT = 1.5;   // igual ao BattleSimulator [REBALANCE]
    private static final int    FIELD     = 3;     // 3 em campo por lado
    private static final int    ROUND_CAP = 80;    // trava anti-loop por onda (sudden death se estourar)
    private static final String[] HIT_ZONES = {"head", "body", "legs"};

    public static final int SIDE_ATTACKER = 0;
    public static final int SIDE_DEFENDER = 1;

    /** Evento do replay da guerra (mesmos campos do BattleEvent + {@code side}/{@code wave}). */
    public record WarEvent(int round, String type, String actor, String target,
                           int damage, int targetHp, int targetMaxHp,
                           String element, String hitZone, int side, int wave) {}

    /** Resultado: quem venceu + eventos (replay) + log + HP final por índice de entrada (p/ persistir). */
    public record WarOutcome(boolean attackersWon, List<WarEvent> events, List<String> log,
                             int[] finalHpAttackers, int[] finalHpDefenders) {}

    /** Combatente + seu índice na lista de entrada (p/ mapear o HP final de volta sem depender do nome). */
    private record Entry(Combatant c, int idx) {}

    /** Estado mutável de um lutador em campo. */
    private static final class F {
        final String name;
        final int atk, def, dex, agi, luk, maxHp, side, idx;
        final Element weapon, armor;
        int hp;
        F(Combatant c, int side, int idx) {
            this.name = c.name(); this.atk = c.atk(); this.def = c.def();
            this.dex = c.dex(); this.agi = c.agi(); this.luk = c.luk();
            this.hp = c.hp(); this.maxHp = c.hp();   // entra CHEIO (HP atual = máximo no spawn)
            this.weapon = c.weapon(); this.armor = c.armor(); this.side = side; this.idx = idx;
        }
    }

    /** Resolve a guerra inteira. attackers/defenders = filas de até 15 (ordem = prioridade de entrada). */
    public WarOutcome resolve(String attackersName, List<Combatant> attackers,
                              String defendersName, List<Combatant> defenders) {
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        Deque<Entry> qA = new ArrayDeque<>();
        for (int i = 0; i < attackers.size(); i++) qA.add(new Entry(attackers.get(i), i));
        Deque<Entry> qB = new ArrayDeque<>();
        for (int i = 0; i < defenders.size(); i++) qB.add(new Entry(defenders.get(i), i));
        List<F> fieldA = new ArrayList<>();
        List<F> fieldB = new ArrayList<>();
        List<F> allA = new ArrayList<>();   // todos que entraram (p/ o HP final)
        List<F> allB = new ArrayList<>();
        List<WarEvent> events = new ArrayList<>();
        List<String> log = new ArrayList<>();

        int wave = 1;
        log.add("⚔ " + attackersName + " vs " + defendersName + " — guerra 15v15 em ondas de 3v3");
        fill(fieldA, qA, SIDE_ATTACKER, events, wave, allA);
        fill(fieldB, qB, SIDE_DEFENDER, events, wave, allB);

        boolean attackersWon;
        while (true) {
            resolveWave(fieldA, fieldB, events, log, wave, rng);
            boolean aElim = fieldA.isEmpty() && qA.isEmpty();
            boolean bElim = fieldB.isEmpty() && qB.isEmpty();
            if (aElim || bElim) {
                attackersWon = bElim && !aElim;   // empate (ambos zerados) → defensor segura o território
                break;
            }
            // Próxima onda: SÓ o perdedor (campo vazio) repõe; o vencedor mantém os sobreviventes (Modelo B).
            wave++;
            events.add(new WarEvent(0, "wave", null, null, 0, 0, 0, null, null, -1, wave));
            if (fieldA.isEmpty()) fill(fieldA, qA, SIDE_ATTACKER, events, wave, allA);
            if (fieldB.isEmpty()) fill(fieldB, qB, SIDE_DEFENDER, events, wave, allB);
        }

        String winner = attackersWon ? attackersName : defendersName;
        String loser  = attackersWon ? defendersName : attackersName;
        log.add("🏆 " + winner + " venceu a guerra! (ondas: " + wave + ")");
        events.add(new WarEvent(0, "victory", winner, loser, 0, 0, 0, null, null,
                attackersWon ? SIDE_ATTACKER : SIDE_DEFENDER, wave));

        // HP final por índice: não-entrantes ficam cheios; entrantes com o que sobrou (0 = morreu).
        int[] fhA = new int[attackers.size()];
        for (int i = 0; i < attackers.size(); i++) fhA[i] = attackers.get(i).hp();
        for (F f : allA) fhA[f.idx] = Math.max(0, f.hp);
        int[] fhB = new int[defenders.size()];
        for (int i = 0; i < defenders.size(); i++) fhB[i] = defenders.get(i).hp();
        for (F f : allB) fhB[f.idx] = Math.max(0, f.hp);
        return new WarOutcome(attackersWon, events, log, fhA, fhB);
    }

    /** Repõe o campo até FIELD com frescos da fila; emite um spawn por entrante e registra em `all`. */
    private void fill(List<F> field, Deque<Entry> queue, int side, List<WarEvent> events, int wave, List<F> all) {
        while (field.size() < FIELD && !queue.isEmpty()) {
            Entry e = queue.poll();
            F f = new F(e.c(), side, e.idx());
            field.add(f);
            all.add(f);
            events.add(new WarEvent(0, "spawn", f.name, null, 0, f.hp, f.maxHp, null, null, side, wave));
        }
    }

    /** Uma onda: luta até um lado zerar o campo (ou cap de rodadas → morte súbita por HP total). */
    private void resolveWave(List<F> fieldA, List<F> fieldB, List<WarEvent> events,
                             List<String> log, int wave, Random rng) {
        for (int round = 1; round <= ROUND_CAP && !fieldA.isEmpty() && !fieldB.isEmpty(); round++) {
            List<F> order = new ArrayList<>(fieldA.size() + fieldB.size());
            order.addAll(fieldA);
            order.addAll(fieldB);
            order.sort(Comparator.<F>comparingInt(f -> f.agi).reversed()
                    .thenComparing(Comparator.<F>comparingInt(f -> f.dex).reversed()));
            for (F a : order) {
                if (a.hp <= 0) continue;                       // morreu nesta rodada
                List<F> enemies = a.side == SIDE_ATTACKER ? fieldB : fieldA;
                F target = lowestHp(enemies);
                if (target == null) break;                     // sem inimigos vivos
                strike(a, target, events, log, round, wave, rng);
            }
            fieldA.removeIf(f -> f.hp <= 0);
            fieldB.removeIf(f -> f.hp <= 0);
        }
        // Cap estourou com os dois lados vivos → morte súbita: o lado com MENOS HP total cai.
        if (!fieldA.isEmpty() && !fieldB.isEmpty()) {
            int hpA = fieldA.stream().mapToInt(f -> f.hp).sum();
            int hpB = fieldB.stream().mapToInt(f -> f.hp).sum();
            boolean aWins = hpA >= hpB;
            List<F> winners = aWins ? fieldA : fieldB;
            List<F> losers  = aWins ? fieldB : fieldA;
            String killer = winners.get(0).name;
            for (F f : losers) {
                f.hp = 0;
                events.add(new WarEvent(ROUND_CAP, "attack", killer, f.name, f.maxHp, 0, f.maxHp, null, "body", f.side, wave));
            }
            losers.clear();
        }
    }

    /** Um golpe de {@code a} em {@code t} (acerto/crit/mitigação/elemento). Emite evento + log. */
    private void strike(F a, F t, List<WarEvent> events, List<String> log, int round, int wave, Random rng) {
        int roll = rng.nextInt(20) + 1;
        if (roll == 1) {   // fumble (natural 1)
            events.add(new WarEvent(round, "miss", a.name, t.name, 0, t.hp, t.maxHp, null, null, a.side, wave));
            return;
        }
        int hitPct = BattleSimulator.hitChance(a.dex, t.agi);
        boolean isCrit = rng.nextInt(100) < BattleSimulator.critChance(a.luk);   // crit fura a esquiva
        boolean hit = isCrit || rng.nextInt(100) < hitPct;
        if (!hit) {
            events.add(new WarEvent(round, "miss", a.name, t.name, 0, t.hp, t.maxHp, null, null, a.side, wave));
            return;
        }
        double elemMult = Element.multiplier(a.weapon, t.armor);
        int dmg = Math.max(1, (int) Math.round(BattleSimulator.mitigatedDamage(a.atk, t.def) * elemMult));
        if (isCrit) dmg = Math.max(1, (int) Math.round(dmg * CRIT_MULT));
        t.hp -= dmg;
        int after = Math.max(0, t.hp);
        String elemStr = elemMult > 1.0 ? "SUPER" : elemMult < 1.0 ? "RESIST" : null;
        String hitZone = HIT_ZONES[rng.nextInt(HIT_ZONES.length)];
        events.add(new WarEvent(round, isCrit ? "crit" : "attack", a.name, t.name, dmg, after, t.maxHp, elemStr, hitZone, a.side, wave));
        log.add("  " + a.name + (isCrit ? " CRITA " : " acerta ") + t.name + " (-" + dmg + ") ❤ " + after + "/" + t.maxHp);
    }

    /** Inimigo vivo de MENOR HP atual (foco de fogo). null se não há vivos. */
    private F lowestHp(List<F> field) {
        F best = null;
        for (F f : field) if (f.hp > 0 && (best == null || f.hp < best.hp)) best = f;
        return best;
    }
}
