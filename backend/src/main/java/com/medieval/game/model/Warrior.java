package com.medieval.game.model;

import com.medieval.game.enums.BuffType;
import com.medieval.game.enums.WarriorClass;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(name = "warriors")
@Getter
@Setter
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
    private int intellect    = 0;
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

    // ── Buff de refeição (slot "Bem Alimentado") — separado dos slots do Templo. [COZINHA] ──
    @Enumerated(EnumType.STRING)
    @Column(name = "meal_buff")
    private com.medieval.game.enums.Meal mealBuff;
    @Column(name = "meal_buff_expires_at")
    private LocalDateTime mealBuffExpiresAt;

    public boolean hasMealBuff() {
        return mealBuff != null && mealBuffExpiresAt != null
               && LocalDateTime.now().isBefore(mealBuffExpiresAt);
    }

    public void clearMealBuff() {
        mealBuff          = null;
        mealBuffExpiresAt = null;
    }

    // ── Encantamento elemental (Templo) — temporário (1h), some na derrota/KO. [ELEMENTOS] ──
    // Arma = elemento que você causa (ofensa); Armadura = seu elemento de defesa.
    @Enumerated(EnumType.STRING)
    @Column(name = "weapon_element", length = 10)
    private com.medieval.game.enums.Element weaponElement;
    @Column(name = "weapon_element_until")
    private LocalDateTime weaponElementUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "armor_element", length = 10)
    private com.medieval.game.enums.Element armorElement;
    @Column(name = "armor_element_until")
    private LocalDateTime armorElementUntil;

    /** Elemento da arma ATIVO (null se expirado/ausente). */
    public com.medieval.game.enums.Element getActiveWeaponElement() {
        return (weaponElement != null && weaponElementUntil != null
                && LocalDateTime.now().isBefore(weaponElementUntil)) ? weaponElement : null;
    }
    /** Elemento da armadura ATIVO (null se expirado/ausente). */
    public com.medieval.game.enums.Element getActiveArmorElement() {
        return (armorElement != null && armorElementUntil != null
                && LocalDateTime.now().isBefore(armorElementUntil)) ? armorElement : null;
    }

    public void clearBuff() {
        activeBuff    = null;
        buffExpiresAt = null;
        activeBuff2   = null;
        buffExpiresAt2 = null;
        clearMealBuff(); // refeição também some na derrota/KO. [COZINHA]
        // Encantamentos elementais também somem na derrota/KO. [ELEMENTOS]
        weaponElement = null; weaponElementUntil = null;
        armorElement  = null; armorElementUntil  = null;
    }

    // ── Postura de combate (tradeoff ATK/DEF) — vale em qualquer combate (PvE/PvP). [POSTURE] ──
    // Aplicada no WarriorStatsService.combatStats (multiplica atk/def). Toggle livre.
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(20) default 'BALANCED'")
    private com.medieval.game.enums.CombatPosture combatPosture = com.medieval.game.enums.CombatPosture.BALANCED;

    // [SEM_TIMER] o antigo flag onMission ("busy") foi removido — tudo é instantâneo, então não há
    // bloqueio cruzado de atividade. Cada atividade tem seu próprio check de sessão ativa.

    // ── War Fatigue (Guerra de Território) — força rotação do roster. [GUERRA_ROSTER] ──
    // Lutar em ciclos de guerra consecutivos acumula −10% nos stats por ciclo (teto −50%);
    // descansar 1 ciclo zera (o gap é detectado por warLastCycleFought != cycle-1). Cycle-based
    // (id = epoch/21600) p/ ser robusto a catch-up do cron, sem job de decay. SÓ vale na guerra.
    @Column(columnDefinition = "integer default 0")
    private int warFatigueStacks = 0;          // ciclos consecutivos lutados (0–5)

    @Column(columnDefinition = "bigint default 0")
    private long warLastCycleFought = 0;       // id do último ciclo em que foi escalado

    private static final int FATIGUE_PER_STACK = 10; // % por stack
    private static final int FATIGUE_MAX_STACKS = 5;  // teto = 50%

    /** Stacks que valem na batalha do ciclo {@code cycle} (0 se descansou o ciclo anterior). */
    public int incomingFatigueStacks(long cycle) {
        return warLastCycleFought == cycle - 1 ? warFatigueStacks : 0;
    }

    /** Debuff de cansaço (%) aplicado na batalha do ciclo {@code cycle}. */
    public int fatiguePctForCycle(long cycle) {
        return Math.min(FATIGUE_MAX_STACKS, incomingFatigueStacks(cycle)) * FATIGUE_PER_STACK;
    }

    /** Registra que foi escalado no ciclo {@code cycle}: acumula stack (consecutivo) ou reinicia (após descanso). */
    public void recordWarParticipation(long cycle) {
        int incoming = incomingFatigueStacks(cycle);
        warFatigueStacks   = Math.min(FATIGUE_MAX_STACKS, incoming + 1);
        warLastCycleFought = cycle;
    }

    /** Cansaço (%) que valerá na PRÓXIMA batalha (que resolve currentCycleId+1) — p/ exibir na UI. */
    public int currentFatiguePct(long currentCycleId) {
        return warLastCycleFought == currentCycleId
                ? Math.min(FATIGUE_MAX_STACKS, warFatigueStacks) * FATIGUE_PER_STACK : 0;
    }

    public void levelUp() {
        level++;
        availablePoints += 2; // 2 pts/level — no auto ATK/DEF/HP per level (attributes handle growth)
    }

    /** XP exponencial estilo Tibia: round(100 × level^1.8) */
    public long expNeededForNextLevel() {
        return Math.round(100.0 * Math.pow(level, 1.8));
    }

    // ── Stats totais (base + atributos, sem itens) ────────────────────────────

    /** ATK base + Força (STR). Damage dealt on hit. */
    public int getTotalBaseAttack()  { return attack + strength; }

    /** DEF base (from equipment via inventory, no per-level or attribute growth). */
    public int getTotalBaseDefense() { return defense; }

    /** HP base + Constituição × 8 HP por ponto (CON is the infinite-growth attribute). */
    public int getTotalBaseHealth()  { return health + constitution * 8; }

    /** AC (Armor Class) = 10 + DEX. Attacker's d20+bonus must meet or beat this to hit. */
    public int getArmorClass()       { return 10 + dexterity; }

    /** Attack Roll bonus = floor(STR / 20). Range 0-3 at attribute cap of 60. */
    public int getAttackBonus()      { return strength / 20; }

    /** Kept for backwards compatibility in API responses — same as getArmorClass(). */
    public int getEvasionChance()    { return getArmorClass(); }
}
