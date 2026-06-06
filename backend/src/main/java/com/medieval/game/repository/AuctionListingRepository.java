package com.medieval.game.repository;

import com.medieval.game.model.AuctionListing;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AuctionListingRepository extends JpaRepository<AuctionListing, Long> {

    List<AuctionListing> findByStatus(AuctionListing.Status status);
    List<AuctionListing> findBySellerAndStatus(Player seller, AuctionListing.Status status);
    long countBySellerAndStatus(Player seller, AuctionListing.Status status);
    List<AuctionListing> findByStatusAndEndsAtBefore(AuctionListing.Status status, LocalDateTime now);

    default List<AuctionListing> findActiveDue(LocalDateTime now) {
        return findByStatusAndEndsAtBefore(AuctionListing.Status.ACTIVE, now);
    }
}
