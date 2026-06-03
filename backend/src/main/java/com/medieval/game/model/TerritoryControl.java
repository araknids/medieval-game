package com.medieval.game.model;

import com.medieval.game.enums.Kingdom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "territory_controls")
@Getter
@Setter
@NoArgsConstructor
public class TerritoryControl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(unique = true, nullable = false)
    private Kingdom territory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guild_id")
    private Guild controllingGuild; // null = neutral

    // How many consecutive 6h cycles the guild has defended this territory
    @Column(columnDefinition = "integer default 0")
    private int defenseStreak = 0;

    private LocalDateTime dominantSince;

    // Último ciclo (epoch/21600) cujas batalhas já foram resolvidas — torna o cron
    // idempotente e permite reprocessar ciclos perdidos em downtime/deploy. [AUDITORIA A7]
    @Column(columnDefinition = "bigint default 0")
    private long lastResolvedCycleId = 0;

    // Debuff % applied to defenders in the NEXT battle = min(50, defenseStreak * 5)
    public int debuffPercent() {
        return Math.min(50, defenseStreak * 5);
    }

    public boolean isNeutral() {
        return controllingGuild == null;
    }
}
