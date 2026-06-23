package com.medieval.game.model;

import com.medieval.game.enums.TrainingStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "training_sessions")
@Getter
@Setter
@NoArgsConstructor
public class TrainingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: serializa double-collect concorrente do treino (XP). [AUDITORIA]
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(nullable = false)
    private int hours;

    @Column(columnDefinition = "bigint default 0")
    private long bronzeCost;

    @Column(columnDefinition = "bigint default 0")
    private long xpReward;

    // [TREINO_IDLE] true = treino idle GRÁTIS (timer real, bloqueia aventura); false = pago instantâneo.
    @Column(columnDefinition = "boolean default false")
    private boolean free = false;

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
