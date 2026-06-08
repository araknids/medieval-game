package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.StashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// Inventário V2 — bag unificada ([BAG_WEIGHT] recurso pesa 0.2 slot → 5 recursos = 1 slot) + stash.
@DisplayName("Inventário V2 | bag unificada + stash")
class StashTest extends BaseIntegrationTest {

    @Autowired StashService           stash;
    @Autowired GatheringService       gathering;
    @Autowired InventoryService       inv;
    @Autowired PlayerRepository       playerRepo;
    @Autowired InventoryItemRepository itemRepo;

    Long playerId;

    @BeforeEach
    void setup() throws Exception {
        String user = uniqueUser("stash");
        registerAndGetToken(user);
        Player p = playerRepo.findAll().stream().filter(x -> x.getUsername().equals(user)).findFirst().orElseThrow();
        playerId = p.getId();
        itemRepo.deleteAll(itemRepo.findAllByPlayer(p)); // limpa itens iniciais → bag vazia (0/30)
        p.addBronzeAmount(100_000); playerRepo.save(p);  // bronze p/ as taxas do stash
    }

    // Sempre busca o player FRESCO (Player tem @Version → evitar reusar instância entre transações).
    private Player player() { return playerRepo.findById(playerId).orElseThrow(); }

    @Test
    @DisplayName("Recurso pesa 0.2 slot na bag (5 recursos = 1 slot)")
    void resourcesCountBagSlots() {
        long added = gathering.addResource(player(), ResourceType.SMALL_FISH, 5);
        assertThat(added).isEqualTo(5);
        assertThat(inv.bagSize(player())).isEqualTo(1); // [BAG_WEIGHT] 5 × 0.2 = 1 slot
    }

    @Test
    @DisplayName("Bag clampa em 150 recursos (30 slots × 5) — excedente é perdido")
    void bagClampsAt30() {
        // [BAG_WEIGHT] 30 slots × 5 recursos/slot = 150 unidades cabem.
        long added = gathering.addResource(player(), ResourceType.SMALL_FISH, 200);
        assertThat(added).isEqualTo(150);         // só 150 cabem (30 slots cheios)
        assertThat(inv.bagSize(player())).isEqualTo(30);
        long more = gathering.addResource(player(), ResourceType.SALMON, 5);
        assertThat(more).isZero();                // bag cheia → nada adicionado
    }

    @Test
    @DisplayName("Depositar e retirar recurso move entre bag e stash")
    void depositWithdrawResource() {
        gathering.addResource(player(), ResourceType.SALMON, 10);
        stash.depositResource(player(), ResourceType.SALMON, 6);
        assertThat(inv.bagSize(player())).isEqualTo(1);   // [BAG_WEIGHT] 4 recursos × 0.2 = 0.8 → 1 slot (ceil)
        assertThat(stash.stashSize(player())).isEqualTo(6);

        stash.withdrawResource(player(), ResourceType.SALMON, 6);
        assertThat(inv.bagSize(player())).isEqualTo(2);   // [BAG_WEIGHT] 10 recursos × 0.2 = 2 slots
        assertThat(stash.stashSize(player())).isZero();
    }

    @Test
    @DisplayName("Depositar item tira da bag e põe no stash")
    void depositItem() {
        InventoryItem it = inv.make(player(), "Test Ring", ItemType.RING, 0, 0, 0, 1, 10);
        int bagBefore = inv.bagSize(player());
        stash.depositItem(player(), it.getId());
        assertThat(inv.bagSize(player())).isEqualTo(bagBefore - 1);
        assertThat(stash.stashSize(player())).isEqualTo(1);
    }

    @Test
    @DisplayName("Cada operação de stash cobra 50 bronze")
    void stashChargesFee() {
        gathering.addResource(player(), ResourceType.TUNA, 3);
        long before = totalBronze(player());
        stash.depositResource(player(), ResourceType.TUNA, 3);
        long after = totalBronze(player());
        assertThat(before - after).isEqualTo(StashService.STASH_FEE);
    }

    private long totalBronze(Player pl) {
        return pl.getBronze() + pl.getSilver() * 100L + pl.getGold() * 10_000L;
    }
}
