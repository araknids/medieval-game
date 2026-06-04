package com.medieval.game.model;

import com.medieval.game.enums.Affix;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

/**
 * Um afixo rolado num item (Itens V2). Tabela filha de inventory_items — carregada em
 * batch (findAllByItemIn) para respeitar open-in-view=false, igual às joias.
 */
@Entity
@Table(name = "item_affixes")
@Getter
@Setter
@NoArgsConstructor
public class ItemAffix {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem item;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Affix affix;

    @Column(nullable = false)
    private int magnitude;
}
