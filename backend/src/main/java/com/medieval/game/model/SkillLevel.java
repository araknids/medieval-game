package com.medieval.game.model;

import com.medieval.game.enums.SkillType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "skill_levels",
       uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "skill_type"}))
@Data
@NoArgsConstructor
public class SkillLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(name = "skill_type", nullable = false)
    private SkillType skillType;

    private int  level      = 1;
    private long experience = 0;

    // XP para o próximo nível: 100 por nível (nível 1→2: 100, 2→3: 200, ...)
    public long expNeededForNextLevel() {
        return (long) level * 100;
    }
}
