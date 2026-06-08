package com.medieval.game.service;

import com.medieval.game.service.BattleSimulator.BattleEvent;
import com.medieval.game.service.BattleSimulator.BattleOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

// [BATALHA_ANIMADA] O BattleSimulator emite eventos estruturados (BattleOutcome.events) PARALELOS
// ao log de texto — sem alterar o combate. Base do replay animado no canvas.
@DisplayName("BATALHA_ANIMADA | BattleSimulator emite eventos estruturados")
class BattleEventTest {

    private final BattleSimulator sim = new BattleSimulator();

    @Test
    @DisplayName("spawn(2) + ataques com hitZone válido + victory; log de texto preservado")
    void emitsStructuredEvents() {
        // Hero domina → o Goblin morre (vencedor determinístico).
        BattleOutcome out = sim.simulateDetailed(
                "Hero",   80, 40, 1500, 10, 5, 5,
                "Goblin",  5,  0,   40,  0, 0, 0);

        // O log de TEXTO continua existindo (eventos são paralelos, não substituem). [BATALHA_ANIMADA]
        assertThat(out.log()).isNotEmpty();
        assertThat(out.log().get(out.log().size() - 1)).startsWith("WINNER:");

        List<BattleEvent> ev = out.events();
        assertThat(ev).isNotEmpty();

        // 2 spawns (um por lutador), cada um com maxHp coerente.
        List<BattleEvent> spawns = ev.stream().filter(e -> e.type().equals("spawn")).toList();
        assertThat(spawns).hasSize(2);
        assertThat(spawns).allSatisfy(s -> assertThat(s.targetMaxHp()).isPositive());

        // 1 victory, com o vencedor.
        List<BattleEvent> victory = ev.stream().filter(e -> e.type().equals("victory")).toList();
        assertThat(victory).hasSize(1);
        assertThat(victory.get(0).actor()).isEqualTo("Hero");

        // Golpes: hitZone sempre em head|body|legs; dano > 0; targetHp dentro do range.
        Set<String> ZONES = Set.of("head", "body", "legs");
        List<BattleEvent> hits = ev.stream()
                .filter(e -> e.type().equals("attack") || e.type().equals("crit")).toList();
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(h -> {
            assertThat(ZONES).contains(h.hitZone());
            assertThat(h.damage()).isPositive();
            assertThat(h.targetHp()).isBetween(0, h.targetMaxHp());
        });

        // O golpe fatal no Goblin aparece como um hit com targetHp == 0.
        assertThat(hits).anySatisfy(h -> assertThat(h.targetHp()).isZero());
    }

    @RepeatedTest(5)
    @DisplayName("Todo evento é bem-formado (round>=0, tipo conhecido, actor não-vazio)")
    void eventsWellFormed() {
        BattleOutcome out = sim.simulateDetailed(
                "A", 30, 15, 200, 10, 5, 8,
                "B", 25, 12, 180,  8, 4, 6);
        Set<String> TYPES = Set.of("spawn", "attack", "crit", "miss", "dodge", "extra", "volley",
                                   "heal", "berserk", "backpedal", "pointblank", "pinned", "victory");
        assertThat(out.events()).isNotEmpty();
        assertThat(out.events()).allSatisfy(e -> {
            assertThat(e.round()).isGreaterThanOrEqualTo(0);
            assertThat(TYPES).contains(e.type());
            assertThat(e.actor()).isNotBlank();
        });
    }
}
