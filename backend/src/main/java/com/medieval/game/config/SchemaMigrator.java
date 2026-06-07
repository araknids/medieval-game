package com.medieval.game.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies manual schema patches that Hibernate ddl-auto=update cannot handle:
 * - PostgreSQL check constraints on enum columns (must be dropped and recreated
 *   when new enum values are added, since Hibernate does not update them automatically).
 *
 * Each patch uses TRY/CATCH semantics via a DO block to be fully idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMigrator {

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        patchZoneActivityRoleCheck();
        patchPlayerSoulStoneColumns();
        patchMailItemColumns();
        patchPlayerVipColumns();
        patchWarriorBuff2Columns();
        patchWarriorIntellectColumn();
        patchInventoryItemDurabilityColumn();
        patchOptimisticLockVersionColumns();
        patchTerritoryLastResolvedCycleColumn();
        patchWarFatigueAndRosterColumns();
        patchGuildLifetimeGoldColumn();
        patchWarriorCombatPostureColumn();
        patchPlayerPetPityColumn();
        patchGuildEverControlledColumn();
        patchInventoryListedColumn();
        patchInventoryWeaponCategoryColumn();
        patchElementColumns();
        patchAbilityPointsColumn();
        patchStashColumns();
        patchKingdomQuestWindowColumn();
        dropWarriorOnMissionColumn();
        dropStaleEnumCheckConstraints();
        purgeStaleEnumRows();
        patchHotIndexes();
    }

    /**
     * [AUDITORIA_2 A3] Índices nas FKs/colunas quentes. O PostgreSQL NÃO indexa coluna de FK
     * automaticamente, e ddl-auto=update não cria índices p/ @ManyToOne — então as queries mais
     * chamadas (warriorRepository.findByPlayer, inventory/zone por player, ranking) viram full scan
     * que cresce com o nº de jogadores. CREATE INDEX IF NOT EXISTS funciona em Postgres e H2; cada um
     * isolado p/ um não bloquear o outro. (As tabelas com unique composto já têm índice — não repetir.)
     */
    private void patchHotIndexes() {
        String[] idx = {
            "CREATE INDEX IF NOT EXISTS idx_warriors_player         ON warriors(player_id)",
            "CREATE INDEX IF NOT EXISTS idx_inventory_items_player  ON inventory_items(player_id)",
            "CREATE INDEX IF NOT EXISTS idx_zone_activities_player  ON zone_activities(player_id)",
            "CREATE INDEX IF NOT EXISTS idx_item_affixes_item       ON item_affixes(inventory_item_id)",
            "CREATE INDEX IF NOT EXISTS idx_kingdom_quests_player   ON kingdom_active_quests(player_id)",
            "CREATE INDEX IF NOT EXISTS idx_arena_matches_chal      ON arena_matches(challenger_id)",
            "CREATE INDEX IF NOT EXISTS idx_mail_recipient          ON mail(recipient_player_id)",
            "CREATE INDEX IF NOT EXISTS idx_auction_status          ON auction_listings(status)",
            "CREATE INDEX IF NOT EXISTS idx_auction_seller          ON auction_listings(seller_id)",
            "CREATE INDEX IF NOT EXISTS idx_guild_wars_a            ON guild_wars(guild_a_id)",
            "CREATE INDEX IF NOT EXISTS idx_guild_wars_b            ON guild_wars(guild_b_id)",
            "CREATE INDEX IF NOT EXISTS idx_guild_wars_status       ON guild_wars(status)",
            "CREATE INDEX IF NOT EXISTS idx_players_rank_points     ON players(rank_points)",      // matchmaking/leaderboard [A6]
            "CREATE INDEX IF NOT EXISTS idx_players_tower_floor     ON players(tower_best_floor)", // leaderboard da torre [A6]
        };
        int ok = 0;
        for (String sql : idx) {
            try { jdbc.execute(sql); ok++; }
            catch (Exception e) { log.warn("[SchemaMigrator] index skipped: {} — {}", sql.trim(), e.getMessage()); }
        }
        log.info("[SchemaMigrator] hot FK/lookup indexes ensured ({}/{})", ok, idx.length);
    }

    // Inventário V2: coluna `stashed` (bag vs stash) em inventory_items e resource_inventory.
    // A chave única de resource_inventory passou de (player,type) → (player,type,stashed) para
    // permitir a mesma resource na bag E no stash. Dropa a unique antiga e recria a correta. [INVENTARIO_V2]
    private void patchStashColumns() {
        try {
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS stashed boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE resource_inventory ADD COLUMN IF NOT EXISTS stashed boolean NOT NULL DEFAULT false");
            jdbc.execute("""
                DO $$
                DECLARE r record;
                BEGIN
                    FOR r IN SELECT con.conname FROM pg_constraint con
                             JOIN pg_class     rel ON rel.oid = con.conrelid
                             JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                             WHERE rel.relname = 'resource_inventory' AND nsp.nspname = 'public'
                               AND con.contype = 'u'
                    LOOP EXECUTE format('ALTER TABLE resource_inventory DROP CONSTRAINT %I', r.conname); END LOOP;
                    BEGIN
                        ALTER TABLE resource_inventory
                            ADD CONSTRAINT uk_resource_inv_player_type_stashed
                            UNIQUE (player_id, resource_type, stashed);
                    EXCEPTION WHEN duplicate_table THEN NULL; WHEN duplicate_object THEN NULL;
                    END;
                END $$;
                """);
            log.info("[SchemaMigrator] stash columns + resource_inventory unique key ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] stash columns patch failed: {}", e.getMessage());
        }
    }

    // Reinos V2 renomeou os valores de Kingdom (antes Territory: DESFILADEIRO_DO_OSSO…) e reescreveu
    // KingdomQuestType. Linhas antigas no banco ainda guardam nomes que o enum atual não tem mais; o
    // Hibernate ESTOURA ao carregá-las via findAll() (ex.: abrir a aba World em getAllTerritories(),
    // ou o soft-wipe). Self-heal no boot: apaga qualquer linha cujo valor de enum não exista mais.
    // Os nomes válidos vêm do próprio enum (não hardcode → sobrevive a renomeações futuras).
    // Idempotente e independente do APP_MAINTENANCE_SOFT_WIPE. [REINOS_V2]
    private void purgeStaleEnumRows() {
        String validKingdoms = inList(java.util.Arrays.stream(com.medieval.game.enums.Kingdom.values())
                .map(Enum::name).toList());
        purgeWhereNotIn("territory_controls",     "territory",  validKingdoms);
        purgeWhereNotIn("territory_declarations", "territory",  validKingdoms);
        purgeWhereNotIn("territory_battle_logs",  "territory",  validKingdoms);
        purgeWhereNotIn("gathering_sessions",     "kingdom",    validKingdoms);
        purgeWhereNotIn("kingdom_active_quests",  "kingdom",    validKingdoms);

        String validQuestTypes = inList(java.util.Arrays.stream(com.medieval.game.enums.KingdomQuestType.values())
                .map(Enum::name).toList());
        purgeWhereNotIn("kingdom_active_quests",  "quest_type", validQuestTypes);
    }

    // Monta "'A','B','C'" a partir dos nomes do enum (alfanumérico + underscore → seguro p/ SQL).
    private static String inList(java.util.List<String> names) {
        return names.stream().map(n -> "'" + n + "'").collect(java.util.stream.Collectors.joining(","));
    }

    private void purgeWhereNotIn(String table, String col, String validCsv) {
        try {
            int n = jdbc.update("DELETE FROM " + table + " WHERE " + col +
                    " IS NOT NULL AND " + col + " NOT IN (" + validCsv + ")");
            if (n > 0) log.warn("[SchemaMigrator] purged {} stale-enum row(s) from {}.{}", n, table, col);
        } catch (Exception e) {
            log.warn("[SchemaMigrator] stale-enum purge on {}.{} failed: {}", table, col, e.getMessage());
        }
    }

    // Reinos V2 adicionou/alterou valores em enums (SkillType.GARIMPO, novos ResourceType,
    // KingdomQuestType reescrito, Kingdom no lugar de Territory). Os check constraints que o
    // Hibernate criou na 1ª vez ficam defasados e rejeitam os novos valores (ex.: GARIMPO em
    // skill_levels). Como a validação do enum já é feita na camada JPA, derrubamos esses checks.
    // Genérico: acha e dropa qualquer CHECK que referencie as colunas de enum afetadas. [REINOS_V2]
    // Inclui warrior_class: a check antiga só aceitava 'WARRIOR' e rejeitaria RECRUIT/ARCHER. [CLASSES]
    private void dropStaleEnumCheckConstraints() {
        try {
            jdbc.execute("""
                DO $$
                DECLARE r record;
                BEGIN
                    FOR r IN
                        SELECT rel.relname AS tbl, con.conname AS con
                        FROM pg_constraint con
                        JOIN pg_class     rel ON rel.oid = con.conrelid
                        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
                        WHERE con.contype = 'c'
                          AND nsp.nspname = 'public'
                          AND rel.relname IN (
                              'skill_levels', 'resource_inventory', 'kingdom_active_quests',
                              'territory_controls', 'territory_declarations', 'territory_battle_logs',
                              'gathering_sessions', 'warriors', 'meal_inventory', 'pets')
                          AND pg_get_constraintdef(con.oid) ~ '(skill_type|resource_type|quest_type|kingdom|territory|meal|pet_type|warrior_class)'
                    LOOP
                        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, r.con);
                    END LOOP;
                END
                $$;
                """);
            log.info("[SchemaMigrator] stale enum check constraints dropped (Reinos V2)");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] enum check constraint patch failed: {}", e.getMessage());
        }
    }

    // territory_controls: add last_resolved_cycle_id (idempotent cron / catch-up)
    private void patchTerritoryLastResolvedCycleColumn() {
        try {
            jdbc.execute("ALTER TABLE territory_controls ADD COLUMN IF NOT EXISTS last_resolved_cycle_id bigint NOT NULL DEFAULT 0");
            log.info("[SchemaMigrator] territory_controls last_resolved_cycle_id column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] territory_controls last_resolved_cycle_id patch failed: {}", e.getMessage());
        }
    }

    // Trava de arma por classe: categoria (MELEE/RANGED) da arma. Nullable — null = arma legada
    // (tratada como MELEE no código; todo arqueiro é novo, todo item antigo é espada). [CLASSES_ARMAS]
    private void patchInventoryWeaponCategoryColumn() {
        try {
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS weapon_category varchar(10)");
            // Stats secundários base (perfil do tipo de arma) + item_level no mail (preserva o nível). [CLASSES_ARMAS]
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS str_bonus integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS dex_bonus integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS luk_bonus integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_level integer NOT NULL DEFAULT 1");
            log.info("[SchemaMigrator] inventory_items.weapon_category/str/dex/luk + mail.item_level columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] inventory_items.weapon_category patch failed: {}", e.getMessage());
        }
    }

    // Habilidades de classe: pontos por level (tabela warrior_abilities é auto-criada pelo ddl-auto). [HABILIDADES]
    private void patchAbilityPointsColumn() {
        try {
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS ability_points integer NOT NULL DEFAULT 0");
            // [REBALANCE] Agilidade: novo atributo (golpes extra + esquiva). Default 0 p/ jogadores existentes.
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS agility integer NOT NULL DEFAULT 0");
            // [GUERRA_FORMACAO] posição na formação 3×5 da guerra (−1 = não posicionado).
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS war_lane  integer NOT NULL DEFAULT -1");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS war_depth integer NOT NULL DEFAULT -1");
            // [TITULOS] título ativo escolhido (player_achievements é auto-criada pelo ddl-auto).
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS active_title varchar(40)");
            log.info("[SchemaMigrator] warriors.ability_points + players war formation + active_title columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] warriors.ability_points patch failed: {}", e.getMessage());
        }
    }

    // Elementos: encantamento temporário (arma/armadura) no guerreiro + elemento da área da zona. [ELEMENTOS]
    private void patchElementColumns() {
        try {
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS weapon_element       varchar(10)");
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS weapon_element_until timestamp");
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS armor_element        varchar(10)");
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS armor_element_until  timestamp");
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS element        varchar(10)");
            // [ZONA_CHEFE] chefe errante pendente
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS boss_level integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS boss_name  varchar(60)");
            log.info("[SchemaMigrator] element + zone boss columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] element columns patch failed: {}", e.getMessage());
        }
    }

    // Casa de Leilão: flag de item anunciado. Tabela auction_listings é auto-criada pelo ddl-auto. [LEILAO]
    private void patchInventoryListedColumn() {
        try {
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS listed boolean NOT NULL DEFAULT false");
            // [MERCADOR] forjador do item (p/ bônus de self-crafted do Mercador). Null p/ itens antigos/dropados.
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS crafted_by bigint");
            log.info("[SchemaMigrator] inventory_items.listed column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] inventory_items.listed patch failed: {}", e.getMessage());
        }
    }

    // Guerra de Guilda: elegibilidade (já controlou território). Tabela guild_wars é auto-criada. [GUERRA_GUILDA]
    private void patchGuildEverControlledColumn() {
        try {
            jdbc.execute("ALTER TABLE guilds ADD COLUMN IF NOT EXISTS ever_controlled_territory boolean NOT NULL DEFAULT false");
            log.info("[SchemaMigrator] guilds.ever_controlled_territory column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] guilds.ever_controlled_territory patch failed: {}", e.getMessage());
        }
    }

    // Pets: contador da pity da quest rara da Luna. (A tabela `pets` é auto-criada pelo ddl-auto). [PETS]
    private void patchPlayerPetPityColumn() {
        try {
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS pet_pity_attempts integer NOT NULL DEFAULT 0");
            log.info("[SchemaMigrator] players.pet_pity_attempts column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] players.pet_pity_attempts patch failed: {}", e.getMessage());
        }
    }

    // Postura de combate (tradeoff ATK/DEF) no warrior. [POSTURE]
    private void patchWarriorCombatPostureColumn() {
        try {
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS combat_posture varchar(20) NOT NULL DEFAULT 'BALANCED'");
            log.info("[SchemaMigrator] warriors.combat_posture column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] warriors.combat_posture patch failed: {}", e.getMessage());
        }
    }

    // Nível da guild derivado do gold acumulado. [GUILD_LEVEL_GOLD]
    // Adiciona lifetime_gold e faz seed = gold atual (baseline; recompute é monotônico → não rebaixa).
    private void patchGuildLifetimeGoldColumn() {
        try {
            jdbc.execute("ALTER TABLE guilds ADD COLUMN IF NOT EXISTS lifetime_gold bigint NOT NULL DEFAULT 0");
            int n = jdbc.update("UPDATE guilds SET lifetime_gold = gold WHERE lifetime_gold = 0 AND gold > 0");
            log.info("[SchemaMigrator] guilds.lifetime_gold column ensured (seeded {} row(s))", n);
        } catch (Exception e) {
            log.warn("[SchemaMigrator] guilds.lifetime_gold patch failed: {}", e.getMessage());
        }
    }

    // Guerra de Território: cap 15 + cansaço. [GUERRA_ROSTER]
    // warriors: stacks/último-ciclo de cansaço; players: flag do roster de guerra.
    private void patchWarFatigueAndRosterColumns() {
        try {
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS war_fatigue_stacks    integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS war_last_cycle_fought bigint  NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE players  ADD COLUMN IF NOT EXISTS in_war_roster         boolean NOT NULL DEFAULT false");
            log.info("[SchemaMigrator] war fatigue + roster columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] war fatigue/roster columns patch failed: {}", e.getMessage());
        }
    }

    // warriors: dropa a coluna on_mission (conceito "busy" aposentado — tudo é instantâneo). [SEM_TIMER]
    // Precisa dropar: o entity não mapeia mais a coluna, e ela é NOT NULL sem default → INSERT de
    // guerreiro novo quebraria. IF EXISTS = idempotente.
    private void dropWarriorOnMissionColumn() {
        try {
            jdbc.execute("ALTER TABLE warriors DROP COLUMN IF EXISTS on_mission");
            log.info("[SchemaMigrator] warriors.on_mission column dropped");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] warriors.on_mission drop failed: {}", e.getMessage());
        }
    }

    // kingdom_active_quests: add completed_window_id (lock da daily quest por janela de 12h) [DAILY_QUESTS]
    private void patchKingdomQuestWindowColumn() {
        try {
            jdbc.execute("ALTER TABLE kingdom_active_quests ADD COLUMN IF NOT EXISTS completed_window_id bigint NOT NULL DEFAULT 0");
            log.info("[SchemaMigrator] kingdom_active_quests completed_window_id column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] kingdom_active_quests completed_window_id patch failed: {}", e.getMessage());
        }
    }

    // [PVP_FLAG] As colunas de "emboscada pendente" do zone_activities foram aposentadas
    // (PvP virou raid-by-flag; vítima saqueada direto + mail). Não dropamos as colunas
    // existentes em prod (inofensivas); só paramos de garanti-las em DBs novos.

    // players: add SoulStone columns if not present (Hibernate adds them but needs explicit DEFAULT)
    private void patchPlayerSoulStoneColumns() {
        try {
            jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                                   WHERE table_name='players' AND column_name='soul_stones') THEN
                        ALTER TABLE players ADD COLUMN soul_stones integer NOT NULL DEFAULT 0;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                                   WHERE table_name='players' AND column_name='last_soulstone_heal_at') THEN
                        ALTER TABLE players ADD COLUMN last_soulstone_heal_at timestamp;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                                   WHERE table_name='players' AND column_name='inventory_expanded') THEN
                        ALTER TABLE players ADD COLUMN inventory_expanded boolean NOT NULL DEFAULT false;
                    END IF;
                END
                $$;
                """);
            log.info("[SchemaMigrator] players SoulStone columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] players SoulStone columns patch failed: {}", e.getMessage());
        }
    }

    // mail: add item-attachment columns and expiresAt (bag-full overflow system)
    private void patchMailItemColumns() {
        try {
            // ADD COLUMN IF NOT EXISTS por coluna (não agrupar num único IF — um IF
            // que testa só a 1ª coluna pula as demais se a criação foi parcial). [AUDITORIA M3]
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_name        varchar(255)");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_type        varchar(50)");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_atk         integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_def         integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_hp          integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_rarity      integer NOT NULL DEFAULT 1");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_sockets     integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_description text");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_origin      varchar(255)");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS item_collected   boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS expires_at       timestamp");
            log.info("[SchemaMigrator] mail item columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] mail item columns patch failed: {}", e.getMessage());
        }
    }

    // zone_activities.role: extend check constraint to include COMBAT
    private void patchZoneActivityRoleCheck() {
        try {
            jdbc.execute("""
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1 FROM information_schema.table_constraints
                        WHERE table_name = 'zone_activities'
                          AND constraint_name = 'zone_activities_role_check'
                    ) THEN
                        ALTER TABLE zone_activities DROP CONSTRAINT zone_activities_role_check;
                    END IF;
                    ALTER TABLE zone_activities
                        ADD CONSTRAINT zone_activities_role_check
                        CHECK (role IN ('GATHERING', 'HUNTING', 'COMBAT'));
                END
                $$;
                """);
            log.info("[SchemaMigrator] zone_activities_role_check updated");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] zone_activities_role_check patch failed: {}", e.getMessage());
        }
        try {
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS kingdom varchar(40)"); // [UNIFICAÇÃO_ZONA]
            log.info("[SchemaMigrator] zone_activities.kingdom column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] zone_activities.kingdom patch failed: {}", e.getMessage());
        }
    }

    // players: add VIP Status columns
    private void patchPlayerVipColumns() {
        try {
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS vip_expires_at           timestamp");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS last_vip_heal_at         timestamp");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS arena_fights_today       integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS last_arena_fight_date    date");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS vip_instant_quests_today integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS last_vip_quest_date      date");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS token_valid_from         timestamp"); // M6
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS pvp_flagged_zone         varchar(20)");  // [PVP_FLAG]
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS pvp_flagged_until        timestamp");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS pvp_shield_until         timestamp");
            log.info("[SchemaMigrator] players VIP + PvP-flag columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] players VIP columns patch failed: {}", e.getMessage());
        }
    }

    // warriors: add intellect attribute column (new d20 system)
    private void patchWarriorIntellectColumn() {
        try {
            jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                                   WHERE table_name='warriors' AND column_name='intellect') THEN
                        ALTER TABLE warriors ADD COLUMN intellect integer NOT NULL DEFAULT 0;
                    END IF;
                END
                $$;
                """);
            log.info("[SchemaMigrator] warriors intellect column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] warriors intellect column patch failed: {}", e.getMessage());
        }
    }

    // warriors: add second buff slot columns (VIP)
    private void patchWarriorBuff2Columns() {
        try {
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS active_buff2     varchar(50)");
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS buff_expires_at2 timestamp");
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS meal_buff            varchar(40)"); // [COZINHA]
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS meal_buff_expires_at timestamp");   // [COZINHA]
            log.info("[SchemaMigrator] warriors buff2/meal columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] warriors buff2 columns patch failed: {}", e.getMessage());
        }
    }

    // Optimistic locking: add version column to state entities (prevents double-collect/double-spend)
    private void patchOptimisticLockVersionColumns() {
        String[] tables = {
            "active_quests", "work_sessions", "gathering_sessions",
            "arena_matches", "zone_activities", "mail", "players"
        };
        for (String table : tables) {
            try {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0");
            } catch (Exception e) {
                log.warn("[SchemaMigrator] {} version column patch failed: {}", table, e.getMessage());
            }
        }
        log.info("[SchemaMigrator] optimistic-lock version columns ensured");
    }

    // inventory_items: add durability column (economic sink — items wear down in combat)
    // + item_level (Itens V3: nível do item, requisito pra equipar).
    private void patchInventoryItemDurabilityColumn() {
        try {
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS durability integer NOT NULL DEFAULT 100");
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS item_level integer NOT NULL DEFAULT 1");
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS pvp_locked boolean NOT NULL DEFAULT false"); // [PVP_FLAG]
            log.info("[SchemaMigrator] inventory_items durability + item_level + pvp_locked columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] inventory_items durability/item_level column patch failed: {}", e.getMessage());
        }
    }
}
