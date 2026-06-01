package com.medieval.game.model;

import com.medieval.game.enums.WarriorClass;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    private int level      = 1;
    private long experience = 0;

    // Stats base (da classe + level ups automáticos)
    private int attack;
    private int defense;
    private int health;

    // Atributos distribuídos pelo jogador
    private int strength     = 0;   // +1 ATK por ponto
    private int dexterity    = 0;   // +1% evasão por ponto
    private int constitution = 0;   // +5 HP e +0.5 DEF por ponto
    private int luck         = 0;   // +1% drop chance por ponto

    private int availablePoints = 0;

    // Sistema de trabalho — separado do combate
    @Column(columnDefinition = "integer default 1")
    private int  workLevel      = 1;

    @Column(columnDefinition = "bigint default 0")
    private long workExperience = 0;

    private boolean onMission = false;

    public void levelUp() {
        level++;
        attack  += 2;
        defense += 2;
        health  += 15;
        availablePoints += 5;
    }

    public long expNeededForNextLevel() {
        return (long) level * 100;
    }

    // XP necessária para o próximo nível de trabalho: 50, 100, 150, 200...
    public long workExpNeededForNextLevel() {
        return 50L * workLevel;
    }

    // Bônus de gold por hora de trabalho (+5% por nível acima do 1)
    public double workGoldBonus() {
        return 1.0 + (workLevel - 1) * 0.05;
    }

    // Stats totais (base + atributos, sem bônus de item)
    public int getTotalBaseAttack()  { return attack  + strength; }
    public int getTotalBaseDefense() { return defense + (int)(constitution * 0.5); }
    public int getTotalBaseHealth()  { return health  + constitution * 5; }
    public int getEvasionChance()    { return 10 + dexterity; }
}
