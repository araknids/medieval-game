package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

// Itens V3 — nível de item: poder escala com nível, raridade é multiplicador; requisito pra equipar.
@DisplayName("Itens V3 | nível de item")
class ItemLevelTest extends BaseIntegrationTest {

    @Autowired InventoryService       inv;
    @Autowired PlayerRepository       playerRepo;
    @Autowired WarriorRepository      warriorRepo;
    @Autowired InventoryItemRepository itemRepo;

    Long playerId;

    @BeforeEach
    void setup() throws Exception {
        String user = uniqueUser("ilvl");
        registerAndGetToken(user);
        Player p = playerRepo.findAll().stream().filter(x -> x.getUsername().equals(user)).findFirst().orElseThrow();
        playerId = p.getId();
        itemRepo.deleteAll(itemRepo.findAllByPlayer(p)); // bag limpa
    }
    private Player player() { return playerRepo.findById(playerId).orElseThrow(); }

    @Test
    @DisplayName("Lvl100 Comum é (em média) muito mais forte que Lvl1 Épico")
    void lvl100Common_beats_lvl1Epic() {
        long common100 = 0, epic1 = 0;
        for (int i = 0; i < 300; i++) {
            int[] c = inv.rollItemStats(100, 1); common100 += c[0] + c[1] + c[2];
            int[] e = inv.rollItemStats(1, 4);   epic1    += e[0] + e[1] + e[2];
        }
        assertThat(common100).isGreaterThan(epic1 * 10L); // domina por ordem de grandeza
    }

    @Test
    @DisplayName("Não dá pra equipar item de nível acima do guerreiro")
    void equip_blockedAboveLevel() {
        // guerreiro nível 1; item nível 50
        InventoryItem hi = inv.make(player(), "High Ring", ItemType.RING, 1, 1, 1, 1, 10, 50, null, null);
        assertThatThrownBy(() -> inv.equip(player(), hi.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Requires level");
    }

    @Test
    @DisplayName("Equipa quando o nível do guerreiro alcança o nível do item")
    void equip_okAtLevel() {
        Warrior w = warriorRepo.findByPlayer(player()).orElseThrow();
        w.setLevel(50); warriorRepo.save(w);
        InventoryItem it = inv.make(player(), "Ok Ring", ItemType.RING, 1, 1, 1, 1, 10, 50, null, null);
        inv.equip(player(), it.getId()); // não lança
        assertThat(itemRepo.findById(it.getId()).orElseThrow().isEquipped()).isTrue();
    }
}
