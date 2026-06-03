package com.medieval.game.model;

import com.medieval.game.enums.GatheringStatus;
import com.medieval.game.enums.SkillType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "gathering_sessions")
@Data
@NoArgsConstructor
public class GatheringSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: impede double-collect concorrente da coleta. [AUDITORIA C3]
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SkillType skillType;

    private int durationMinutes;
    private int xpReward;

    @Enumerated(EnumType.STRING)
    private GatheringStatus status = GatheringStatus.IN_PROGRESS;

    private LocalDateTime startedAt  = LocalDateTime.now();
    private LocalDateTime finishesAt;

    public boolean isReadyToCollect() {
        return status == GatheringStatus.IN_PROGRESS
                && LocalDateTime.now().isAfter(finishesAt);
    }
}
