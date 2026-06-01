package com.medieval.game.model;

import com.medieval.game.enums.ResourceType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "resource_inventory",
       uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "resource_type"}))
@Data
@NoArgsConstructor
public class ResourceInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private ResourceType resourceType;

    private long quantity = 0;
}
