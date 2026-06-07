package com.medieval.game.integration;

import com.medieval.game.enums.ActivityRole;
import com.medieval.game.enums.Element;
import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.enums.Zone;
import com.medieval.game.enums.ZoneActivityStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.ZoneActivity;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.ZoneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// TC-230+ — [FORTALEZA_ZONAS] Fortaleza Maldita: 3 zonas de caçada (verde/amarela/vermelha) + elementos.
// A "coleta" é matar mob → materiais de combate + essência do elemento + XP/bronze por-kill.
@DisplayName("TC-230 | Fortaleza — zonas de caçada (COMBAT)")
class ZoneCombatHuntTest extends BaseIntegrationTest {

    @Autowired PlayerRepository  playerRepository;
    @Autowired WarriorRepository warriorRepository;
    @Autowired ZoneService       zoneService;
    @Autowired com.medieval.game.service.GatheringService gatheringService; // [MONSTER_CORE_BATALHA]

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("hunt"));
    }

    private Player playerOf() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("hunt"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    private Warrior crusher(Player p, int level) {
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setWarriorClass(WarriorClass.WARRIOR);
        w.setLevel(level);
        w.setAttack(2000); w.setDefense(2000); w.setHealth(5000);
        w.setStrength(800); w.setConstitution(200);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        return warriorRepository.save(w);
    }

    // ── TC-230: COMBAT entra na zona VERDE (antes era bloqueada) e a caçada dá materiais + XP/bronze ──
    @Test
    @DisplayName("TC-230 | Caçada na verde (SAFE) dropa Monster Core + essência + XP/bronze")
    void tc230_safeHunt_dropsMaterialsAndRewards() {
        Player p = playerOf();
        crusher(p, 30); // esmaga qualquer encontro PvE → sempre sobrevive

        // Antes a COMBAT era barrada na SAFE; agora entra normalmente.
        ZoneActivity act = zoneService.enter(p, Zone.SAFE, ActivityRole.COMBAT, null, 20, Kingdom.COMBAT, Element.FIRE);
        ZoneService.CollectResult r = zoneService.collect(playerRepository.findById(p.getId()).orElseThrow(), act.getId());

        assertThat(r.survived()).isTrue();
        assertThat(r.activity().getStatus()).isEqualTo(ZoneActivityStatus.COMPLETED);
        assertThat(r.drops()).anyMatch(d -> d.type() == ResourceType.MONSTER_CORE);     // material de combate
        assertThat(r.drops()).anyMatch(d -> d.type() == Element.FIRE.essence());          // essência da área
        assertThat(r.activity().getXpGained()).isPositive();                              // XP por-kill
        assertThat(r.activity().getBronzeGained()).isPositive();                          // bronze por-kill
    }

    // ── TC-231: zona VERMELHA escala o loot (mult 2.5) acima da verde (mult 1.0) ──
    @Test
    @DisplayName("TC-231 | Vermelha rende mais materiais/XP que a verde (multiplicador do tier)")
    void tc231_redTierScalesRewards() {
        Player p = playerOf();
        crusher(p, 30);

        ZoneActivity green = zoneService.enter(p, Zone.SAFE, ActivityRole.COMBAT, null, 20, Kingdom.COMBAT, Element.FIRE);
        long greenXp = zoneService.collect(playerRepository.findById(p.getId()).orElseThrow(), green.getId())
                .activity().getXpGained();

        Player p2 = playerRepository.findById(p.getId()).orElseThrow();
        p2.setCurrentStamina(100); p2.setStaminaUpdatedAt(LocalDateTime.now()); playerRepository.save(p2);
        warriorRepository.findByPlayer(p2).ifPresent(w -> {
            w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now()); warriorRepository.save(w);
        });

        ZoneActivity red = zoneService.enter(p2, Zone.HIGH_RISK, ActivityRole.COMBAT, null, 20, Kingdom.COMBAT, Element.FIRE);
        long redXp = zoneService.collect(playerRepository.findById(p.getId()).orElseThrow(), red.getId())
                .activity().getXpGained();

        assertThat(redXp).isGreaterThan(greenXp); // 2.5× vs 1.0×
    }

    // ── TC-232: batalha PvE vencida DURANTE a coleta (minerando/pescando) também dropa Monster Core ──
    @Test
    @DisplayName("TC-232 | Vencer um encontro PvE coletando (GATHERING) dropa Monster Core")
    void tc232_gatheringBattle_dropsMonsterCore() {
        Player p = playerOf();
        crusher(p, 30); // esmaga qualquer NPC → sempre vence o encontro

        boolean gotCore = false;
        // HIGH_RISK tem 35% de encontro de NPC por farm; em ~50 coletas vence vários → dropa Monster Core.
        for (int i = 0; i < 50 && !gotCore; i++) {
            Player fp = playerRepository.findById(p.getId()).orElseThrow();
            fp.setCurrentStamina(100); fp.setStaminaUpdatedAt(LocalDateTime.now()); playerRepository.save(fp);
            warriorRepository.findByPlayer(fp).ifPresent(w -> {
                w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now()); warriorRepository.save(w);
            });
            var act = zoneService.enter(playerRepository.findById(p.getId()).orElseThrow(),
                    Zone.HIGH_RISK, ActivityRole.GATHERING, com.medieval.game.enums.SkillType.FISHING, 20);
            var r = zoneService.collect(playerRepository.findById(p.getId()).orElseThrow(), act.getId());
            if (r.drops().stream().anyMatch(d -> d.type() == ResourceType.MONSTER_CORE)) gotCore = true;
        }
        assertThat(gotCore).as("uma batalha PvE vencida na coleta deveria dropar Monster Core").isTrue();
        assertThat(gatheringService.resourceQuantityTotal(p, ResourceType.MONSTER_CORE)).isPositive();
    }
}
