package com.medieval.game.repository;

import com.medieval.game.model.AuctionListing;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AuctionListingRepository extends JpaRepository<AuctionListing, Long> {

    List<AuctionListing> findByStatus(AuctionListing.Status status);
    // [AUDITORIA_2 A5] browse capado (evita serializar o livro inteiro). Paginação = futuro (PLANO_LEILAO).
    List<AuctionListing> findTop200ByStatusOrderByListedAtDesc(AuctionListing.Status status);
    List<AuctionListing> findBySellerAndStatus(Player seller, AuctionListing.Status status);
    long countBySellerAndStatus(Player seller, AuctionListing.Status status);
    List<AuctionListing> findByStatusAndEndsAtBefore(AuctionListing.Status status, LocalDateTime now);
    // [LEILAO_FK_FIX] limpa listagens (histórico SOLD/CANCELLED/EXPIRED) que referenciam o item antes de
    // deletá-lo (vender) — senão a FK auction_listings.item_id (nullable=false) barra a venda.
    void deleteByItem(InventoryItem item);

    default List<AuctionListing> findActiveDue(LocalDateTime now) {
        return findByStatusAndEndsAtBefore(AuctionListing.Status.ACTIVE, now);
    }
}
