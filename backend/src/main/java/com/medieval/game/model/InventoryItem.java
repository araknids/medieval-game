package com.medieval.game.model;

import com.medieval.game.enums.ItemType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory_items")
@Getter
@Setter
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

    // 1=Comum, 2=Incomum, 3=Raro, 4=Épico, 5=Lendário
    private int rarity = 1;

    // Itens V3: nível do item (fixo). Poder = nível × multiplicador de raridade.
    // Requisito pra equipar: itemLevel ≤ nível do guerreiro.
    @Column(columnDefinition = "integer default 1")
    private int itemLevel = 1;

    private long sellPrice = 0;

    private boolean equipped = false;

    // Número de sockets disponíveis neste item (0-3)
    @Column(columnDefinition = "integer default 0")
    private int sockets = 0;

    @Column(columnDefinition = "boolean default false")
    private boolean guarded = false; // protegido pelo Templo

    // Inventário V2: item guardado no stash (não conta na bag; não pode estar equipado).
    @Column(columnDefinition = "boolean default false")
    private boolean stashed = false;

    // [PVP_FLAG] Travado por entrar numa zona PvP: o item ficou EXPOSTO (snapshot na entrada).
    // Enquanto o dono está flagged, não pode vender/stashar/guardar e pode ser saqueado num raid.
    @Column(columnDefinition = "boolean default false")
    private boolean pvpLocked = false;

    // [LEILAO] Item anunciado na Casa de Leilão: continua do dono, mas sai da bag (não conta/aparece;
    // não dá pra equipar/vender/encaixar). Volta na expiração/cancelamento; muda de dono na venda.
    @Column(columnDefinition = "boolean default false")
    private boolean listed = false;

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
