package com.medieval.game.service;

import com.medieval.game.enums.WarriorClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sonda de BALANCE (não é teste de regressão): roda o {@link BattleSimulator} REAL com builds
 * diferentes no MESMO nível pra medir "pior caso upando vs melhor caso upando" e quão
 * desequilibrado fica um x1 (win%, HP restante, dano máximo, taxa de timeout). [REBALANCE]
 *
 * <p>Modelo novo: STR=dano, DEX=acerto, AGI=golpes extra+esquiva, LUK=crit (×1.5), CON=HP.
 * Sem gear/skills/elementos — isola a DISTRIBUIÇÃO DE ATRIBUTOS. Pontos por nível = (nível-1)×2.
 * Asserções frouxas (sanidade); o VALOR é o relatório no stdout. Rode com:
 * {@code mvn -q test -Dtest=CombatBalanceProbeTest}.
 */
class CombatBalanceProbeTest {

    private static final BattleSimulator SIM = new BattleSimulator();
    private static final Pattern DMG = Pattern.compile("\\[-(\\d+) HP");
    private static final int N = 4000;

    // ── Construção de build ─────────────────────────────────────────────────────

    /** [REBALANCE] dano da classe: Archer escala com DEX, resto com STR (espelha getTotalBaseAttack). */
    private static int dmgAttr(WarriorClass c, int str, int dex) {
        return c == WarriorClass.ARCHER ? dex : str;
    }

    /** [atk, def, hp, dex, agi, luk] a partir de classe + atributos. */
    private static int[] stats(WarriorClass c, int str, int dex, int con, int agi, int luk) {
        return new int[]{ c.baseAttack + dmgAttr(c, str, dex), c.baseDefense, c.baseHealth + con * 8, dex, agi, luk };
    }

    private record Build(String label, WarriorClass cls, int str, int dex, int con, int agi, int luk) {
        int[] arr() { return stats(cls, str, dex, con, agi, luk); }
        int atk()   { return cls.baseAttack + dmgAttr(cls, str, dex); }
        int hp()    { return cls.baseHealth + con * 8; }
        String line() {
            return String.format("%-22s STR%-3d DEX%-3d AGI%-3d CON%-3d LUK%-3d | ATK %-3d HP %-4d acc+%d dodge-%d crit@%d",
                    label, str, dex, agi, con, luk, atk(), hp(), dex / 5, agi / 8,
                    BattleSimulator.critThreshold(luk));
        }
    }

    /**
     * Distribui (nível-1)×2 pontos por prioridade; sobra vai pra CON (cap infinito).
     * order: 0=STR, 1=DEX, 2=AGI, 3=LUK, 4=INT(desperdiçado em combate).
     */
    private static Build alloc(String label, WarriorClass c, int level, int... order) {
        int p = Math.max(0, (level - 1) * 2);
        int[] caps = { c.strCap, c.dexCap, c.agiCap, c.lukCap, c.intCap };
        int[] v = new int[5];
        for (int s : order) { int put = Math.min(caps[s], p); v[s] = put; p -= put; }
        int con = p; // sobra → CON
        return new Build(label, c, v[0], v[1], con, v[2], v[3]); // v[4] = INT, jogado fora
    }

    // ── Runner ──────────────────────────────────────────────────────────────────

    private record Match(double aWinPct, double winnerHpPct, double timeoutPct, int maxHit) {}

    private static Match run(Build a, Build b, int n) {
        int aWins = 0, timeouts = 0, maxHit = 0;
        double hpSum = 0;
        for (int i = 0; i < n; i++) {
            boolean aFirst = (i % 2 == 0); // alterna pra anular vantagem de iniciativa
            Build first = aFirst ? a : b, second = aFirst ? b : a;
            int[] f = first.arr(), s = second.arr();
            boolean fRanged = first.cls() == WarriorClass.ARCHER, sRanged = second.cls() == WarriorClass.ARCHER;
            BattleSimulator.BattleOutcome o = SIM.simulateDetailed(
                    first.label, f[0], f[1], f[2], f[3], f[4], f[5],
                    second.label, s[0], s[1], s[2], s[3], s[4], s[5], false, fRanged, sRanged);
            boolean aWon = aFirst == o.firstWon();
            if (aWon) aWins++;
            int fHp = o.firstHpFinal(), sHp = o.secondHpFinal();
            if (fHp > 0 && sHp > 0) timeouts++;
            int winHp  = o.firstWon() ? fHp : sHp;
            int winMax = o.firstWon() ? f[2] : s[2];
            hpSum += 100.0 * winHp / winMax;
            for (String ln : o.log()) {
                Matcher m = DMG.matcher(ln);
                while (m.find()) maxHit = Math.max(maxHit, Integer.parseInt(m.group(1)));
            }
        }
        return new Match(100.0 * aWins / n, hpSum / n, 100.0 * timeouts / n, maxHit);
    }

    // ── Relatório ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sonda de balance: pior vs melhor build upando + x1 desequilibrado + dano máximo")
    void balanceProbe() {
        StringBuilder out = new StringBuilder("\n");
        out.append("==============================================================================\n");
        out.append("  SONDA DE BALANCE [REBALANCE] - BattleSimulator real, sem gear/skills/elementos\n");
        out.append("  ").append(N).append(" duelos/matchup, iniciativa alternada, desempate por %HP\n");
        out.append("  STR=dano DEX=acerto AGI=golpes+esquiva LUK=crit(x1.5) CON=HP\n");
        out.append("==============================================================================\n");

        int[] levels = { 10, 30, 50 };

        // ── 1) PIOR vs MELHOR caso upando (mesma classe WARRIOR, mesmo nível) ──────
        out.append("\n# 1) MESMO NIVEL, MESMA CLASSE (Warrior) - pior build vs melhor build\n");
        for (int lvl : levels) {
            WarriorClass W = WarriorClass.WARRIOR;
            Build dmg   = alloc("MaxSTR (dano)",   W, lvl, 0, 1);    // STR cap, DEX cap, resto CON
            Build brui  = alloc("Bruiser",         W, lvl, 0, 2, 1); // STR, AGI, DEX, resto CON
            Build agi   = alloc("AgileCrit",       W, lvl, 2, 3, 1); // AGI, LUK, DEX, resto CON
            Build noob  = alloc("Noob (so CON)",   W, lvl);          // tudo CON, 0 ofensiva
            Build waste = alloc("Pior (dump INT)", W, lvl, 4);       // INT cap, resto CON
            List<Build> builds = List.of(dmg, brui, agi, noob, waste);

            out.append("\n  -- Nivel ").append(lvl).append("  (").append((lvl - 1) * 2).append(" pontos) --\n");
            for (Build b : builds) out.append("    ").append(b.line()).append("\n");
            out.append(String.format("%n    %-20s", "win% (linha vs col)"));
            for (Build col : builds) out.append(String.format(" %-10s", shortName(col.label)));
            out.append("\n");
            int maxHit = 0;
            for (Build row : builds) {
                out.append(String.format("    %-20s", shortName(row.label)));
                for (Build col : builds) {
                    if (row == col) { out.append(String.format(" %-10s", "  -")); continue; }
                    Match m = run(row, col, N);
                    maxHit = Math.max(maxHit, m.maxHit());
                    out.append(String.format(" %-10s", String.format("%.0f%%", m.aWinPct())));
                }
                out.append("\n");
            }
            Match hl = run(brui, waste, N);
            out.append(String.format("    -> Bruiser vs Pior(INT): %.0f%% win | vencedor com %.0f%% HP | timeout %.0f%% | maior golpe no nivel: %d%n",
                    hl.aWinPct(), hl.winnerHpPct(), hl.timeoutPct(), maxHit));
        }

        // ── 2) ACERTO (DEX) vs ESQUIVA (AGI) — sem mais "parede" ──────────────────
        out.append("\n# 2) ACERTO x ESQUIVA - atacante L50 MaxSTR (ATK 95) vs alvo so com AGI+CON\n");
        out.append("     acerto = d20 + DEX/5 - AGI_def/8 >= 11. Crit (LUK) sempre fura a esquiva.\n");
        WarriorClass W = WarriorClass.WARRIOR;
        Build attacker = alloc("L50 MaxSTR+DEX", W, 50, 0, 1); // STR80, DEX30 (acc +6)
        for (int agi : new int[]{0, 10, 20, 25}) {
            Build def = new Build("AGI " + agi, W, 0, 0, 40, agi, 0);
            Match m = run(attacker, def, N);
            out.append(String.format("    alvo AGI %-3d (dodge -%d) -> atacante vence %.0f%%, timeout %.0f%%%n",
                    agi, agi / 8, m.aWinPct(), m.timeoutPct()));
        }

        // ── 3) CROSS-CLASS no nível 50 (melhor build de cada) — triângulo ─────────
        out.append("\n# 3) CROSS-CLASS no Lv50 (build otimizado de cada classe)\n");
        Build warOpt = best(WarriorClass.WARRIOR, 50);
        Build arcOpt = best(WarriorClass.ARCHER, 50);
        Build merOpt = best(WarriorClass.MERCHANT, 50);
        List<Build> classes = List.of(warOpt, arcOpt, merOpt);
        for (Build b : classes) out.append("    ").append(b.line()).append("\n");
        out.append(String.format("%n    %-14s", ""));
        for (Build col : classes) out.append(String.format(" %-10s", col.cls.displayName));
        out.append("\n");
        for (Build row : classes) {
            out.append(String.format("    %-14s", row.cls.displayName));
            for (Build col : classes) {
                if (row == col) { out.append(String.format(" %-10s", "  -")); continue; }
                Match m = run(row, col, N);
                out.append(String.format(" %-10s", String.format("%.0f%%", m.aWinPct())));
            }
            out.append("\n");
        }

        // ── 4) DANO MÁXIMO por golpe (crit ×1.5) ───────────────────────────────────
        out.append("\n# 4) DANO MAXIMO POR GOLPE (crit x1.5; +25% se vantagem elemental)\n");
        Build maxAtk = alloc("L50 MaxSTR", W, 50, 0, 1);
        Build soft   = new Build("alvo DEF base", W, 0, 0, 40, 0, 0);
        Match dmgM = run(maxAtk, soft, N);
        int mit = BattleSimulator.mitigatedDamage(maxAtk.atk(), WarriorClass.WARRIOR.baseDefense);
        int crit = (int) Math.round(mit * 1.5);
        out.append(String.format("    ATK %d vs DEF %d -> golpe normal %d, CRITICO %d (x1.5). Maior observado: %d%n",
                maxAtk.atk(), WarriorClass.WARRIOR.baseDefense, mit, crit, dmgM.maxHit()));
        out.append(String.format("    HP de uma build ofensiva L50: %d -> crit tira %.0f%% (antes x2 = one-shot).%n",
                best(WarriorClass.WARRIOR, 50).hp(), 100.0 * crit / best(WarriorClass.WARRIOR, 50).hp()));

        System.out.println(out);

        Match sanity = run(warOpt, merOpt, 200);
        assertTrue(sanity.aWinPct() >= 0 && sanity.aWinPct() <= 100, "win% deve estar em [0,100]");
        assertTrue(dmgM.maxHit() > 0, "deve haver dano registrado");
    }

    /** "Melhor" build por classe: stat forte + acerto/esquiva, resto CON. */
    private static Build best(WarriorClass c, int level) {
        return switch (c) {
            case ARCHER   -> alloc("Archer Opt",   c, level, 1, 2, 3);    // DEX (dano+acerto), AGI, LUK
            case MERCHANT -> alloc("Merchant Opt", c, level, 0, 1, 3, 2); // STR, DEX, LUK, AGI
            default       -> alloc("Warrior Opt",  c, level, 0, 1, 2);    // STR, DEX, AGI (depois resto CON)
        };
    }

    private static String shortName(String label) {
        int sp = label.indexOf(' ');
        return sp < 0 ? label : label.substring(0, sp);
    }
}
