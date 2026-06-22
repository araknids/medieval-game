package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "guilds")
@Getter
@Setter
@NoArgsConstructor
public class Guild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    private String description;

    // Treasury stored in bronze (same unit as player currency) — spendable (territory upkeep).
    @Column(columnDefinition = "bigint default 0")
    private long gold = 0;

    // [GUILD_LEVEL_GOLD] Total gold ever contributed to the guild (donations). Only grows — never
    // decreases (spending the treasury does NOT lower it). Drives the guild level.
    @Column(columnDefinition = "bigint default 0")
    private long lifetimeGold = 0;

    @Column(columnDefinition = "integer default 1")
    private int level = 1;

    // [GUERRA_GUILDA] true se a guild já controlou um território ao menos uma vez (req. p/ declarar/ser declarada).
    @Column(columnDefinition = "boolean default false")
    private boolean everControlledTerritory = false;

    // [LEADERBOARDS] Kills acumulados em guerras de guilda — alimenta o ranking de guildas "War kills".
    @Column(columnDefinition = "bigint default 0")
    private long warKills = 0;

    @Column(nullable = false)
    private Long leaderId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // ── Nível derivado do gold acumulado (sem level-up manual). [GUILD_LEVEL_GOLD] ──
    public static final int MAX_LEVEL = 10;
    private static final long GOLD_CURVE_UNIT = 10_000L; // "prestígio": Lv10 = 450.000 bronze (45 ouro)

    /** Gold acumulado (bronze) necessário para ATINGIR o nível n: 10.000 × (n-1)·n/2. n≤1 → 0. */
    public static long goldThreshold(int n) {
        if (n <= 1) return 0;
        int capped = Math.min(n, MAX_LEVEL);
        return GOLD_CURVE_UNIT * (capped - 1) * capped / 2;
    }

    /** Maior nível (1..MAX_LEVEL) cujo limiar é coberto por {@code lifetimeBronze}. */
    public static int levelForGold(long lifetimeBronze) {
        int lvl = 1;
        for (int n = 2; n <= MAX_LEVEL; n++) {
            if (lifetimeBronze >= goldThreshold(n)) lvl = n; else break;
        }
        return lvl;
    }

    /** Recalcula o nível a partir do lifetimeGold. Monotônico pra cima — nunca rebaixa. */
    public void recomputeLevel() {
        this.level = Math.max(this.level, levelForGold(this.lifetimeGold));
    }

    /** Gold acumulado (bronze) p/ o PRÓXIMO nível; -1 se já no nível máximo. */
    public long goldForNextLevel() {
        return level >= MAX_LEVEL ? -1L : goldThreshold(level + 1);
    }

    /** Quanto falta (bronze) p/ o próximo nível; 0 se já maxado. */
    public long goldToNextLevel() {
        long next = goldForNextLevel();
        return next < 0 ? 0L : Math.max(0L, next - lifetimeGold);
    }

    /** Progresso (%) dentro do nível atual (0..100; 100 no nível máximo). */
    public int levelProgressPct() {
        if (level >= MAX_LEVEL) return 100;
        long lo = goldThreshold(level);
        long hi = goldThreshold(level + 1);
        if (hi <= lo) return 100;
        long p = (lifetimeGold - lo) * 100 / (hi - lo);
        return (int) Math.max(0, Math.min(100, p));
    }

    // Max members: 10 at level 1, +5 per additional level, capped at 50.
    public int maxMembers() {
        return Math.min(50, 10 + (level - 1) * 5);
    }

    // XP bonus % for all members — min(20, (level-1)*5)
    public int xpBonus() {
        return Math.min(20, (level - 1) * 5);
    }

    // Drop chance bonus % — min(7, max(0, level-2)*2)
    public int dropBonus() {
        return Math.min(7, Math.max(0, level - 2) * 2);
    }

    // Bronze reward bonus % — min(10, max(0, level-3)*5)
    public int bronzeBonus() {
        return Math.min(10, Math.max(0, level - 3) * 5);
    }
}
