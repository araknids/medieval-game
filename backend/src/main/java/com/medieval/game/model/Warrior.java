package com.medieval.game.model;

import com.medieval.game.enums.BuffType;
import com.medieval.game.enums.WarriorClass;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "warriors")
@Data
@NoArgsConstructor
public class Warrior {

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
    private WarriorClass warriorClass;

    private int  level      = 1;
    private long experience = 0;

    // Stats base
    private int attack;
    private int defense;
    private int health;

    // Atributos
    private int strength     = 0;
    private int dexterity    = 0;
    private int constitution = 0;
    private int luck         = 0;
    private int availablePoints = 0;

    // ── HP com regen passiva (% 0-100, regenera 100% em 1 hora) ──
    @Column(columnDefinition = "integer default 100")
    private int currentHpSnapshot = 100;

    @Column(nullable = false)
    private LocalDateTime hpUpdatedAt = LocalDateTime.now();

    public int getCalculatedHpPercent() {
        long minutes = Duration.between(hpUpdatedAt, LocalDateTime.now()).toMinutes();
        int regen = (int)(minutes * 100.0 / 60.0);
        return Math.min(100, currentHpSnapshot + regen);
    }

    public boolean isKnockedOut() {
        return getCalculatedHpPercent() <= 0;
    }

    /** Aplica dano (em %). Regen começa automaticamente a partir do valor atual */
    public void applyDamagePercent(int percent) {
        currentHpSnapshot = Math.max(0, getCalculatedHpPercent() - percent);
        hpUpdatedAt       = LocalDateTime.now();
    }

    /** Cura para 100% */
    public void healFull() {
        currentHpSnapshot = 100;
        hpUpdatedAt       = LocalDateTime.now();
    }

    // ── Buff do Templo ──
    @Enumerated(EnumType.STRING)
    private BuffType activeBuff;
    private LocalDateTime buffExpiresAt;

    /** Segundo slot de buff — exclusivo para jogadores VIP */
    private BuffType activeBuff2;
    private LocalDateTime buffExpiresAt2;

    public boolean hasActiveBuff() {
        return activeBuff != null && buffExpiresAt != null
               && LocalDateTime.now().isBefore(buffExpiresAt);
    }

    public boolean hasActiveBuff2() {
        return activeBuff2 != null && buffExpiresAt2 != null
               && LocalDateTime.now().isBefore(buffExpiresAt2);
    }

    public void clearBuff() {
        activeBuff    = null;
        buffExpiresAt = null;
        activeBuff2   = null;
        buffExpiresAt2 = null;
    }

    private boolean onMission = false;

    public void levelUp() {
        level++;
        attack  += 2;
        defense += 2;
        health  += 15;
        availablePoints += 5;
    }

    public long expNeededForNextLevel() { return (long) level * 100; }

    // Stats totais (base + atributos, sem itens)
    public int getTotalBaseAttack()  { return attack  + strength; }
    public int getTotalBaseDefense() { return defense + (int)(constitution * 0.5); }
    public int getTotalBaseHealth()  { return health  + constitution * 5; }
    public int getEvasionChance()    { return 10 + dexterity; }
}
