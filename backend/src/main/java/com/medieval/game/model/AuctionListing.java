package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Anúncio na Casa de Leilão: 1 item por preço fixo (buyout), expira em 2 dias. [LEILAO] */
@Entity
@Table(name = "auction_listings")
@Getter
@Setter
@NoArgsConstructor
public class AuctionListing {

    public enum Status { ACTIVE, SOLD, EXPIRED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private InventoryItem item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id", nullable = false)
    private Player seller;

    private long price; // buyout, em bronze

    @Column(nullable = false)
    private LocalDateTime listedAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    private Long buyerId; // quem comprou (null se não vendido)

    public boolean isExpired() { return LocalDateTime.now().isAfter(endsAt); }
}
