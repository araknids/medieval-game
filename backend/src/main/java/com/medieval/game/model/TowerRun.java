package com.medieval.game.model;

import com.medieval.game.enums.TowerStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "tower_runs")
@Data
@NoArgsConstructor
public class TowerRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    private int currentFloor  = 1;  // próximo andar a ser combatido
    private int highestFloor  = 0;  // último andar completado com sucesso

    @Enumerated(EnumType.STRING)
    private TowerStatus status = TowerStatus.IN_PROGRESS;

    private LocalDateTime startedAt = LocalDateTime.now();
}
