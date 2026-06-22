package com.medieval.game.model;

import com.medieval.game.enums.Kingdom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/**
 * [LEADERBOARDS] Quantas "incursões" (ajudas) um jogador já fez NAQUELE território — alimenta o
 * ranking "Território" (quem mais ajudou a cidade). 1 linha por (player, território); o contador
 * é incrementado em atividades de território (hoje: quest completada no reino). Quando a Incursão
 * roguelike existir, cada run concluída no reino também incrementa este mesmo contador.
 * Tabela auto-criada pelo ddl-auto (só os contadores em tabelas existentes precisam de SchemaMigrator).
 */
@Entity
@Table(name = "territory_contributions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "kingdom"}))
@Getter
@Setter
@NoArgsConstructor
public class TerritoryContribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Kingdom kingdom;

    @Column(columnDefinition = "integer default 0")
    private int incursions = 0;

    public TerritoryContribution(Player player, Kingdom kingdom) {
        this.player = player;
        this.kingdom = kingdom;
    }
}
