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
        patchZoneActivityAmbushColumns();
        patchInventoryItemDurabilityColumn();
        patchOptimisticLockVersionColumns();
        patchTerritoryLastResolvedCycleColumn();
        patchGatheringSessionKingdomColumn();
    }

    // gathering_sessions: add kingdom column (define o pool de drops — Reinos V2)
    private void patchGatheringSessionKingdomColumn() {
        try {
            jdbc.execute("ALTER TABLE gathering_sessions ADD COLUMN IF NOT EXISTS kingdom varchar(40)");
            log.info("[SchemaMigrator] gathering_sessions kingdom column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] gathering_sessions kingdom column patch failed: {}", e.getMessage());
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

    // zone_activities: add ambush PvP columns (each column independently — robust)
    private void patchZoneActivityAmbushColumns() {
        try {
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS ambush_count integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS ambush_pending boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS last_ambusher_name varchar(255)");
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS last_ambush_bronze_lost bigint NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS last_ambush_item_lost varchar(255)");
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS last_ambush_log text");
            log.info("[SchemaMigrator] zone_activities ambush columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] zone_activities ambush columns patch failed: {}", e.getMessage());
        }
    }

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
            log.info("[SchemaMigrator] players VIP columns ensured");
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
            log.info("[SchemaMigrator] warriors buff2 columns ensured");
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
    private void patchInventoryItemDurabilityColumn() {
        try {
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS durability integer NOT NULL DEFAULT 100");
            log.info("[SchemaMigrator] inventory_items durability column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] inventory_items durability column patch failed: {}", e.getMessage());
        }
    }
}
