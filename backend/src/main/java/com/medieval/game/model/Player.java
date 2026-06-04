package com.medieval.game.model;

import com.medieval.game.enums.Location;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "players")
@Getter
@Setter
@NoArgsConstructor
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: protege double-spend de moeda e contadores diários (VIP/cura). [AUDITORIA C3]
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    // Sistema de 3 moedas: 100 bronze = 1 prata, 100 prata = 1 ouro
    @Column(columnDefinition = "bigint default 0")
    private long bronze = 0;

    @Column(columnDefinition = "bigint default 50")
    private long silver = 50; // novos jogadores começam com 50 prata

    @Column(columnDefinition = "bigint default 0")
    private long gold   = 0;

    // Total em bronze para comparações
    public long totalBronze() {
        return bronze + silver * 100L + gold * 10_000L;
    }

    // Adiciona bronze e auto-converte sem depender de service.
    // Clamp em 0: amount negativo (ex.: penalidade de PvP) nunca deixa o saldo
    // devedor — resto de negativo em Java é negativo e corromperia os 3 campos. [AUDITORIA C4]
    public void addBronzeAmount(long amount) {
        long total = Math.max(0, totalBronze() + amount);
        this.gold   = total / 10_000L;
        this.silver = (total % 10_000L) / 100L;
        this.bronze = total % 100L;
    }

    private int rankPoints    = 1000;
    private int arenaWins     = 0;
    private int arenaLosses   = 0;
    @Column(columnDefinition = "integer default 0")
    private int towerBestFloor = 0; // melhor andar já alcançado na Torre Infernal

    private int currentStamina = 100;

    @Column(nullable = false)
    private LocalDateTime staminaUpdatedAt = LocalDateTime.now();

    // Estamina regenera 100% em 1h (jogo sem timer — estamina é o gate). [SEM_TIMER]
    public int getCalculatedStamina() {
        long minutes = Duration.between(staminaUpdatedAt, LocalDateTime.now()).toMinutes();
        int regen = (int) (minutes * 100.0 / 60.0);
        return Math.min(100, currentStamina + regen);
    }

    public long getMinutesToFullStamina() {
        int stamina = getCalculatedStamina();
        if (stamina >= 100) return 0;
        return (long) Math.ceil((100 - stamina) * 60.0 / 100.0);
    }

    @Enumerated(EnumType.STRING)
    private Location location = Location.TAVERN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guild_id")
    private Guild guild;

    // Total donated to current guild in bronze — reset on leave/join/disband
    @Column(columnDefinition = "bigint default 0")
    private long guildDonatedBronze = 0;

    // ── SoulStone (moeda VIP) ─────────────────────────────────────────────────
    @Column(columnDefinition = "integer default 0")
    private int soulStones = 0;

    /** Último uso da cura instantânea via SoulStone (controle de CD 30 min) */
    private LocalDateTime lastSoulstoneHealAt;

    /** Expansão de bag comprada com SoulStone (10 → 20 slots) */
    @Column(columnDefinition = "boolean default false")
    private boolean inventoryExpanded = false;

    // Inventário V2: bag free 30 / VIP (ou expandida com SoulStone) 50. Recursos contam slot por unidade.
    public int getMaxInventorySlots() { return inventoryExpanded || isVip() ? 50 : 30; }

    // ── VIP Status ────────────────────────────────────────────────────────────
    /** Timestamp de expiração do VIP; null = sem VIP */
    private java.time.LocalDateTime vipExpiresAt;

    /** Cura grátis VIP — CD de 10 minutos */
    private java.time.LocalDateTime lastVipHealAt;

    /** Lutas de arena feitas hoje (reset meia-noite UTC) */
    @Column(columnDefinition = "integer default 0")
    private int arenaFightsToday = 0;

    /** Data do último reset do counter de arena */
    private java.time.LocalDate lastArenaFightDate;

    /** Missões instantâneas usadas hoje (reset meia-noite UTC) */
    @Column(columnDefinition = "integer default 0")
    private int vipInstantQuestsToday = 0;

    /** Data do último reset do counter de missões instantâneas */
    private java.time.LocalDate lastVipQuestDate;

    public boolean isVip() {
        return vipExpiresAt != null && java.time.LocalDateTime.now().isBefore(vipExpiresAt);
    }

    public int getArenaFightLimit() { return isVip() ? 10 : 5; }

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // M6: tokens emitidos ANTES deste instante são rejeitados (setado ao trocar/resetar a senha).
    // null = sem restrição (registro novo / nunca trocou a senha).
    private LocalDateTime tokenValidFrom;
}
