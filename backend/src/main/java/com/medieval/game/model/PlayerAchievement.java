package com.medieval.game.model;

import com.medieval.game.enums.Achievement;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Achievement desbloqueado por um jogador (1 linha por achievement). [TITULOS] */
@Entity
@Table(name = "player_achievements",
       uniqueConstraints = @UniqueConstraint(name = "uk_player_achievement", columnNames = {"player_id", "achievement"}))
@Getter
@Setter
@NoArgsConstructor
public class PlayerAchievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Achievement achievement;

    @Column(nullable = false)
    private LocalDateTime unlockedAt = LocalDateTime.now();

    public PlayerAchievement(Player player, Achievement achievement) {
        this.player = player;
        this.achievement = achievement;
        this.unlockedAt = LocalDateTime.now();
    }
}
