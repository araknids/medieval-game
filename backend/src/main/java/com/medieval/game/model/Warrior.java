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

    // [VARREDURA] Optimistic locking: o Warrior é a entidade mais salva (XP/HP/atributos). Sem isso, dois
    // POST /spend-point simultâneos gastavam 2 pontos de 1 (read-modify-write sem trava); heal de peixe
    // concorrente com dano de combate apagava um. Mesmo padrão do Player.version. [AUDITORIA C3]
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
    // [REBALANCE] Agilidade: golpes extra + esquiva. Coluna nova → default 0 p/ jogadores existentes.
    @Column(columnDefinition = "integer default 0")
    private int agility      = 0;
    private int availablePoints = 0;

    // [HABILIDADES] Pontos de habilidade (1 por level, separado dos atributos). Gastos em ClassAbility.
    @Column(columnDefinition = "integer default 0")
    private int abilityPoints = 0;

    // [LEADERBOARDS] Mobs (PvE) abatidos — acumulado, alimenta o ranking "Hunter". +1 por vitória PvE.
    @Column(columnDefinition = "integer default 0")
    private int mobKills = 0;

    // ── HP com regen passiva (% 0-100, regenera 100% em 1 hora) ──
    @Column(columnDefinition = "integer default 100")
    private int currentHpSnapshot = 100;

    @Column(nullable = false)
    private LocalDateTime hpUpdatedAt = LocalDateTime.now();

    // HP regenera 100% em 1h — ou 15 min no buff de novato (herdado do Player). [BUFF_NOVATO]
    public int getCalculatedHpPercent() {
        return getCalculatedHpPercent(hpRegenMinutes());
    }

    /** Overload p/ contextos detached (controllers): recebe a janela de regen do Player já carregado. */
    public int getCalculatedHpPercent(int regenMinutes) {
        long minutes = Duration.between(hpUpdatedAt, LocalDateTime.now()).toMinutes();
        int regen = (int)(minutes * 100.0 / regenMinutes);
        return Math.min(100, currentHpSnapshot + regen);
    }

    /** Janela de regen do HP — herda o buff de novato do Player. Fallback 60 min se o player estiver detached. */
    private int hpRegenMinutes() {
        try {
            if (player != null) return player.regenMinutes();
        } catch (RuntimeException lazyDetached) { /* fora de transação → sem buff */ }
        return 60;
    }

    public boolean isKnockedOut() {
        return getCalculatedHpPercent() <= 0;
    }

    public boolean isKnockedOut(int regenMinutes) {
        return getCalculatedHpPercent(regenMinutes) <= 0;
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

    // ── Buff da Taverna: stackável, +0.01%/stack em TODOS os stats, cap 100%, dura 5min, renova no gole. [TAVERNA] ──
    @Column(columnDefinition = "integer default 0")
    private int tavernBuffStacks = 0;
    @Column(name = "tavern_buff_expires_at")
    private LocalDateTime tavernBuffExpiresAt;

    public static final int TAVERN_BUFF_CAP = 10_000; // 100% (0.01% por stack)

    public boolean tavernBuffActive() {
        return tavernBuffStacks > 0 && tavernBuffExpiresAt != null
               && LocalDateTime.now().isBefore(tavernBuffExpiresAt);
    }
    /** Stacks que VALEM agora (0 se expirou). */
    public int activeTavernStacks() { return tavernBuffActive() ? Math.min(TAVERN_BUFF_CAP, tavernBuffStacks) : 0; }
    /** Multiplicador aplicado a todos os stats de combate (1.0 se inativo). */
    public double tavernBuffMultiplier() { return 1.0 + activeTavernStacks() * 0.0001; }

    public void clearBuff() {
        activeBuff    = null;
        buffExpiresAt = null;
        activeBuff2   = null;
        buffExpiresAt2 = null;
        clearMealBuff(); // refeição também some na derrota/KO. [COZINHA]
        // Encantamentos elementais também somem na derrota/KO. [ELEMENTOS]
        weaponElement = null; weaponElementUntil = null;
        armorElement  = null; armorElementUntil  = null;
        // Porre da Taverna também some no KO/derrota. [TAVERNA]
        tavernBuffStacks = 0; tavernBuffExpiresAt = null;
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
        abilityPoints   += 1; // 1 ponto de habilidade por level [HABILIDADES]
    }

    /** XP exponencial estilo Tibia: round(100 × level^1.8) */
    public long expNeededForNextLevel() {
        return Math.round(100.0 * Math.pow(level, 1.8));
    }

    // ── Stats totais (base + atributos, sem itens) ────────────────────────────

    /**
     * ATK base + atributo de dano da ARMA. [REBALANCE][CLASSES_ARMAS] Arma ranged (arco) escala com
     * DEX (precisão = dano do arco); melee escala com STR — independente da classe (a trava de arma
     * por classe foi removida). O chamador passa {@code rangedWeapon} (arma equipada é RANGED?).
     */
    public int getTotalBaseAttack(boolean rangedWeapon) {
        // [REBALANCE v2] O nível NÃO dá ATK grátis (bounded accuracy, à la D&D 5e): o canal de escala
        // pra quem não maximiza o atributo de dano é a ARMA (WeaponType.stats, independente de STR).
        // Tentamos +ATK/nível antes, mas QUALQUER valor fazia CON-puro dominar (CON é sink infinito).
        return attack + (rangedWeapon ? dexterity : strength);
    }

    /** DEF base (from equipment via inventory, no per-level or attribute growth). */
    public int getTotalBaseDefense() { return defense; }

    /**
     * HP base + Constituição com RETORNO DECRESCENTE em camadas [SOFT_CAP_CON] (modelo Dark Souls 3 Vigor):
     * 8/pt até CON 40, 4/pt de 41–80, 2/pt acima. CON continua sink INFINITO (bom p/ idle), mas perde o
     * valor marginal que fazia o tank de CON-puro dominar a atrição (linear×sem-teto = solução de canto).
     * HP é guardado como % → mexer no máximo não exige migração. Espelhado em CombatBalanceProbeTest.
     */
    public int getTotalBaseHealth() {
        int tier1 = Math.min(constitution, 40);
        int tier2 = Math.min(Math.max(constitution - 40, 0), 40);
        int tier3 = Math.max(constitution - 80, 0);
        return health + tier1 * 8 + tier2 * 4 + tier3 * 2;
    }

    /** AC (Armor Class) = 10 + DEX. Attacker's d20+bonus must meet or beat this to hit. */
    public int getArmorClass()       { return 10 + dexterity; }

    /** Attack Roll bonus = floor(STR / 20). Range 0-3 at attribute cap of 60. */
    public int getAttackBonus()      { return strength / 20; }

    /** Kept for backwards compatibility in API responses — same as getArmorClass(). */
    public int getEvasionChance()    { return getArmorClass(); }
}
