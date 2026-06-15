package com.medieval.game.model;

import com.medieval.game.enums.Kingdom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "territory_battle_logs")
@Getter
@Setter
@NoArgsConstructor
public class TerritoryBattleLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kingdom territory;

    private String attackerGuildName;
    private String defenderGuildName; // null = NPC

    private String winnerGuildName;

    @Column(name = "battle_log", columnDefinition = "TEXT")
    private String battleLog;

    // [GUERRA_GAUNTLET] eventos (JSON) da batalha p/ o replay 3D no cliente. null = sem replay (logs antigos / sem combate).
    @Column(name = "battle_events", columnDefinition = "TEXT")
    private String battleEvents;

    @Column(nullable = false)
    private LocalDateTime resolvedAt = LocalDateTime.now();
}
