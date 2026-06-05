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
        patchStashColumns();
        patchKingdomQuestWindowColumn();
        dropStaleEnumCheckConstraints();
        purgeStaleEnumRows();
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
                              'gathering_sessions', 'warriors', 'meal_inventory')
                          AND pg_get_constraintdef(con.oid) ~ '(skill_type|resource_type|quest_type|kingdom|territory|meal)'
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
