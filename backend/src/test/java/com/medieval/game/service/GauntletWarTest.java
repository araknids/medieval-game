package com.medieval.game.service;

import com.medieval.game.service.BattleSimulator.Combatant;
import com.medieval.game.service.GauntletWarSimulator.WarOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** [GUERRA_GAUNTLET] Invariantes do gauntlet 15v15 em ondas de 3v3 (Modelo B). RNG → testa estrutura, não placar exato. */
class GauntletWarTest {

    private final GauntletWarSimulator sim = new GauntletWarSimulator();

    /** stats = [atk, def, hp, dex, agi, luk] */
    private List<Combatant> team(String prefix, int n, int[] stats) {
        List<Combatant> t = new ArrayList<>();
        for (int i = 1; i <= n; i++)
            t.add(Combatant.of(prefix + i, stats, null, null, List.of()));
        return t;
    }

    private long spawns(WarOutcome o, int side) {
        return o.events().stream().filter(e -> "spawn".equals(e.type()) && e.side() == side).count();
    }

    private String lastType(WarOutcome o) {
        return o.events().get(o.events().size() - 1).type();
    }

    @Test @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void atacantesVarremQuandoMuitoMaisFortes() {
        var strong = team("A", 15, new int[]{120, 60, 300, 50, 40, 25});
        var weak = team("D", 15, new int[]{10, 5, 50, 5, 5, 0});
        WarOutcome o = sim.resolve("Atacantes", strong, "Defensores", weak);
        assertTrue(o.attackersWon(), "lado muito mais forte deve vencer");
        assertEquals("victory", lastType(o));
        assertEquals(15, spawns(o, GauntletWarSimulator.SIDE_DEFENDER), "perdedor é consumido por inteiro");
        assertTrue(spawns(o, GauntletWarSimulator.SIDE_ATTACKER) <= 15);
    }

    @Test @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void defensoresVencemQuandoMaisFortes() {
        var weak = team("A", 15, new int[]{10, 5, 50, 5, 5, 0});
        var strong = team("D", 15, new int[]{120, 60, 300, 50, 40, 25});
        WarOutcome o = sim.resolve("Atacantes", weak, "Defensores", strong);
        assertFalse(o.attackersWon());
        assertEquals(15, spawns(o, GauntletWarSimulator.SIDE_ATTACKER), "perdedor (atacante) usa os 15");
    }

    @Test @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void terminaEPerdedorConsomeOs15() {
        var a = team("A", 15, new int[]{40, 20, 120, 20, 15, 10});
        var b = team("D", 15, new int[]{40, 20, 120, 20, 15, 10});
        WarOutcome o = sim.resolve("Atacantes", a, "Defensores", b);
        assertEquals("victory", lastType(o));
        int loserSide = o.attackersWon() ? GauntletWarSimulator.SIDE_DEFENDER : GauntletWarSimulator.SIDE_ATTACKER;
        assertEquals(15, spawns(o, loserSide), "o lado eliminado usa os 15");
        assertTrue(o.events().stream().anyMatch(e -> "wave".equals(e.type())), "times parelhos → várias ondas (houve reposição)");
    }

    @Test @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void trioForteVarreMantendoSobreviventes() {   // Modelo B: vencedor não repõe
        var attackers = team("A", 3, new int[]{220, 120, 600, 60, 50, 30});
        attackers.addAll(team("Aw", 12, new int[]{10, 5, 50, 5, 5, 0})); // banco fraco que não precisa entrar
        var medium = team("D", 15, new int[]{35, 20, 110, 20, 15, 10});
        WarOutcome o = sim.resolve("Atacantes", attackers, "Defensores", medium);
        assertTrue(o.attackersWon());
        assertEquals(15, spawns(o, GauntletWarSimulator.SIDE_DEFENDER));
        assertTrue(spawns(o, GauntletWarSimulator.SIDE_ATTACKER) < 15,
                "o trio forte segura sem precisar do banco inteiro (Modelo B)");
    }
}
