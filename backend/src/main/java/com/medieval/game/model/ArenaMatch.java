package com.medieval.game.model;

import com.medieval.game.enums.MatchStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "arena_matches")
@Getter
@Setter
@NoArgsConstructor
public class ArenaMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: impede coletar a recompensa da mesma luta duas vezes. [AUDITORIA C3]
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenger_id", nullable = false)
    private Player challenger;

    // null se o oponente for NPC
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opponent_id")
    private Player opponent;

    private String opponentName;

    @Enumerated(EnumType.STRING)
    private MatchStatus status = MatchStatus.FIGHTING;

    private boolean challengerWon;

    @Column(columnDefinition = "TEXT")
    private String battleLog;

    private long goldReward;
    private int  rankChange;

    private LocalDateTime startedAt  = LocalDateTime.now();
    private LocalDateTime finishesAt;
}
