package com.medieval.game.model;

import com.medieval.game.enums.Meal;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

// Estoque de refeições cozidas do jogador. [COZINHA]
@Entity
@Table(name = "meal_inventory",
       uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "meal"}))
@Getter
@Setter
@NoArgsConstructor
public class MealInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal", nullable = false)
    private Meal meal;

    private int quantity = 0;
}
