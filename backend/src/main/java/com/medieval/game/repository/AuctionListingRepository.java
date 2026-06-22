package com.medieval.game.repository;

import com.medieval.game.model.AuctionListing;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuctionListingRepository extends JpaRepository<AuctionListing, Long> {

    // [AUDITORIA_2 A5] browse capado (evita serializar o livro inteiro). Paginação = futuro (PLANO_LEILAO).
    // [VARREDURA] item+seller eager (@EntityGraph) — antes cada linha lazy-carregava os 2 (~400 SELECTs/página).
    @EntityGraph(attributePaths = {"item", "seller"})
    List<AuctionListing> findTop200ByStatusOrderByListedAtDesc(AuctionListing.Status status);
    @EntityGraph(attributePaths = {"item", "seller"})
    List<AuctionListing> findBySellerAndStatus(Player seller, AuctionListing.Status status);
    long countBySellerAndStatus(Player seller, AuctionListing.Status status);
    List<AuctionListing> findByStatusAndEndsAtBefore(AuctionListing.Status status, LocalDateTime now);

    // [VARREDURA] Claim ATÔMICO da expiração: só a tx que muda ACTIVE→EXPIRED (rowcount==1) devolve o item.
    // O perdedor (já vendida/expirada por outra instância) sai com 0 SEM lançar OptimisticLock — antes o
    // throw marcava o batch inteiro rollback-only (self-invocation) e perdia todas as expirações.
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE AuctionListing l SET l.status = :to WHERE l.id = :id AND l.status = :from")
    int claimStatus(@Param("id") Long id, @Param("from") AuctionListing.Status from, @Param("to") AuctionListing.Status to);
    // [LEILAO_FK_FIX] limpa listagens (histórico SOLD/CANCELLED/EXPIRED) que referenciam o item antes de
    // deletá-lo (vender) — senão a FK auction_listings.item_id (nullable=false) barra a venda.
    void deleteByItem(InventoryItem item);

    default List<AuctionListing> findActiveDue(LocalDateTime now) {
        return findByStatusAndEndsAtBefore(AuctionListing.Status.ACTIVE, now);
    }
}
