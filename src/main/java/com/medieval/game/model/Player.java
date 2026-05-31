package com.medieval.game.model;

import com.medieval.game.enums.Location;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.Duration;

@Entity
@Table(name = "players")
@Data
@NoArgsConstructor
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    private long gold = 500;

    private int rankPoints  = 1000;
    private int arenaWins   = 0;
    private int arenaLosses = 0;

    private int currentStamina = 100;

    @Column(nullable = false)
    private LocalDateTime staminaUpdatedAt = LocalDateTime.now();

    // Calcula estamina atual considerando regeneração passiva
    // 100 de estamina em 120 minutos (2 horas)
    public int getCalculatedStamina() {
        long minutes = Duration.between(staminaUpdatedAt, LocalDateTime.now()).toMinutes();
        int regen = (int) (minutes * 100.0 / 120.0);
        return Math.min(100, currentStamina + regen);
    }

    // Minutos restantes para estamina cheia (0 se já está cheia)
    public long getMinutesToFullStamina() {
        int stamina = getCalculatedStamina();
        if (stamina >= 100) return 0;
        return (long) Math.ceil((100 - stamina) * 120.0 / 100.0);
    }

    @Enumerated(EnumType.STRING)
    private Location location = Location.TAVERN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guild_id")
    private Guild guild;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
