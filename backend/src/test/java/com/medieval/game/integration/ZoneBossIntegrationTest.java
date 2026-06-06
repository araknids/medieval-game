package com.medieval.game.integration;

import com.medieval.game.enums.ActivityRole;
import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.enums.Zone;
import com.medieval.game.enums.ZoneActivityStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.ZoneActivity;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.repository.ZoneActivityRepository;
import com.medieval.game.service.ZoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// TC-226 a TC-228 — [ZONA_CHEFE] Chefe errante: fugir / encarar. O roll aleatório do chefe fica OFF nos
// testes (app.zone.boss-enabled=false), então forçamos o estado BOSS_PENDING e exercitamos resolveBoss*.
@DisplayName("TC-226-228 | Zone — Chefe errante (fugir/encarar)")
class ZoneBossIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository       playerRepository;
    @Autowired WarriorRepository      warriorRepository;
    @Autowired ZoneActivityRepository activityRepository;
    @Autowired ZoneService            zoneService;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("boss"));
    }

    private Player playerOf(String prefix) {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith(prefix))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .orElseThrow();
    }

    /** Cria uma expedição já travada num chefe (BOSS_PENDING) no nível pedido. */
    private ZoneActivity bossPending(Player player, Zone zone, int bossLevel) {
        ZoneActivity act = zoneService.enter(player, zone, ActivityRole.GATHERING, SkillType.FISHING, 30);
        act.setStatus(ZoneActivityStatus.BOSS_PENDING);
        act.setBossLevel(bossLevel);
        act.setBossName("Escaped Tower Warden");
        return activityRepository.save(act);
    }

    private Warrior crusher(Player player) {
        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
        w.setWarriorClass(WarriorClass.WARRIOR);
        w.setLevel(30);
        w.setAttack(2000); w.setDefense(2000); w.setHealth(5000);
        w.setStrength(800); w.setConstitution(200);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        return warriorRepository.save(w);
    }

    // ── TC-226: Encarar um chefe fraco → vitória, loot garantido, expedição COMPLETED ──
    @Test
    @DisplayName("TC-226 | Fight a weak boss → win + guaranteed loot + COMPLETED")
    void tc226_fightWeakBoss_winsAndLoots() {
        Player player = playerOf("boss");
        crusher(player);
        ZoneActivity act = bossPending(player, Zone.SAFE, 1); // chefe nível 1 vs warrior esmagador

        ZoneService.CollectResult r =
                zoneService.resolveBossFight(playerRepository.findById(player.getId()).orElseThrow(), act.getId());

        assertThat(r.survived()).isTrue();
        assertThat(r.wasAttacked()).isTrue();
        assertThat(r.lootItemName()).isNotBlank();                       // item garantido
        ZoneActivity after = activityRepository.findById(act.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ZoneActivityStatus.COMPLETED);
    }

    // ── TC-227: Encarar um chefe esmagador → derrota, DEFEATED + KO ──
    @Test
    @DisplayName("TC-227 | Fight an overwhelming boss → defeat + DEFEATED + KO")
    void tc227_fightStrongBoss_loses() {
        Player player = playerOf("boss");
        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
        w.setWarriorClass(WarriorClass.WARRIOR);
        w.setLevel(20); // mínimo p/ entrar na vermelha; stats baixos garantem a derrota
        w.setAttack(1); w.setDefense(1); w.setHealth(10);
        w.setStrength(1); w.setConstitution(1);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
        ZoneActivity act = bossPending(player, Zone.HIGH_RISK, 60); // chefe nível 60 vs warrior fraco

        ZoneService.CollectResult r =
                zoneService.resolveBossFight(playerRepository.findById(player.getId()).orElseThrow(), act.getId());

        assertThat(r.survived()).isFalse();
        ZoneActivity after = activityRepository.findById(act.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ZoneActivityStatus.DEFEATED);
        Warrior ko = warriorRepository.findByPlayer(player).orElseThrow();
        assertThat(ko.getCalculatedHpPercent()).isZero(); // KO
    }

    // ── TC-228: Fugir de chefe fraco eventualmente completa a expedição (sem ficar preso em BOSS_PENDING) ──
    @Test
    @DisplayName("TC-228 | Flee resolves the boss (escape completes, fail forces the fight)")
    void tc228_fleeResolvesBoss() {
        Player player = playerOf("boss");
        crusher(player); // forte: se a fuga falhar, ainda vence a luta forçada → sempre resolve

        ZoneService.CollectResult last = null;
        // várias tentativas: ou foge (COMPLETED) ou falha e luta (vence, COMPLETED). Nunca fica BOSS_PENDING.
        for (int i = 0; i < 5; i++) {
            ZoneActivity act = bossPending(player, Zone.SAFE, 1);
            last = zoneService.resolveBossFlee(playerRepository.findById(player.getId()).orElseThrow(), act.getId());
            ZoneActivity after = activityRepository.findById(act.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(ZoneActivityStatus.COMPLETED);
            // restaura HP entre tentativas (caso tenha caído numa luta)
            Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
            w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
            warriorRepository.save(w);
        }
        assertThat(last).isNotNull();
        assertThat(last.survived()).isTrue();
    }
}
