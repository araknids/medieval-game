package com.medieval.game.model;

import com.medieval.game.enums.WorkType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "work_professions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "work_type"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkProfession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_type", nullable = false)
    private WorkType workType;

    private int  level      = 1;
    private long experience = 0;

    // XP necessária para o próximo nível: 50, 100, 150...
    public long expNeededForNextLevel() {
        return 50L * level;
    }

    // +5% de gold por nível acima do 1
    public double goldBonus() {
        return 1.0 + (level - 1) * 0.05;
    }
}
