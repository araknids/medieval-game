package com.medieval.game.model;

import com.medieval.game.enums.MountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Uma montaria que o jogador POSSUI (coleção). Compra → vira dele pra sempre; equipa 1 por vez.
 * A redução de estamina vem de {@link MountType#staminaReductionPct} da montaria equipada.
 * Ver docs/PLANO_ESTABULO.md.
 */
@Entity
@Table(name = "mounts")
@Getter
@Setter
@NoArgsConstructor
public class Mount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MountType mountType;

    @Column(columnDefinition = "boolean default false")
    private boolean equipped = false;
}
