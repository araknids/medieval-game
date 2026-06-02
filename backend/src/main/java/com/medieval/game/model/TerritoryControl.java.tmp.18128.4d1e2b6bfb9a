package com.medieval.game.model;

import com.medieval.game.enums.Territory;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "territory_controls")
@Data
@NoArgsConstructor
public class TerritoryControl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(unique = true, nullable = false)
    private Territory territory;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guild_id")
    private Guild controllingGuild; // null = neutral

    // How many consecutive 6h cycles the guild has defended this territory
    @Column(columnDefinition = "integer default 0")
    private int defenseStreak = 0;

    private LocalDateTime dominantSince;

    // Debuff % applied to defenders in the NEXT battle = min(50, defenseStreak * 5)
    public int debuffPercent() {
        return Math.min(50, defenseStreak * 5);
    }

    public boolean isNeutral() {
        return controllingGuild == null;
    }
}
