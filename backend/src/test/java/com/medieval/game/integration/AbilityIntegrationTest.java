package com.medieval.game.integration;

import com.medieval.game.enums.AbilityEffect;
import com.medieval.game.enums.ClassAbility;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.AbilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Habilidades: aprender (cap/classe/pontos), passivas no combate, loadout das ativas, respec. [HABILIDADES]
@DisplayName("Abilities | aprender + passivas + loadout + respec")
class AbilityIntegrationTest extends BaseIntegrationTest {

    @Autowired AbilityService    abilityService;
    @Autowired PlayerRepository  playerRepository;
    @Autowired WarriorRepository warriorRepository;

    private Player reload(Player p) { return playerRepository.findById(p.getId()).orElseThrow(); }
    private Warrior warriorOf(Player p) { return warriorRepository.findByPlayer(reload(p)).orElseThrow(); }

    private Player newPlayer(String prefix, WarriorClass clazz, int points) {
        String u = uniqueUser(prefix);
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        p.addBronzeAmount(2000);
        p = playerRepository.save(p);
        Warrior w = new Warrior();
        w.setName("W_" + u); w.setWarriorClass(clazz); w.setPlayer(p);
        w.setLevel(20); w.setAbilityPoints(points);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
        return p;
    }

    @Test
    @DisplayName("Aprender sobe o nível e gasta 1 ponto")
    void learn_incrementsAndSpends() {
        Player p = newPlayer("ab", WarriorClass.WARRIOR, 3);
        abilityService.learn(reload(p), ClassAbility.TOUGHNESS);
        abilityService.learn(reload(p), ClassAbility.TOUGHNESS);
        assertThat(abilityService.levels(warriorOf(p)).get(ClassAbility.TOUGHNESS)).isEqualTo(2);
        assertThat(warriorOf(p).getAbilityPoints()).isEqualTo(1);
    }

    @Test
    @DisplayName("Não aprende habilidade de outra classe nem sem pontos")
    void learn_guards() {
        Player warrior = newPlayer("ab", WarriorClass.WARRIOR, 3);
        assertThatThrownBy(() -> abilityService.learn(reload(warrior), ClassAbility.EAGLE_EYE)) // arquearia
                .isInstanceOf(IllegalStateException.class);

        Player broke = newPlayer("ab", WarriorClass.WARRIOR, 0);
        assertThatThrownBy(() -> abilityService.learn(reload(broke), ClassAbility.TOUGHNESS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Passiva soma no bônus de combate (Toughness lv3 = +36 HP)")
    void passiveStatBonus() {
        Player p = newPlayer("ab", WarriorClass.WARRIOR, 5);
        abilityService.learn(reload(p), ClassAbility.TOUGHNESS);
        abilityService.learn(reload(p), ClassAbility.TOUGHNESS);
        abilityService.learn(reload(p), ClassAbility.TOUGHNESS);
        assertThat(abilityService.passiveStatBonus(warriorOf(p))[2]).isEqualTo(36); // hp
    }

    @Test
    @DisplayName("Loadout das ativas carrega efeito + cooldown + magnitude do nível")
    void activeLoadout() {
        Player p = newPlayer("ab", WarriorClass.WARRIOR, 5);
        abilityService.learn(reload(p), ClassAbility.SHIELD_BASH);
        abilityService.learn(reload(p), ClassAbility.SHIELD_BASH); // lv2 → 8+4×2 = 16
        var kit = abilityService.activeLoadout(warriorOf(p));
        assertThat(kit).hasSize(1);
        assertThat(kit.get(0).effect()).isEqualTo(AbilityEffect.BONUS_DAMAGE);
        assertThat(kit.get(0).cooldown()).isEqualTo(5);
        assertThat(kit.get(0).magnitude()).isEqualTo(16);
    }

    @Test
    @DisplayName("Respec devolve os pontos, zera as habilidades e cobra bronze")
    void respec_refundsAndCharges() {
        Player p = newPlayer("ab", WarriorClass.WARRIOR, 4);
        abilityService.learn(reload(p), ClassAbility.TOUGHNESS);
        abilityService.learn(reload(p), ClassAbility.WEAPON_MASTERY);
        long bronzeBefore = reload(p).totalBronze();

        abilityService.respec(reload(p));

        Warrior w = warriorOf(p);
        assertThat(w.getAbilityPoints()).isEqualTo(4);                 // 2 livres + 2 devolvidos
        assertThat(abilityService.levels(w)).isEmpty();
        assertThat(reload(p).totalBronze()).isEqualTo(bronzeBefore - 500);
    }
}
