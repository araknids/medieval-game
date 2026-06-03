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
            jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                                   WHERE table_name='mail' AND column_name='item_name') THEN
                        ALTER TABLE mail ADD COLUMN item_name        varchar(255);
                        ALTER TABLE mail ADD COLUMN item_type        varchar(50);
                        ALTER TABLE mail ADD COLUMN item_atk         integer NOT NULL DEFAULT 0;
                        ALTER TABLE mail ADD COLUMN item_def         integer NOT NULL DEFAULT 0;
                        ALTER TABLE mail ADD COLUMN item_hp          integer NOT NULL DEFAULT 0;
                        ALTER TABLE mail ADD COLUMN item_rarity      integer NOT NULL DEFAULT 1;
                        ALTER TABLE mail ADD COLUMN item_sockets     integer NOT NULL DEFAULT 0;
                        ALTER TABLE mail ADD COLUMN item_description text;
                        ALTER TABLE mail ADD COLUMN item_origin      varchar(255);
                        ALTER TABLE mail ADD COLUMN item_collected   boolean NOT NULL DEFAULT false;
                        ALTER TABLE mail ADD COLUMN expires_at       timestamp;
                    END IF;
                END
                $$;
                """);
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
            jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                                   WHERE table_name='players' AND column_name='vip_expires_at') THEN
                        ALTER TABLE players ADD COLUMN vip_expires_at       timestamp;
                        ALTER TABLE players ADD COLUMN last_vip_heal_at     timestamp;
                        ALTER TABLE players ADD COLUMN arena_fights_today   integer NOT NULL DEFAULT 0;
                        ALTER TABLE players ADD COLUMN last_arena_fight_date date;
                        ALTER TABLE players ADD COLUMN vip_instant_quests_today integer NOT NULL DEFAULT 0;
                        ALTER TABLE players ADD COLUMN last_vip_quest_date  date;
                    END IF;
                END
                $$;
                """);
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
            jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                                   WHERE table_name='warriors' AND column_name='active_buff2') THEN
                        ALTER TABLE warriors ADD COLUMN active_buff2    varchar(50);
                        ALTER TABLE warriors ADD COLUMN buff_expires_at2 timestamp;
                    END IF;
                END
                $$;
                """);
            log.info("[SchemaMigrator] warriors buff2 columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] warriors buff2 columns patch failed: {}", e.getMessage());
        }
    }
}
