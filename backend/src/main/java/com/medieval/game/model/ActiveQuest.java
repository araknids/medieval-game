package com.medieval.game.model;

import com.medieval.game.enums.QuestStatus;
import com.medieval.game.enums.QuestType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "active_quests")
@Getter
@Setter
@NoArgsConstructor
public class ActiveQuest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: impede double-collect concorrente da mesma quest. [AUDITORIA C3]
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warrior_id", nullable = false)
    private Warrior warrior;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestType questType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestStatus status = QuestStatus.IN_PROGRESS;

    @Column(nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime completesAt;

    private long goldReward;
    private long expReward;

    public boolean isReadyToCollect() {
        // >= (não > estrito): no modo sem-timer completesAt=agora; com isAfter haveria uma corrida
        // de mesmo-instante onde a quest fica momentaneamente "não pronta". Igual a ZoneActivity. [SEM_TIMER]
        return status == QuestStatus.IN_PROGRESS
                && !LocalDateTime.now().isBefore(completesAt);
    }
}
