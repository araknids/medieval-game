package com.medieval.game.model;

import com.medieval.game.enums.Gender;
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

    // Gênero do personagem — cosmético (base + peças Male/Female do paper-doll no Godot). [OUTFITS_FEMALE]
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "varchar(8) default 'MALE'")
    private Gender gender = Gender.MALE;

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

    // [LEADERBOARDS] Jogadores abatidos (PvP) — acumulado, alimenta o ranking "Slayer". +1 por vitória PvP.
    @Column(columnDefinition = "integer default 0")
    private int playerKills = 0;

    // [MERCADO_STEAM] SteamID64 da conta Steam linkada (null = não linkado). Setado via auth ticket do
    // cliente Godot (futuro). Pré-requisito p/ o Mercador Azul exportar itens pro inventário Steam.
    @Column(name = "steam_id", unique = true)
    private String steamId;

    // [ONBOARDING] true depois que o jogador viu a tela de boas-vindas (lore + 1ª missão). Persiste no
    // banco pra não reaparecer ao limpar cache. Default false.
    @Column(columnDefinition = "boolean default false")
    private boolean onboardingSeen = false;

    // [ONBOARDING] Deveres do Recruta — 3 quests de ENTREGA únicas (NPC pede recurso → XP+gold). Uma flag
    // por NPC; soft-wipe reseta junto com onboardingSeen. Doc: docs/PLANO_ONBOARDING.md
    @Column(columnDefinition = "boolean default false")
    private boolean starterGuardDone = false;
    @Column(columnDefinition = "boolean default false")
    private boolean starterPriestDone = false;
    @Column(columnDefinition = "boolean default false")
    private boolean starterShopDone = false;
    // [ONBOARDING v2] estado "aceita": NPC oferece (available) -> aceita (entra no diário) -> entrega (done).
    @Column(columnDefinition = "boolean default false")
    private boolean starterGuardAccepted = false;
    @Column(columnDefinition = "boolean default false")
    private boolean starterPriestAccepted = false;
    @Column(columnDefinition = "boolean default false")
    private boolean starterShopAccepted = false;

    // [I18N] Idioma preferido do jogador (ex.: "en", "pt"). O cliente lê isto e manda no header
    // Accept-Language; o backend serve o conteúdo (quests/lore/torre/erros) já no idioma. Default "en".
    @Column(columnDefinition = "varchar(5) default 'en'")
    private String language = "en";

    private int currentStamina = 100;

    @Column(nullable = false)
    private LocalDateTime staminaUpdatedAt = LocalDateTime.now();

    // [BUFF_NOVATO] Nos primeiros 3 dias da conta, estamina E HP regeneram 100% em 15 min (em vez de 60),
    // pra suavizar o "penhasco de estamina" do recém-chegado. Janela derivada de createdAt — sem coluna nova.
    // Soft-wipe reseta createdAt → re-concede o buff. O HP herda esta janela via Warrior.hpRegenMinutes().
    public static final int NEWBIE_BUFF_DAYS = 3;

    public boolean isNewbieBuffActive() {
        return createdAt != null
                && Duration.between(createdAt, LocalDateTime.now()).toDays() < NEWBIE_BUFF_DAYS;
    }

    /** Minutos p/ regen 100% (estamina e HP): 15 no buff de novato, 60 normal. [BUFF_NOVATO] */
    public int regenMinutes() { return isNewbieBuffActive() ? 15 : 60; }

    /** Horas restantes do buff de novato (0 se inativo) — p/ exibir na UI. */
    public long getNewbieBuffHoursLeft() {
        if (!isNewbieBuffActive()) return 0;
        long mins = Duration.between(LocalDateTime.now(), createdAt.plusDays(NEWBIE_BUFF_DAYS)).toMinutes();
        return Math.max(1, (long) Math.ceil(mins / 60.0));
    }

    // Estamina regenera 100% em 1h — ou 15 min no buff de novato (estamina é o gate). [SEM_TIMER][BUFF_NOVATO]
    public int getCalculatedStamina() {
        long minutes = Duration.between(staminaUpdatedAt, LocalDateTime.now()).toMinutes();
        int regen = (int) (minutes * 100.0 / regenMinutes());
        return Math.min(100, currentStamina + regen);
    }

    public long getMinutesToFullStamina() {
        int stamina = getCalculatedStamina();
        if (stamina >= 100) return 0;
        return (long) Math.ceil((100 - stamina) * regenMinutes() / 100.0);
    }

    @Enumerated(EnumType.STRING)
    private Location location = Location.TAVERN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guild_id")
    private Guild guild;

    // Total donated to current guild in bronze — reset on leave/join/disband
    @Column(columnDefinition = "bigint default 0")
    private long guildDonatedBronze = 0;

    // Roster de guerra: o líder marca até 15 membros p/ a batalha de território. [GUERRA_ROSTER]
    // Reset ao entrar/sair de guild. Se faltar pra 15, o sistema auto-preenche (prefere não-cansado).
    @Column(columnDefinition = "boolean default false")
    private boolean inWarRoster = false;

    // [GUERRA_FORMACAO] Posição na formação 3×5 da guerra: lane 0–2, depth 0–4. −1 = não posicionado.
    @Column(columnDefinition = "integer default -1")
    private int warLane = -1;
    @Column(columnDefinition = "integer default -1")
    private int warDepth = -1;

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

    // [QUESTS_INTERATIVAS] vipInstantQuestsToday/lastVipQuestDate removidos com o instant-start.
    // As colunas órfãs em prod (integer default 0 / date) são inofensivas (não dropadas).

    public boolean isVip() {
        return vipExpiresAt != null && java.time.LocalDateTime.now().isBefore(vipExpiresAt);
    }

    public int getArenaFightLimit() { return isVip() ? 10 : 5; }

    // ── PvP por flag (jogo sem timer): farmar zona PvP te deixa exposto por 1h. [PVP_FLAG] ──
    @Enumerated(EnumType.STRING)
    @Column(name = "pvp_flagged_zone")
    private com.medieval.game.enums.Zone pvpFlaggedZone;   // zona em que está exposto (null = não)
    private java.time.LocalDateTime pvpFlaggedUntil;        // fim do flag de exposição
    private java.time.LocalDateTime pvpShieldUntil;         // imunidade pós-derrota (saqueado 1x)

    public boolean isPvpFlagged() {
        return pvpFlaggedZone != null && pvpFlaggedUntil != null
               && java.time.LocalDateTime.now().isBefore(pvpFlaggedUntil);
    }
    public boolean isPvpShielded() {
        return pvpShieldUntil != null && java.time.LocalDateTime.now().isBefore(pvpShieldUntil);
    }
    public void clearPvpFlag() { pvpFlaggedZone = null; pvpFlaggedUntil = null; }

    // ── Pets: contador da pity da quest rara da Luna (sobe a chance a cada tentativa). [PETS] ──
    // Posse do pet é via PetRepository.existsByPlayerAndPetType (sem flag redundante aqui).
    @Column(columnDefinition = "integer default 0")
    private int petPityAttempts = 0;

    // [TITULOS] Título ativo escolhido (Achievement.name() já desbloqueado; null/"" = nenhum).
    @Column(name = "active_title", length = 40)
    private String activeTitle;

    // ── [DAILY] Recompensa de login diária (ciclo de 7 dias) ───────────────────
    /** Data da última coleta da daily; null = nunca coletou. Reset por comparação de data (sem scheduler). */
    @Column(name = "last_daily_claim_date")
    private java.time.LocalDate lastDailyClaimDate;
    /** Dias consecutivos coletados (streak). Faltar um dia zera; cicla a tabela de 7 dias por (streak-1)%7. */
    @Column(columnDefinition = "integer default 0")
    private int dailyStreak = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // M6: tokens emitidos ANTES deste instante são rejeitados (setado ao trocar/resetar a senha).
    // null = sem restrição (registro novo / nunca trocou a senha).
    private LocalDateTime tokenValidFrom;
}
