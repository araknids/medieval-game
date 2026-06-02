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
    }

    // players: add SoulStone columns if not present (Hibernate adds them but needs defaults)
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
}
