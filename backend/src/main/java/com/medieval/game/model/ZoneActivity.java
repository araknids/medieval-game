package com.medieval.game.model;

import com.medieval.game.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "zone_activities")
@Getter
@Setter
@NoArgsConstructor
public class ZoneActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: protege a coleta e a emboscada concorrente. [AUDITORIA C3]
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

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

    // ── Ambush PvP (player-vs-player) ──────────────────────────────────────────
    /** How many ambushes this expedition survived (drives anti-farm -5% per win). */
    @Column(columnDefinition = "integer default 0")
    private int ambushCount = 0;

    /** True when there is an ambush the owner hasn't acknowledged yet → triggers dialog. */
    @Column(columnDefinition = "boolean default false")
    private boolean ambushPending = false;

    /** Details of the most recent ambush suffered (for the dialog). */
    private String lastAmbusherName;
    private long   lastAmbushBronzeLost = 0;
    private String lastAmbushItemLost;
    @Column(columnDefinition = "TEXT")
    private String lastAmbushLog;

    public boolean isReadyToCollect() {
        return status == ZoneActivityStatus.IN_PROGRESS
                && LocalDateTime.now().isAfter(endsAt);
    }

    public boolean isDefeated() {
        return status == ZoneActivityStatus.DEFEATED;
    }
}
