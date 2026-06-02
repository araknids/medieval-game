package com.medieval.game.model;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.enums.QuestStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "kingdom_active_quests")
@Data
@NoArgsConstructor
public class KingdomActiveQuest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warrior_id", nullable = false)
    private Warrior warrior;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kingdom kingdom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KingdomQuestType questType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestStatus status = QuestStatus.IN_PROGRESS;

    @Column(nullable = false)
    private long bronzeReward;

    @Column(nullable = false)
    private long expReward;

    @Column(nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime completesAt;

    public boolean isReadyToCollect() {
        return !LocalDateTime.now().isBefore(completesAt);
    }

    public long secondsRemaining() {
        if (isReadyToCollect()) return 0;
        return java.time.temporal.ChronoUnit.SECONDS.between(LocalDateTime.now(), completesAt);
    }
}
