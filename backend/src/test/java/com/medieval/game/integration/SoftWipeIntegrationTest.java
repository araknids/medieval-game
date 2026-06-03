package com.medieval.game.integration;

import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.MaintenanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// Soft wipe — reset fresh-start mantendo contas. docs do MaintenanceService/SoftWipeRunner.
@DisplayName("Soft Wipe | reset fresh-start mantendo login")
class SoftWipeIntegrationTest extends BaseIntegrationTest {

    @Autowired MaintenanceService       maintenanceService;
    @Autowired PlayerRepository         playerRepository;
    @Autowired WarriorRepository        warriorRepository;
    @Autowired InventoryItemRepository  inventoryItemRepository;
    @Autowired GuildRepository          guildRepository;

    @Test
    @DisplayName("softWipe reseta progresso, devolve itens iniciais e dissolve guildas")
    void softWipe_resetsEverythingKeepsLogin() throws Exception {
        registerAndGetToken(uniqueUser("wipe"));
        Player p = playerRepository.findAll().stream()
                .filter(x -> x.getUsername().startsWith("wipe"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
        String username = p.getUsername();
        Long playerId = p.getId();

        // Suja o estado: grana, soulstones, guerreiro forte, guilda
        p.addBronzeAmount(1_000_000);
        p.setSoulStones(15);
        p.setTowerBestFloor(40);
        Guild g = new Guild();
        g.setName("WipeGuild-" + System.nanoTime());
        g.setLeaderId(playerId);
        g.setGold(5000);
        g = guildRepository.save(g);
        p.setGuild(g);
        playerRepository.save(p);

        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setLevel(50);
        w.setExperience(99999);
        w.setAvailablePoints(40);
        w.setStrength(30);
        warriorRepository.save(w);

        // Wipe
        int reset = maintenanceService.softWipe();
        assertThat(reset).isGreaterThanOrEqualTo(1);

        // Conta preservada, progresso zerado
        Player after = playerRepository.findById(playerId).orElseThrow();
        assertThat(after.getUsername()).isEqualTo(username);     // login mantido
        assertThat(after.totalBronze()).isEqualTo(5000);          // 50 prata
        assertThat(after.getSoulStones()).isZero();
        assertThat(after.getTowerBestFloor()).isZero();
        assertThat(after.getGuild()).isNull();

        Warrior wAfter = warriorRepository.findByPlayer(after).orElseThrow();
        assertThat(wAfter.getLevel()).isEqualTo(1);
        assertThat(wAfter.getExperience()).isZero();
        assertThat(wAfter.getAvailablePoints()).isZero();
        assertThat(wAfter.getStrength()).isZero();
        assertThat(wAfter.getAttack()).isEqualTo(15);             // base WARRIOR

        // Itens iniciais de volta (7), guildas dissolvidas
        assertThat(inventoryItemRepository.findAllByPlayer(after)).hasSize(7);
        assertThat(guildRepository.count()).isZero();
    }
}
