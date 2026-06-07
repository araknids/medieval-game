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
 * desequilibrado fica um x1 (win%, HP restante, dano máximo, taxa de timeout).
 *
 * <p>Sem gear, sem habilidades, sem elementos — isola o efeito da DISTRIBUIÇÃO DE ATRIBUTOS.
 * Pontos por nível = (nível-1)×2. As asserções são frouxas (sanidade); o VALOR é o relatório
 * impresso no stdout do surefire. Rode com: {@code mvn -q test -Dtest=CombatBalanceProbeTest}.
 */
class CombatBalanceProbeTest {

    private static final BattleSimulator SIM = new BattleSimulator();
    private static final Pattern DMG = Pattern.compile("\\[-(\\d+) HP");
    private static final int N = 4000; // sims por matchup (par/ímpar alterna quem ataca primeiro)

    // ── Construção de build ─────────────────────────────────────────────────────

    /** Array de combate [atk, def, hp, dex, strBonus, luk] a partir de classe + atributos. */
    private static int[] stats(WarriorClass c, int str, int dex, int con, int luk) {
        return new int[]{ c.baseAttack + str, c.baseDefense, c.baseHealth + con * 8, dex, str / 20, luk };
    }

    private record Build(String label, WarriorClass cls, int str, int dex, int con, int luk) {
        int[] arr() { return stats(cls, str, dex, con, luk); }
        int ac()    { return 10 + dex; }
        int atk()   { return cls.baseAttack + str; }
        int hp()    { return cls.baseHealth + con * 8; }
        int toHit() { return str / 20; }
        String line() {
            return String.format("%-22s STR%-3d DEX%-3d CON%-3d LUK%-3d  | ATK %-3d DEF %-2d HP %-4d AC %-3d +hit %d crit@%d",
                    label, str, dex, con, luk, atk(), cls.baseDefense, hp(), ac(), toHit(),
                    BattleSimulator.critThreshold(luk));
        }
    }

    /**
     * Distribui (nível-1)×2 pontos seguindo uma ordem de prioridade; sobra vai pra CON (cap infinito).
     * order: 0=STR, 1=DEX, 2=LUK, 3=INT(desperdiçado em combate).
     */
    private static Build alloc(String label, WarriorClass c, int level, int... order) {
        int p = Math.max(0, (level - 1) * 2);
        int[] caps = { c.strCap, c.dexCap, c.lukCap, c.intCap };
        int[] v = new int[4];
        for (int s : order) { int put = Math.min(caps[s], p); v[s] = put; p -= put; }
        int con = p; // sobra → CON
        return new Build(label, c, v[0], v[1], con, v[2]); // v[3] = INT, jogado fora
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
            BattleSimulator.BattleOutcome o = SIM.simulateDetailed(
                    first.label, f[0], f[1], f[2], f[3], f[4], f[5],
                    second.label, s[0], s[1], s[2], s[3], s[4], s[5], false);
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
        out.append("╔══════════════════════════════════════════════════════════════════════════╗\n");
        out.append("║  SONDA DE BALANCE — BattleSimulator real, sem gear/skills/elementos        ║\n");
        out.append("║  ").append(N).append(" duelos por matchup, iniciativa alternada, desempate por %HP         ║\n");
        out.append("╚══════════════════════════════════════════════════════════════════════════╝\n");

        int[] levels = { 10, 30, 50 };

        // ── 1) PIOR vs MELHOR caso upando (mesma classe WARRIOR, mesmo nível) ──────
        out.append("\n■ 1) MESMO NÍVEL, MESMA CLASSE (Warrior) — pior build vs melhor build\n");
        out.append("     Mostra quanto um x1 fica desequilibrado SÓ pela escolha de atributos.\n");
        for (int lvl : levels) {
            WarriorClass W = WarriorClass.WARRIOR;
            Build glass  = alloc("MaxSTR (glass)",  W, lvl, 0, 2);          // STR cap, LUK cap, resto CON
            Build bruiser= alloc("Bruiser (STR+DEX)",W, lvl, 0, 1);          // STR cap, DEX cap, resto CON
            Build dodge  = alloc("DodgeTank (DEX)",  W, lvl, 1, 2);          // DEX cap, LUK cap, resto CON
            Build noob   = alloc("Noob (só CON)",    W, lvl);                // tudo CON, 0 ofensiva
            Build wasted = alloc("Pior (dump INT)",  W, lvl, 3);            // INT cap, resto CON
            List<Build> builds = List.of(glass, bruiser, dodge, noob, wasted);

            out.append("\n  ── Nível ").append(lvl).append("  (").append((lvl - 1) * 2).append(" pontos) ──\n");
            for (Build b : builds) out.append("    ").append(b.line()).append("\n");
            out.append(String.format("\n    %-20s", "win% (linha vs col)"));
            for (Build col : builds) out.append(String.format(" %-10s", shortName(col.label)));
            out.append("\n");
            int worstGapMaxHit = 0;
            for (Build row : builds) {
                out.append(String.format("    %-20s", shortName(row.label)));
                for (Build col : builds) {
                    if (row == col) { out.append(String.format(" %-10s", "  —")); continue; }
                    Match m = run(row, col, N);
                    worstGapMaxHit = Math.max(worstGapMaxHit, m.maxHit());
                    out.append(String.format(" %-10s", String.format("%.0f%%", m.aWinPct())));
                }
                out.append("\n");
            }
            // destaque: melhor vs pior (Bruiser vs Pior-INT) com detalhe
            Match hl = run(bruiser, wasted, N);
            out.append(String.format("    → Bruiser vs Pior(INT): %.0f%% win | vencedor sai com %.0f%% HP | timeout %.0f%% | maior golpe visto no nível: %d%n",
                    hl.aWinPct(), hl.winnerHpPct(), hl.timeoutPct(), worstGapMaxHit));
        }

        // ── 2) A "PAREDE DE AC" (DEX) — desequilíbrio estrutural ──────────────────
        out.append("\n■ 2) PAREDE DE AC — DEX deixa o alvo só-crittável (acerto normal = d20 + STR/20 ≤ 24)\n");
        out.append("     AC = 10 + DEX. Pra acertar normal precisa de roll+bônus ≥ AC. Crit ignora AC.\n");
        WarriorClass W = WarriorClass.WARRIOR;
        for (int dex : new int[]{0, 10, 14, 15, 20, 30}) {
            // atacante padrão L50 MaxSTR (ATK 95, +hit 4, crit@18); defensor = só esse DEX + CON
            Build atkr = alloc("L50 MaxSTR", W, 50, 0, 2);
            Build def  = new Build("DEX " + dex, W, 0, dex, 40, 0);
            Match m = run(atkr, def, N);
            int ac = 10 + dex;
            boolean onlyCrit = (20 + atkr.toHit()) < ac;
            out.append(String.format("    DEX %-3d (AC %-3d) %-14s atacante L50 vence %.0f%%, timeout %.0f%%%n",
                    dex, ac, onlyCrit ? "[SÓ CRIT]" : "[acerta normal]", m.aWinPct(), m.timeoutPct()));
        }

        // ── 3) CROSS-CLASS no nível 50 (melhor build de cada) — triângulo ─────────
        out.append("\n■ 3) CROSS-CLASS no Lv50 (build otimizado de cada classe) — checa o triângulo\n");
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
                if (row == col) { out.append(String.format(" %-10s", "  —")); continue; }
                Match m = run(row, col, N);
                out.append(String.format(" %-10s", String.format("%.0f%%", m.aWinPct())));
            }
            out.append("\n");
        }

        // ── 4) DANO MÁXIMO por golpe ──────────────────────────────────────────────
        out.append("\n■ 4) DANO MÁXIMO POR GOLPE (sem elemento; ×1.25 se vantagem elemental)\n");
        Build maxAtk = alloc("L50 MaxSTR", W, 50, 0, 2); // ATK 95
        Build softTarget = new Build("alvo DEF base", W, 0, 0, 40, 0);
        Match dmgM = run(maxAtk, softTarget, N);
        int mit = BattleSimulator.mitigatedDamage(maxAtk.atk(), WarriorClass.WARRIOR.baseDefense);
        out.append(String.format("    ATK %d vs DEF %d → golpe normal %d, CRÍTICO %d (×2). Maior observado: %d%n",
                maxAtk.atk(), WarriorClass.WARRIOR.baseDefense, mit, mit * 2, dmgM.maxHit()));
        out.append(String.format("    Com vantagem elemental no crit: ~%d. HP típico L50: %d–%d → crit tira %.0f%%–%.0f%% da vida.%n",
                (int) Math.round(mit * 2 * 1.25), softTarget.hp(), best(WarriorClass.WARRIOR, 50).hp(),
                100.0 * mit * 2 / softTarget.hp(), 100.0 * mit * 2 / best(WarriorClass.WARRIOR, 50).hp()));

        System.out.println(out);

        // sanidade frouxa: as simulações resolvem e produzem números válidos
        Match sanity = run(warOpt, merOpt, 200);
        assertTrue(sanity.aWinPct() >= 0 && sanity.aWinPct() <= 100, "win% deve estar em [0,100]");
        assertTrue(dmgM.maxHit() > 0, "deve haver dano registrado");
    }

    /** "Melhor" build por classe: foca o stat forte da classe + DEX pra AC, resto CON. */
    private static Build best(WarriorClass c, int level) {
        return switch (c) {
            case ARCHER   -> alloc("Archer Opt",   c, level, 1, 2, 0); // DEX, LUK, STR
            case MERCHANT -> alloc("Merchant Opt", c, level, 0, 2, 1); // STR, LUK, DEX
            default       -> alloc("Warrior Opt",  c, level, 0, 1, 2); // STR, DEX, LUK
        };
    }

    private static String shortName(String label) {
        int sp = label.indexOf(' ');
        return sp < 0 ? label : label.substring(0, sp);
    }
}
