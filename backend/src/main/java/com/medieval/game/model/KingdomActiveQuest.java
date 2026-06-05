package com.medieval.game.model;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.enums.QuestStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "kingdom_active_quests")
@Getter
@Setter
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

    // [DAILY_QUESTS] Janela de 12h (epoch/43200) em que a quest foi COLLECTED.
    // 0 = legado/nunca coletada. Usado pra travar a daily 1x por janela. Ver docs/PLANO_QUESTS.md.
    @Column(columnDefinition = "bigint default 0")
    private long completedWindowId = 0;

    public boolean isReadyToCollect() {
        return !LocalDateTime.now().isBefore(completesAt);
    }

    public long secondsRemaining() {
        if (isReadyToCollect()) return 0;
        return java.time.temporal.ChronoUnit.SECONDS.between(LocalDateTime.now(), completesAt);
    }
}
