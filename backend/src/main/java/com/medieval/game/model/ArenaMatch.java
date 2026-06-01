package com.medieval.game.model;

import com.medieval.game.enums.MatchStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "arena_matches")
@Data
@NoArgsConstructor
public class ArenaMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
