package com.medieval.game.model;

import com.medieval.game.enums.Territory;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "territory_battle_logs")
@Data
@NoArgsConstructor
public class TerritoryBattleLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Territory territory;

    private String attackerGuildName;
    private String defenderGuildName; // null = NPC

    private String winnerGuildName;

    @Column(name = "battle_log", columnDefinition = "TEXT")
    private String battleLog;

    @Column(nullable = false)
    private LocalDateTime resolvedAt = LocalDateTime.now();
}
