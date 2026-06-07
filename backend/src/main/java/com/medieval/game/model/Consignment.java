package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * [MERCADO_STEAM] Item entregue ao Mercador Azul pra exportar pro mercado da Steam.
 * HELD = em escrow (saiu da bag); LINKED = concedido ao inventário Steam (Community Market);
 * SOLD = vendido lá fora (saldo Steam pro jogador); RETURNED = devolvido pra bag.
 * Enquanto a Steam está desligada, fica em HELD (dá pra cancelar = devolver). Ver docs/PLANO_MERCADO_STEAM.md.
 */
@Entity
@Table(name = "consignments")
@Getter
@Setter
@NoArgsConstructor
public class Consignment {

    public enum Status { HELD, LINKED, SOLD, RETURNED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private InventoryItem item;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** Itemdef da Steam mapeado pra este item (placeholder até existir o catálogo na Steam). */
    @Column(name = "steam_item_def")
    private String steamItemDef;

    /** Instância concedida no inventário Steam (stub/real). Null enquanto HELD. */
    @Column(name = "steam_item_instance")
    private String steamItemInstance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.HELD;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    public boolean isActive() { return status == Status.HELD || status == Status.LINKED; }
}
