package com.medieval.game.model;

import com.medieval.game.enums.TrainingStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "training_sessions")
@Data
@NoArgsConstructor
public class TrainingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(nullable = false)
    private int hours;

    @Column(columnDefinition = "bigint default 0")
    private long bronzeCost;

    @Column(columnDefinition = "bigint default 0")
    private long xpReward;

    @Column(nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime finishesAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrainingStatus status = TrainingStatus.IN_PROGRESS;

    public boolean isReadyToCollect() {
        return !LocalDateTime.now().isBefore(finishesAt);
    }
}
