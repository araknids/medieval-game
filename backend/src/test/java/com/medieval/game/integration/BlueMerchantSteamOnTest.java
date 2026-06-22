package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.Consignment;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.BlueMerchantService;
import com.medieval.game.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// [MERCADO_STEAM] Steam LIGADA (stub simula a concessão): consignar com conta linkada vira LINKED.
@TestPropertySource(properties = "app.steam.enabled=true")
@DisplayName("Blue Merchant | Steam ligada (stub) → consignar exporta (LINKED)")
class BlueMerchantSteamOnTest extends BaseIntegrationTest {

    @Autowired BlueMerchantService blueMerchant;
    @Autowired InventoryService    inventoryService;
    @Autowired PlayerRepository    playerRepository;

    @Test
    @DisplayName("Com app.steam.enabled=true + conta linkada, consignar vira LINKED com instância")
    void consign_withLinkedSteam_becomesLinked() {
        String u = uniqueUser("bms");
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        p = playerRepository.save(p);
        blueMerchant.linkSteam(p, "76561198000000001");
        p = playerRepository.findById(p.getId()).orElseThrow();

        InventoryItem item = inventoryService.make(p, "Trade Ring", ItemType.RING, 5, 0, 0, 3, 100); // [BALANCE_ECON] Raro+ (só Raro+ é consignável)
        Consignment c = blueMerchant.consign(p, item.getId());

        assertThat(c.getStatus()).isEqualTo(Consignment.Status.LINKED);
        assertThat(c.getSteamItemInstance()).isNotBlank(); // instância simulada pelo stub

        // [MERCADO_STEAM] Já LINKED na Steam = via única: não dá pra pegar de volta (evita duplicar o item).
        Player owner = playerRepository.findById(c.getPlayer().getId()).orElseThrow();
        assertThatThrownBy(() -> blueMerchant.cancel(owner, c.getId()))
                .isInstanceOf(IllegalStateException.class);
    }
}
