package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "shop_purchases",
       uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "rotation_id", "slot_index"}))
@Getter
@Setter
@NoArgsConstructor
public class ShopPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    private long rotationId;
    private int  slotIndex;
}
