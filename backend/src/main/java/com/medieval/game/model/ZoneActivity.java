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

    // [UNIFICAÇÃO_ZONA] Reino da coleta (drops específicos: Mar Abençoado = peixe de vida). null = combate.
    @Enumerated(EnumType.STRING)
    private com.medieval.game.enums.Kingdom kingdom;

    // [ELEMENTOS] Área de elemento: define a essência dropada + o elemento dos monstros. null = sem elemento.
    @Enumerated(EnumType.STRING)
    @Column(name = "element", length = 10)
    private com.medieval.game.enums.Element element;

    // [ZONA_CHEFE] Chefe errante pendente (status BOSS_PENDING): nível e nome guardados até fugir/encarar.
    @Column(name = "boss_level")
    private int bossLevel = 0;
    @Column(name = "boss_name")
    private String bossName;

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
    // [PVP_FLAG] O PvP de zona virou raid-by-flag: a vítima é saqueada direto + avisada por mail.
    // Os antigos campos de "emboscada pendente" (ambushPending/ambushCount/lastAmbush*) foram removidos.

    public boolean isReadyToCollect() {
        // >= em vez de > estrito: pronta quando o tempo chega (consistente com KingdomActiveQuest/
        // GatheringSession). Evita flake no instant-complete, onde endsAt == now() no mesmo instante.
        return status == ZoneActivityStatus.IN_PROGRESS
                && !LocalDateTime.now().isBefore(endsAt);
    }

    public boolean isDefeated() {
        return status == ZoneActivityStatus.DEFEATED;
    }
}
