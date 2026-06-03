package com.medieval.game.model;

import com.medieval.game.enums.WorkStatus;
import com.medieval.game.enums.WorkType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "work_sessions")
@Data
@NoArgsConstructor
public class WorkSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: impede double-collect concorrente do trabalho. [AUDITORIA C3]
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkType workType;

    private int hours;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkStatus status = WorkStatus.IN_PROGRESS;

    private LocalDateTime startedAt  = LocalDateTime.now();
    private LocalDateTime finishesAt;

    private long goldReward;
    private int  xpReward;

    public boolean isReadyToCollect() {
        return status == WorkStatus.IN_PROGRESS
                && LocalDateTime.now().isAfter(finishesAt);
    }
}
