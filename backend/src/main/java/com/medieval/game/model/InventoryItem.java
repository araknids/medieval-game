package com.medieval.game.model;

import com.medieval.game.enums.ItemType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory_items")
@Data
@NoArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemType type;

    private int attackBonus;
    private int defenseBonus;
    private int healthBonus;

    // 1=Comum, 2=Incomum, 3=Raro, 4=Épico
    private int rarity = 1;

    private long sellPrice = 0;

    private boolean equipped = false;

    // Número de sockets disponíveis neste item (0-3)
    @Column(columnDefinition = "integer default 0")
    private int sockets = 0;

    @Column(columnDefinition = "boolean default false")
    private boolean guarded = false; // protegido pelo Templo

    @Column(columnDefinition = "TEXT")
    private String description; // lore do item

    private String origin; // onde foi encontrado

    // ── Durabilidade (sink econômico) ──────────────────────────────────────────
    // 0-100; começa cheia. Em 0, o item não aplica nenhum bônus até ser reparado.
    @Column(columnDefinition = "integer default 100")
    private int durability = 100;

    /** Item dá bônus apenas se tiver durabilidade > 0. */
    public boolean isBroken() { return durability <= 0; }

    public int getEffectiveAttack()  { return isBroken() ? 0 : attackBonus; }
    public int getEffectiveDefense() { return isBroken() ? 0 : defenseBonus; }
    public int getEffectiveHealth()  { return isBroken() ? 0 : healthBonus; }
}
