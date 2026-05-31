package com.medieval.game.model;

import com.medieval.game.enums.ItemType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory_items")
@Data
@NoArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemType type;

    private int attackBonus;
    private int defenseBonus;
    private int healthBonus;

    // 1=Comum, 2=Incomum, 3=Raro, 4=Épico
    private int rarity = 1;

    private long sellPrice = 0;

    private boolean equipped = false;
}
