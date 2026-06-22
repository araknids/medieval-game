package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.Consignment;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.BlueMerchantService;
import com.medieval.game.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// [MERCADO_STEAM] Mercador Azul: consignar (escrow) / cancelar (devolver) / guards / link. Steam OFF no perfil de teste.
@DisplayName("Blue Merchant | consignar / devolver / guards (Steam desligada)")
class BlueMerchantTest extends BaseIntegrationTest {

    @Autowired BlueMerchantService     blueMerchant;
    @Autowired InventoryService        inventoryService;
    @Autowired InventoryItemRepository itemRepo;
    @Autowired PlayerRepository        playerRepository;

    private Player newPlayer() {
        String u = uniqueUser("bm");
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        return playerRepository.save(p);
    }
    private Player reload(Player p) { return playerRepository.findById(p.getId()).orElseThrow(); }

    @Test
    @DisplayName("Consignar tira o item da bag e cria consignação HELD (Steam off)")
    void consign_movesToEscrow() {
        Player p = newPlayer();
        InventoryItem item = inventoryService.make(p, "Old Ring", ItemType.RING, 5, 0, 0, 3, 100); // [BALANCE_ECON] Raro+ (só Raro+ é consignável)
        Long id = item.getId();

        Consignment c = blueMerchant.consign(p, id);

        assertThat(c.getStatus()).isEqualTo(Consignment.Status.HELD); // Steam desligada → fica em escrow
        assertThat(c.getSteamItemDef()).isNotBlank(); // itemdef mapeado (placeholder)
        assertThat(itemRepo.findById(id).orElseThrow().isConsigned()).isTrue();
        assertThat(inventoryService.getInventory(p).stream().noneMatch(i -> i.getId().equals(id))).isTrue();
    }

    @Test
    @DisplayName("Cancelar devolve o item pra bag (RETURNED)")
    void cancel_returnsItem() {
        Player p = newPlayer();
        InventoryItem item = inventoryService.make(p, "Old Ring", ItemType.RING, 5, 0, 0, 3, 100); // [BALANCE_ECON] Raro+ (só Raro+ é consignável)
        Long id = item.getId();
        Consignment c = blueMerchant.consign(p, id);

        blueMerchant.cancel(p, c.getId());

        assertThat(itemRepo.findById(id).orElseThrow().isConsigned()).isFalse();
        assertThat(inventoryService.getInventory(p).stream().anyMatch(i -> i.getId().equals(id))).isTrue();
    }

    @Test
    @DisplayName("Item consignado não pode ser equipado/vendido")
    void consigned_isLocked() {
        Player p = newPlayer();
        InventoryItem item = inventoryService.make(p, "Old Ring", ItemType.RING, 5, 0, 0, 3, 100); // [BALANCE_ECON] Raro+ (só Raro+ é consignável)
        Long id = item.getId();
        blueMerchant.consign(p, id);

        assertThatThrownBy(() -> inventoryService.equip(p, id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inventoryService.sell(p, id)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Consignar → cancelar → vender NÃO quebra na FK de consignments [CONSIGN_FK_FIX]")
    void cancel_thenSell_succeeds() {
        Player p = newPlayer();
        InventoryItem item = inventoryService.make(p, "Old Ring", ItemType.RING, 5, 0, 0, 3, 100); // [BALANCE_ECON] Raro+ (só Raro+ é consignável)
        Long id = item.getId();
        Consignment c = blueMerchant.consign(p, id);
        blueMerchant.cancel(p, c.getId());   // devolve (RETURNED) — a linha de consignação fica

        inventoryService.sell(p, id);        // antes: FK consignments.item_id barrava a venda do item devolvido

        assertThat(itemRepo.findById(id)).isEmpty();   // item realmente removido (sem violar FK)
    }

    @Test
    @DisplayName("Linkar conta Steam grava o steamId")
    void link_setsSteamId() {
        Player p = newPlayer();
        blueMerchant.linkSteam(p, "76561198000000000");
        assertThat(reload(p).getSteamId()).isEqualTo("76561198000000000");
    }
}
