package com.medieval.game.model;

import com.medieval.game.enums.Location;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "players")
@Data
@NoArgsConstructor
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    // Adiciona bronze e auto-converte sem depender de service
    public void addBronzeAmount(long amount) {
        long total = totalBronze() + amount;
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

    public int getCalculatedStamina() {
        long minutes = Duration.between(staminaUpdatedAt, LocalDateTime.now()).toMinutes();
        int regen = (int) (minutes * 100.0 / 120.0);
        return Math.min(100, currentStamina + regen);
    }

    public long getMinutesToFullStamina() {
        int stamina = getCalculatedStamina();
        if (stamina >= 100) return 0;
        return (long) Math.ceil((100 - stamina) * 120.0 / 100.0);
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

    /** Último uso da cura instantânea via SoulStone (CD de 30 min) */
    private LocalDateTime lastSoulstoneHealAt;

    /** Expansão de inventário comprada com SoulStone (10 → 20 slots) */
    @Column(columnDefinition = "boolean default false")
    private boolean inventoryExpanded = false;

    public int getMaxInventorySlots() { return inventoryExpanded ? 20 : 10; }

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
