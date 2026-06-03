package com.medieval.game.model;

import com.medieval.game.enums.ResourceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "socketed_gems",
       uniqueConstraints = @UniqueConstraint(columnNames = {"inventory_item_id", "slot_index"}))
@Getter
@Setter
@NoArgsConstructor
public class SocketedGem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResourceType gemType;

    private int slotIndex; // 0, 1 ou 2
}
