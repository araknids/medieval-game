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

    @Column(columnDefinition = "bigint default 0")
    private long gold = 0;

    @Column(columnDefinition = "integer default 1")
    private int level = 1;

    @Column(nullable = false)
    private Long leaderId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Máximo de membros: 15 no nível 1, +5 por nível adicional
    public int maxMembers() {
        return 10 + level * 5;
    }

    // Custo em gold da guilda para subir de nível
    public long levelUpCost() {
        return level * 1000L;
    }
}
