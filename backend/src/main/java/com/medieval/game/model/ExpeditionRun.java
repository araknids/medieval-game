package com.medieval.game.model;

import com.medieval.game.enums.Element;
import com.medieval.game.enums.ExpeditionNodeType;
import com.medieval.game.enums.ExpeditionSource;
import com.medieval.game.enums.ExpeditionStatus;
import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.Zone;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * [INCURSAO] Uma Incursão (Delve): run roguelike de mapa ramificado. Quest e coleta produzem o mesmo
 * tipo de run, parametrizado por {@link ExpeditionSource} (KINGDOM = gear, ZONE = recursos).
 *
 * <p>A tabela é auto-criada pelo Hibernate ddl-auto (sem patch no SchemaMigrator). O loot de GEAR é
 * carregado como {@code InventoryItem.runPending=true} (fora da bag); só os escalares (bronze/xp) e os
 * recursos (CSV) ficam aqui. Ver docs/PLANO_INCURSAO.md.
 */
@Entity
@Table(name = "expedition_runs")
@Getter
@Setter
@NoArgsConstructor
public class ExpeditionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Linha de estado mutada por requisições sequenciais (choose/extract) → optimistic lock.
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warrior_id")
    private Warrior warrior;

    // ── Fonte / theming ───────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExpeditionSource source;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private Kingdom kingdom;      // tema/loot do reino (KINGDOM)

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Zone zone;           // tier/PvP (ZONE)

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SkillType skillType; // profissão coletada (ZONE); null = só combate

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Element element;     // elemento da área (opcional)

    // ── Mapa / posição ────────────────────────────────────────────────
    @Column(columnDefinition = "integer default 4")
    private int depth = 4;        // total de camadas; última = BOSS
    @Column(columnDefinition = "integer default 0")
    private int currentLayer = 0; // 0-based; camada aguardando escolha
    @Column(columnDefinition = "integer default 1")
    private int tier = 1;         // dificuldade 1..3 (escala monstro/loot)
    @Column(columnDefinition = "bigint default 0")
    private long seed = 0;        // seed do mapa (= id no start) → determinístico

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExpeditionStatus status = ExpeditionStatus.IN_PROGRESS;

    /** Mapa procedural serializado (ExpeditionMapGenerator.Map em JSON). [INCURSAO] */
    @Column(columnDefinition = "TEXT")
    private String mapJson;

    /** Quando NODE_PENDING: qual nó abriu a decisão interna (espelha KingdomActiveQuest.pendingOptionId). */
    @Column(length = 60)
    private String pendingNodeId;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ExpeditionNodeType pendingNodeType;
    /** Quando NODE_PENDING de EVENTO: qual KingdomQuestType fornece o diálogo. */
    @Column(length = 60)
    private String pendingEventQuest;

    // ── [INCURSAO_EVENTOS] Eventos NATIVOS da Incursão (pacto/loja/altar/santuário) ──
    /** Tipo do evento nativo pendente: PACT / SHOP / ALTAR / SANCTUARY (null = é quest de reino). */
    @Column(length = 20)
    private String pendingDelveEvent;
    /** Dados do evento (ex.: ofertas da loja em JSON), gerados no choose e lidos na resolução/diálogo. */
    @Column(columnDefinition = "TEXT")
    private String pendingEventData;
    /** Modificadores de combate da run (pactos/bênçãos): JSON {stat:pct}. Aplicados em TODA batalha. */
    @Column(columnDefinition = "TEXT")
    private String runModsJson;

    // ── Bolsa carregada (ainda NÃO no inventário) ─────────────────────
    // Gear carregado vive como InventoryItem.runPending=true; aqui só escalares + recursos (CSV).
    @Column(columnDefinition = "bigint default 0")  private long carriedBronze = 0;
    @Column(columnDefinition = "bigint default 0")  private long carriedXp     = 0;
    /** Recursos carregados como CSV compacto "TYPE:qty,TYPE:qty" (ResourceType.name():long). */
    @Column(columnDefinition = "TEXT")              private String carriedResources;

    // ── Sacado/garantido (travado por CAMP/extração; ledger informativo p/ a UI) ──
    @Column(columnDefinition = "bigint default 0")  private long securedBronze = 0;
    @Column(columnDefinition = "bigint default 0")  private long securedXp     = 0;
    @Column(columnDefinition = "TEXT")              private String securedResources;

    @Column(columnDefinition = "TEXT")
    private String battleLog;     // log do último combate (string[] join "\n")

    private LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime resolvedAt;

    public boolean isActive() {
        return status == ExpeditionStatus.IN_PROGRESS || status == ExpeditionStatus.NODE_PENDING;
    }
}
