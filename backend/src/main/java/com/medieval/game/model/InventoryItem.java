package com.medieval.game.model;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.WeaponCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

// [AUDITORIA_DUPE] @DynamicUpdate: o UPDATE só grava as colunas SUJAS, não a linha inteira — defesa
// extra contra clobber de player_id num race comprar+cancelar do leilão (F-1). @Version serializa.
@Entity
@DynamicUpdate
@Table(name = "inventory_items")
@Getter
@Setter
@NoArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // [AUDITORIA_DUPE] Optimistic lock: serializa transações concorrentes sobre o MESMO item
    // (sell/list/equip/stash/buy paralelos). A 2ª transação falha no commit → 409 "tente de novo".
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItemType type;

    // [CLASSES_ARMAS] Só p/ ItemType.WEAPON; null = arma legada (tratada como MELEE).
    // Definida no make() a partir do nome da arma (palavra de arco → RANGED).
    @Enumerated(EnumType.STRING)
    @Column(name = "weapon_category", length = 10)
    private WeaponCategory weaponCategory;

    private int attackBonus;
    private int defenseBonus;
    private int healthBonus;

    // [CLASSES_ARMAS] Stats secundários base (hoje só em armas, via perfil do WeaponType).
    // Entram no combate via WarriorStatsService.equippedGear (como os afixos).
    @Column(columnDefinition = "integer default 0")
    private int strBonus = 0;
    @Column(columnDefinition = "integer default 0")
    private int dexBonus = 0;
    @Column(columnDefinition = "integer default 0")
    private int lukBonus = 0;

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

    // [MERCADO_STEAM] Item entregue ao Mercador Azul (consignado): sai da bag igual ao `listed`.
    // Volta na devolução; muda de dono ao ser vendido na Steam (futuro).
    @Column(columnDefinition = "boolean default false")
    private boolean consigned = false;

    @Column(columnDefinition = "TEXT")
    private String description; // lore do item

    private String origin; // onde foi encontrado

    // ── Durabilidade (sink econômico) ──────────────────────────────────────────
    // 0-100; começa cheia. Em 0, o item não aplica nenhum bônus até ser reparado.
    @Column(columnDefinition = "integer default 100")
    private int durability = 100;

    // [MERCADOR] Quem forjou este item (playerId). Usado pelo bônus de self-crafted do Mercador.
    @Column(name = "crafted_by")
    private Long craftedBy;

    /** Item dá bônus apenas se tiver durabilidade > 0. */
    public boolean isBroken() { return durability <= 0; }

    /** [MERCADOR] true se este item foi forjado pelo próprio jogador {@code playerId}. */
    public boolean isSelfCraftedBy(Long playerId) { return craftedBy != null && craftedBy.equals(playerId); }

    public int getEffectiveAttack()  { return isBroken() ? 0 : attackBonus; }
    public int getEffectiveDefense() { return isBroken() ? 0 : defenseBonus; }
    public int getEffectiveHealth()  { return isBroken() ? 0 : healthBonus; }
    public int getEffectiveStr()     { return isBroken() ? 0 : strBonus; }
    public int getEffectiveDex()     { return isBroken() ? 0 : dexBonus; }
    public int getEffectiveLuk()     { return isBroken() ? 0 : lukBonus; }

    /** Categoria efetiva da arma: arma legada (null) conta como MELEE. Não-arma → null. [CLASSES_ARMAS] */
    public WeaponCategory effectiveWeaponCategory() {
        if (type != ItemType.WEAPON) return null;
        return weaponCategory != null ? weaponCategory : WeaponCategory.MELEE;
    }
}
