package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.AuctionListing;
import com.medieval.game.model.AuctionListing.Status;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.AuctionListingRepository;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.AuctionService;
import com.medieval.game.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Casa de Leilão: postar (taxa 5%), comprar (vendedor 80%), cancelar, expirar. [LEILAO]
@DisplayName("Auction | postar/comprar/cancelar/expirar (taxa 5%+15%)")
class AuctionTest extends BaseIntegrationTest {

    @Autowired AuctionService          auctionService;
    @Autowired AuctionListingRepository listingRepo;
    @Autowired InventoryItemRepository  itemRepo;
    @Autowired PlayerRepository         playerRepository;
    @Autowired WarriorRepository        warriorRepository;
    @Autowired InventoryService         inventoryService;

    private Player newPlayer(String prefix) {
        String u = uniqueUser(prefix);
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        p = playerRepository.save(p);
        Warrior w = new Warrior();
        w.setName("W_" + u); w.setWarriorClass(WarriorClass.WARRIOR); w.setPlayer(p);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
        return p;
    }

    private Player reload(Player p) { return playerRepository.findById(p.getId()).orElseThrow(); }

    private InventoryItem makeItem(Player p) {
        return inventoryService.make(p, "Test Sword", ItemType.WEAPON, 10, 0, 0, 1, 500, 1, "d", "o");
    }

    private Player rich(String prefix) {
        Player p = newPlayer(prefix);
        p.addBronzeAmount(1_000_000); playerRepository.save(p);
        return reload(p);
    }

    // ── Postar: cobra 5% e tira da bag ──
    @Test
    @DisplayName("Postar cobra 5% e remove o item da bag")
    void list_chargesFeeAndRemovesFromBag() {
        Player seller = rich("aucs");
        InventoryItem item = makeItem(reload(seller));
        long bagBefore    = inventoryService.bagSize(reload(seller));
        long bronzeBefore = reload(seller).totalBronze();

        AuctionListing l = auctionService.list(reload(seller), item.getId(), 1000);

        assertThat(l.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(itemRepo.findById(item.getId()).orElseThrow().isListed()).isTrue();
        assertThat(inventoryService.bagSize(reload(seller))).isEqualTo(bagBefore - 1);
        assertThat(reload(seller).totalBronze()).isEqualTo(bronzeBefore - 50); // 5% de 1000
    }

    // ── Comprar: comprador paga cheio, vendedor recebe 80%, item transfere ──
    @Test
    @DisplayName("Comprar: comprador paga 1000, vendedor recebe 850; item vai pro comprador")
    void buy_transfersAndPays() {
        Player seller = rich("aucs");
        InventoryItem item = makeItem(reload(seller));
        auctionService.list(reload(seller), item.getId(), 1000);
        AuctionListing l = listingRepo.findBySellerAndStatus(reload(seller), Status.ACTIVE).get(0);

        Player buyer = rich("aucb");
        long sellerBefore = reload(seller).totalBronze();
        long buyerBefore  = reload(buyer).totalBronze();

        auctionService.buy(reload(buyer), l.getId());

        assertThat(itemRepo.findById(item.getId()).orElseThrow().isListed()).isFalse();
        boolean buyerHasIt = inventoryService.getInventory(reload(buyer)).stream()
                .anyMatch(i -> i.getId().equals(item.getId()));
        assertThat(buyerHasIt).isTrue();                                       // transferiu
        assertThat(reload(buyer).totalBronze()).isEqualTo(buyerBefore - 1000); // pagou cheio
        assertThat(reload(seller).totalBronze()).isEqualTo(sellerBefore + 850);// recebeu 85% (1000 − 15%)
        assertThat(listingRepo.findById(l.getId()).orElseThrow().getStatus()).isEqualTo(Status.SOLD);
    }

    // ── [LEILAO_FK_FIX] Item comprado no leilão PODE ser vendido depois (a FK da listagem SOLD não barra) ──
    @Test
    @DisplayName("Item comprado no leilão pode ser vendido depois (sem erro de FK)")
    void boughtItem_canBeSold() {
        Player seller = rich("aucs");
        InventoryItem item = makeItem(reload(seller));
        auctionService.list(reload(seller), item.getId(), 1000);
        AuctionListing l = listingRepo.findBySellerAndStatus(reload(seller), Status.ACTIVE).get(0);

        Player buyer = rich("aucb");
        auctionService.buy(reload(buyer), l.getId());

        long before = reload(buyer).totalBronze();
        inventoryService.sell(reload(buyer), item.getId()); // antes: FK auction_listings.item_id barrava

        assertThat(itemRepo.findById(item.getId())).isEmpty();          // vendeu (deletou)
        assertThat(reload(buyer).totalBronze()).isGreaterThan(before);  // recebeu bronze
        assertThat(listingRepo.findById(l.getId())).isEmpty();          // listagem histórica também saiu
    }

    @Test
    @DisplayName("Não dá pra comprar a própria listagem")
    void buy_cantBuyOwn() {
        Player p = rich("aucs");
        InventoryItem item = makeItem(reload(p));
        AuctionListing l = auctionService.list(reload(p), item.getId(), 100);
        assertThatThrownBy(() -> auctionService.buy(reload(p), l.getId())).isInstanceOf(IllegalStateException.class);
    }

    // ── Cancelar: item volta, sem reembolso da taxa ──
    @Test
    @DisplayName("Cancelar devolve o item; a taxa de 5% não volta")
    void cancel_returnsItemNoRefund() {
        Player seller = rich("aucs");
        InventoryItem item = makeItem(reload(seller));
        AuctionListing l = auctionService.list(reload(seller), item.getId(), 1000);
        long bronzeAfterList = reload(seller).totalBronze();

        auctionService.cancel(reload(seller), l.getId());

        assertThat(itemRepo.findById(item.getId()).orElseThrow().isListed()).isFalse(); // voltou
        assertThat(reload(seller).totalBronze()).isEqualTo(bronzeAfterList);            // sem reembolso
        assertThat(listingRepo.findById(l.getId()).orElseThrow().getStatus()).isEqualTo(Status.CANCELLED);
    }

    // ── Guards de postar ──
    @Test
    @DisplayName("Postar rejeita preço inválido e item equipado")
    void list_guards() {
        Player p = rich("aucs");
        InventoryItem item = makeItem(reload(p));
        assertThatThrownBy(() -> auctionService.list(reload(p), item.getId(), 0))
                .isInstanceOf(IllegalArgumentException.class);

        InventoryItem eq = makeItem(reload(p));
        inventoryService.equip(reload(p), eq.getId());
        assertThatThrownBy(() -> auctionService.list(reload(p), eq.getId(), 100))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Máximo de 10 listagens ativas")
    void list_maxTen() {
        Player p = rich("aucs");
        for (int i = 0; i < 10; i++) {
            InventoryItem it = makeItem(reload(p));
            auctionService.list(reload(p), it.getId(), 100);
        }
        InventoryItem extra = makeItem(reload(p));
        assertThatThrownBy(() -> auctionService.list(reload(p), extra.getId(), 100))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Expirar: devolve o item ──
    @Test
    @DisplayName("Expirar (2 dias) devolve o item ao vendedor")
    void expire_returnsItem() {
        Player seller = rich("aucs");
        InventoryItem item = makeItem(reload(seller));
        AuctionListing l = auctionService.list(reload(seller), item.getId(), 1000);

        AuctionListing l2 = listingRepo.findById(l.getId()).orElseThrow();
        l2.setEndsAt(LocalDateTime.now().minusMinutes(1)); // força o vencimento
        listingRepo.save(l2);

        auctionService.expireDueAuctions();

        assertThat(itemRepo.findById(item.getId()).orElseThrow().isListed()).isFalse();
        assertThat(listingRepo.findById(l.getId()).orElseThrow().getStatus()).isEqualTo(Status.EXPIRED);
    }
}
