package com.medieval.game.service;

import com.medieval.game.enums.AbilityEffect;
import com.medieval.game.service.BattleSimulator.ActiveAbility;
import com.medieval.game.service.BattleSimulator.Combatant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Habilidades ATIVAS disparam no loop do BattleSimulator (markers no log). [HABILIDADES]
@DisplayName("BattleSimulator | habilidades ativas disparam no combate")
class BattleSimulatorAbilityTest {

    private final BattleSimulator sim = new BattleSimulator();

    // Atacante que sempre acerta (strBonus alto, sem crit) vs um saco de pancada que aguenta 40 rounds.
    private Combatant attacker(List<ActiveAbility> kit) {
        return Combatant.of("Hero", new int[]{50, 10, 300, 0, 5, 0}, null, null, kit);
    }
    private Combatant dummy() {
        return Combatant.of("Dummy", new int[]{1, 0, 100000, 0, 0, 0}, null, null, List.of());
    }

    private String logOf(List<ActiveAbility> kit) {
        return String.join("\n", sim.simulate(attacker(kit), dummy(), false).log());
    }

    @Test
    @DisplayName("Shield Bash adiciona dano bônus (marker 💥+)")
    void shieldBash_triggers() {
        String log = logOf(List.of(new ActiveAbility(AbilityEffect.BONUS_DAMAGE, 5, 20)));
        assertThat(log).contains("💥+");
    }

    @Test
    @DisplayName("Precise Shot dá crítico garantido (marker 🎯)")
    void preciseShot_triggers() {
        String log = logOf(List.of(new ActiveAbility(AbilityEffect.GUARANTEED_CRIT, 4, 30)));
        assertThat(log).contains("🎯");
    }

    @Test
    @DisplayName("Volley dá um ataque extra (marker ☄)")
    void volley_triggers() {
        String log = logOf(List.of(new ActiveAbility(AbilityEffect.EXTRA_ATTACK, 5, 80)));
        assertThat(log).contains("☄");
    }

    @Test
    @DisplayName("Sem kit de ativas, nenhum marker aparece")
    void noAbilities_noMarkers() {
        String log = logOf(List.of());
        assertThat(log).doesNotContain("💥+").doesNotContain("🎯").doesNotContain("☄");
    }
}
