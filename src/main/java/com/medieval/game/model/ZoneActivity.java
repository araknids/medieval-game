package com.medieval.game.model;

import com.medieval.game.enums.*;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "zone_activities")
@Data
@NoArgsConstructor
public class ZoneActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    private Zone zone;

    @Enumerated(EnumType.STRING)
    private ActivityRole role;

    @Enumerated(EnumType.STRING)
    private SkillType skillType; // null se for hunter

    private int durationMinutes;

    private LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    private ZoneActivityStatus status = ZoneActivityStatus.IN_PROGRESS;

    // Recompensas acumuladas
    private long bronzeGained = 0;
    private long xpGained     = 0;

    // PvP results
    private boolean attacked        = false;
    private boolean survivedAttack  = false;
    private long    bronzeLost      = 0;
    private String  lostEquippedItem; // nome do item equipado perdido (Alto Risco, 10%)

    @Column(columnDefinition = "TEXT")
    private String battleLog; // linhas do combate separadas por \n

    private String attackerWarriorName;
    private LocalDateTime resolvedAt;

    public boolean isReadyToCollect() {
        return status == ZoneActivityStatus.IN_PROGRESS
                && LocalDateTime.now().isAfter(endsAt);
    }

    public boolean isDefeated() {
        return status == ZoneActivityStatus.DEFEATED;
    }
}
