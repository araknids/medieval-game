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

    private long gold = 0;

    private int level = 1;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
