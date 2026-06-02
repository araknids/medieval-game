package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "guilds")
@Data
@NoArgsConstructor
public class Guild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    private String description;

    // Treasury stored in bronze (same unit as player currency)
    @Column(columnDefinition = "bigint default 0")
    private long gold = 0;

    @Column(columnDefinition = "integer default 1")
    private int level = 1;

    @Column(nullable = false)
    private Long leaderId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Max members: 15 at level 1, +5 per additional level
    public int maxMembers() {
        return 10 + level * 5;
    }

    // Cost in bronze to level up: level 1→2 = 1000, level 2→3 = 2000, etc.
    public long levelUpCost() {
        return level * 1000L;
    }

    // XP bonus % for all members — min(20, (level-1)*5)
    public int xpBonus() {
        return Math.min(20, (level - 1) * 5);
    }

    // Drop chance bonus % — min(7, max(0, level-2)*2)
    public int dropBonus() {
        return Math.min(7, Math.max(0, level - 2) * 2);
    }

    // Bronze reward bonus % — min(10, max(0, level-3)*5)
    public int bronzeBonus() {
        return Math.min(10, Math.max(0, level - 3) * 5);
    }
}
