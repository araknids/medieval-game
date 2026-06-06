package com.medieval.game.integration;

import com.medieval.game.enums.ActivityRole;
import com.medieval.game.enums.Element;
import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.enums.Zone;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.ZoneActivity;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.TempleService;
import com.medieval.game.service.ZoneService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Encantamento (Templo) consome essência + 1h; áreas de elemento dropam essência. [ELEMENTOS]
@DisplayName("Elementos | encantar (Templo) + drop de essência nas áreas")
class ElementIntegrationTest extends BaseIntegrationTest {

    @Autowired TempleService     templeService;
    @Autowired ZoneService       zoneService;
    @Autowired GatheringService  gatheringService;
    @Autowired PlayerRepository  playerRepository;
    @Autowired WarriorRepository warriorRepository;

    private Player reload(Player p) { return playerRepository.findById(p.getId()).orElseThrow(); }

    private Player newPlayerWithWarrior(String prefix, int level) {
        String u = uniqueUser(prefix);
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        p.addBronzeAmount(5000);
        p = playerRepository.save(p);
        Warrior w = new Warrior();
        w.setName("W_" + u); w.setWarriorClass(WarriorClass.WARRIOR); w.setPlayer(p);
        w.setLevel(level); w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
        return p;
    }

    private Warrior warriorOf(Player p) { return warriorRepository.findByPlayer(reload(p)).orElseThrow(); }

    // ── Encantar consome essência + bronze e seta o elemento por 1h ──
    @Test
    @DisplayName("Encantar arma consome 1 essência + bronze e ativa o elemento")
    void enchantWeapon_consumesEssenceAndActivates() {
        Player p = newPlayerWithWarrior("elw", 10);
        gatheringService.addResource(reload(p), ResourceType.FIRE_ESSENCE, 2);
        long essBefore  = gatheringService.resourceQuantity(reload(p), ResourceType.FIRE_ESSENCE);
        long bronzeBefore = reload(p).totalBronze();

        templeService.enchantWeapon(reload(p), Element.FIRE);

        Warrior w = warriorOf(p);
        assertThat(w.getActiveWeaponElement()).isEqualTo(Element.FIRE);
        assertThat((long) gatheringService.resourceQuantity(reload(p), ResourceType.FIRE_ESSENCE)).isEqualTo(essBefore - 1);
        assertThat(reload(p).totalBronze()).isEqualTo(bronzeBefore - 100);
    }

    @Test
    @DisplayName("Encantar sem essência é rejeitado")
    void enchant_rejectsWithoutEssence() {
        Player p = newPlayerWithWarrior("elw", 10);
        assertThatThrownBy(() -> templeService.enchantArmor(reload(p), Element.WATER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Encantamento expirado não conta como ativo")
    void expiredEnchant_isInactive() {
        Player p = newPlayerWithWarrior("elw", 10);
        Warrior w = warriorOf(p);
        w.setWeaponElement(Element.FIRE);
        w.setWeaponElementUntil(LocalDateTime.now().minusMinutes(1)); // já expirou
        warriorRepository.save(w);
        assertThat(warriorOf(p).getActiveWeaponElement()).isNull();
    }

    // ── Área de elemento dropa a essência ──
    @Test
    @DisplayName("Farmar uma área de elemento dropa a essência daquele elemento")
    void elementArea_dropsEssence() {
        Player p = newPlayerWithWarrior("ele", 10);
        // Guerreiro forte → sobrevive a qualquer NPC da zona SAFE e coleta normalmente.
        Warrior w = warriorOf(p);
        w.setAttack(500); w.setStrength(800); w.setConstitution(200);
        warriorRepository.save(w);

        ZoneActivity act = zoneService.enter(reload(p), Zone.SAFE, ActivityRole.GATHERING,
                SkillType.FISHING, 20, Kingdom.FISHING, Element.FIRE);
        zoneService.collect(reload(p), act.getId());

        assertThat((long) gatheringService.resourceQuantity(reload(p), ResourceType.FIRE_ESSENCE))
                .isGreaterThanOrEqualTo(1);
    }
}
