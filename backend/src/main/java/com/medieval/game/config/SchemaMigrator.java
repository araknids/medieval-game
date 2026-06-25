package com.medieval.game.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
public class SchemaMigrator implements SmartInitializingSingleton {

    private final JdbcTemplate jdbc;

    // [HARDENING P2-4] Roda no fim da instanciação dos singletons (afterSingletonsInstantiated) — DEPOIS
    // do schema-gen do Hibernate (o EntityManagerFactory é singleton, já criado aqui) e ANTES do start do
    // servidor web (finishRefresh). Antes era @EventListener(ApplicationReadyEvent.class), que dispara
    // DEPOIS de o Tomcat aceitar tráfego: havia uma janela de boot servindo requests com um check de enum
    // defasado ainda no ar (dropStaleEnumCheckConstraints só rodava depois). Roda antes do DataSeeder
    // (que continua no ApplicationReadyEvent) → ordem correta: migra schema, depois popula.
    @Override
    public void afterSingletonsInstantiated() {
        migrate();
    }

    public void migrate() {
        remapRemovedEnumValues();   // [VARREDURA] roda 1º: salva linhas com valor de enum removido
        patchZoneActivityKingdomColumn();
        patchPlayerLanguageColumn();
        patchPlayerSoulStoneColumns();
        patchMailItemColumns();
        patchPlayerVipColumns();
        patchWarriorBuff2Columns();
        patchWarriorIntellectColumn();
        patchInventoryItemDurabilityColumn();
        patchOptimisticLockVersionColumns();
        patchSingleSessionUniqueIndexes();
        patchEquippedUniqueIndex();
        patchTerritoryLastResolvedCycleColumn();
        patchWarFatigueAndRosterColumns();
        patchGuildLifetimeGoldColumn();
        patchWarriorCombatPostureColumn();
        patchPlayerPetPityColumn();
        patchPlayerDailyRewardColumns();
        patchStarterQuestColumns();
        patchGuildEverControlledColumn();
        patchInventoryListedColumn();
        patchInventoryItemRunPendingColumn();
        patchInventoryWeaponCategoryColumn();
        patchElementColumns();
        patchAbilityPointsColumn();
        patchStashColumns();
        patchKingdomQuestWindowColumn();
        patchTrainingFreeColumn();
        patchLeaderboardCounterColumns();
        patchPlayerGenderColumn();
        patchTerritoryBattleEventsColumn();
        dropWarriorOnMissionColumn();
        dropStaleEnumCheckConstraints();
        purgeStaleEnumRows();
        fixSubLevel10NonRecruits();
        patchHotIndexes();
    }

    /**
     * [AUDITORIA_2 A3] Índices nas FKs/colunas quentes. O PostgreSQL NÃO indexa coluna de FK
     * automaticamente, e ddl-auto=update não cria índices p/ @ManyToOne — então as queries mais
     * chamadas (warriorRepository.findByPlayer, inventory/zone por player, ranking) viram full scan
     * que cresce com o nº de jogadores. CREATE INDEX IF NOT EXISTS funciona em Postgres e H2; cada um
     * isolado p/ um não bloquear o outro. (As tabelas com unique composto já têm índice — não repetir.)
     */
    /**
     * [CLASSES] Corrige personagens que estão como WARRIOR/ARCHER/MERCHANT mas com nível < 10 — anomalia:
     * só se especializa GANHANDO a Path Trial (Lv10+). Esses são seeds antigos / contas pré-RECRUIT que
     * apareciam como "Warrior" no início. Reseta-os p/ RECRUIT + base de recruta (12/10/100). Idempotente.
     */
    private void fixSubLevel10NonRecruits() {
        try {
            int n = jdbc.update("UPDATE warriors SET warrior_class = 'RECRUIT', attack = 12, defense = 10, health = 100 "
                    + "WHERE warrior_class <> 'RECRUIT' AND level < 10");
            if (n > 0) log.warn("[SchemaMigrator] {} personagem(ns) <Lv10 não-recruta resetado(s) p/ RECRUIT.", n);
            else       log.info("[SchemaMigrator] starter-class check: nenhum não-recruta abaixo do Lv10.");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] fixSubLevel10NonRecruits skipped — {}", e.getMessage());
        }
    }

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
            "CREATE INDEX IF NOT EXISTS idx_players_guild           ON players(guild_id)",         // [VARREDURA] roster/count/join de guild
            "CREATE INDEX IF NOT EXISTS idx_players_pvp_flag        ON players(pvp_flagged_zone, pvp_flagged_until)", // [VARREDURA] matchmaking de raid (findFlaggedInZone, todo collect flagado)
        };
        int ok = 0;
        for (String sql : idx) {
            try { jdbc.execute(sql); ok++; }
            catch (Exception e) { log.warn("[SchemaMigrator] index skipped: {} — {}", sql.trim(), e.getMessage()); }
        }
        log.info("[SchemaMigrator] hot FK/lookup indexes ensured ({}/{})", ok, idx.length);
    }

    // [GUERRA_GAUNTLET] coluna battle_events (JSON dos eventos da guerra) p/ o replay no cliente.
    private void patchTerritoryBattleEventsColumn() {
        try {
            jdbc.execute("ALTER TABLE territory_battle_logs ADD COLUMN IF NOT EXISTS battle_events TEXT");
            log.info("[SchemaMigrator] territory_battle_logs.battle_events ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] territory battle_events patch failed: {}", e.getMessage());
        }
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

    // [VARREDURA] Valores de enum REMOVIDOS do código (write-dead): Location.COMMERCE/ARENA,
    // MatchStatus.FINISHED, QuestStatus.READY_TO_COLLECT, ExpeditionSource.KINGDOM. Linha antiga com esse
    // valor faria o Hibernate ESTOURAR ao desserializar o nome inexistente. Aqui REMAPEAMOS (UPDATE, não
    // DELETE — não apagar um player só porque location='COMMERCE') p/ um valor válido, ANTES de qualquer
    // leitura. Todos @Enumerated(STRING) → seguro por nome. Idempotente (0 linhas no caso normal, pois
    // nenhum desses valores é mais escrito pelo código).
    private void remapRemovedEnumValues() {
        remap("players",               "location", "TAVERN",    "'COMMERCE','ARENA'");
        remap("arena_matches",         "status",   "COLLECTED", "'FINISHED'");
        remap("kingdom_active_quests", "status",   "COLLECTED", "'READY_TO_COLLECT'");
    }
    private void remap(String table, String col, String to, String fromCsv) {
        try {
            int n = jdbc.update("UPDATE " + table + " SET " + col + " = '" + to + "' WHERE " + col + " IN (" + fromCsv + ")");
            if (n > 0) log.warn("[SchemaMigrator] remapped {} stale-enum row(s) in {}.{} → {}", n, table, col, to);
        } catch (Exception e) {
            log.warn("[SchemaMigrator] enum remap on {}.{} skipped — {}", table, col, e.getMessage());
        }
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

    // [ENUM_CHECKS] PROATIVO: dropa TODO check constraint de enum (estilo "col IN (...)") de QUALQUER
    // tabela. O Hibernate cria um por coluna @Enumerated(STRING); quando o enum ganha/renomeia um valor
    // (SkillType.GARIMPO, ZoneActivityStatus.BOSS_PENDING, Kingdom V2, RECRUIT/ARCHER/MERCHANT, etc.) o
    // check fica defasado e rejeita o INSERT/UPDATE com o valor novo (Postgres 23514 → 500). Caçar um a
    // um em prod é whack-a-mole; aqui derrubamos todos de uma vez. SEGURO porque (a) a validação do enum
    // já é feita na camada JPA e (b) NÃO existe nenhum check de NEGÓCIO no código (zero @Check /
    // columnDefinition CHECK) — todo check é gerado pelo Hibernate p/ enum. O Postgres normaliza todo
    // "col IN ('A','B')" como "col = ANY (ARRAY['A','B'])", então a marca registrada do enum-check é
    // conter "ARRAY[" — um range/negócio (ex.: "x >= 0") não conteria. Roda no ApplicationReadyEvent
    // (depois do schema-gen do Hibernate); o update-mode não recria check em coluna existente, então
    // o drop persiste entre restarts. [REINOS_V2][CLASSES]
    public void dropStaleEnumCheckConstraints() { // [HARDENING P2-5] público p/ o teste de regressão do sweep
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
                          AND pg_get_constraintdef(con.oid) ~* 'array\\['
                    LOOP
                        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.tbl, r.con);
                    END LOOP;
                END
                $$;
                """);
            log.info("[SchemaMigrator] enum check constraints dropped (proactive — todos os enum-checks)");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] enum check constraint sweep failed: {}", e.getMessage());
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
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS outfit_theme varchar(16)"); // [OUTFITS_CLASSE] tema visual da armadura (do item)
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
            // [MERCADO_STEAM] SteamID64 da conta linkada (null = não linkado). Fundação do Mercador Azul.
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS steam_id varchar(20)");
            // [ONBOARDING] tela de boas-vindas já vista? (não reaparece ao limpar cache).
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS onboarding_seen boolean NOT NULL DEFAULT false");
            log.info("[SchemaMigrator] warriors.ability_points + players war formation + active_title columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] warriors.ability_points patch failed: {}", e.getMessage());
        }
    }

    // [ONBOARDING] Deveres do Recruta — 3 flags one-time (NPC pede recurso → XP+gold). Soft-wipe reseta.
    private void patchStarterQuestColumns() {
        try {
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS starter_guard_done  boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS starter_priest_done boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS starter_shop_done   boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS starter_guard_accepted  boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS starter_priest_accepted boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS starter_shop_accepted   boolean NOT NULL DEFAULT false");
            log.info("[SchemaMigrator] players starter-quest columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] players starter-quest patch failed: {}", e.getMessage());
        }
    }

    // Gênero do personagem (cosmético: base/peças Male/Female no paper-doll). Default MALE p/ contas antigas. [OUTFITS_FEMALE]
    private void patchPlayerGenderColumn() {
        try {
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS gender varchar(8) DEFAULT 'MALE'");
            log.info("[SchemaMigrator] players.gender column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] players.gender patch failed: {}", e.getMessage());
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
            // [MERCADO_STEAM] item consignado ao Mercador Azul (sai da bag). Tabela consignments = ddl-auto.
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS consigned boolean NOT NULL DEFAULT false");
            log.info("[SchemaMigrator] inventory_items.listed column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] inventory_items.listed patch failed: {}", e.getMessage());
        }
    }

    // [INCURSAO] flag de item carregado na bolsa de uma Incursão (sai da bag, igual listed/consigned).
    // A tabela expedition_runs é auto-criada pelo ddl-auto (sem patch). Só o run_pending na tabela
    // existente inventory_items precisa de patch (ddl-auto=update não põe o DEFAULT explícito).
    private void patchInventoryItemRunPendingColumn() {
        try {
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS run_pending boolean NOT NULL DEFAULT false");
            log.info("[SchemaMigrator] inventory_items.run_pending column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] inventory_items.run_pending patch failed: {}", e.getMessage());
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

    // Recompensa de login diária (ciclo de 7 dias). [DAILY]
    private void patchPlayerDailyRewardColumns() {
        try {
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS last_daily_claim_date date");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS daily_streak integer NOT NULL DEFAULT 0");
            log.info("[SchemaMigrator] players daily-reward columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] players daily-reward columns patch failed: {}", e.getMessage());
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

    // [LEADERBOARDS] contadores de ranking: mobs abatidos (warriors), players abatidos (players),
    // war kills da guilda (guilds). Tabelas novas (territory_contributions/friendships/guild_invites)
    // são auto-criadas pelo ddl-auto — só estas colunas em tabelas existentes precisam de patch.
    private void patchLeaderboardCounterColumns() {
        try {
            jdbc.execute("ALTER TABLE warriors ADD COLUMN IF NOT EXISTS mob_kills    integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE players  ADD COLUMN IF NOT EXISTS player_kills integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE guilds   ADD COLUMN IF NOT EXISTS war_kills    bigint  NOT NULL DEFAULT 0");
            log.info("[SchemaMigrator] leaderboard counter columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] leaderboard counter columns patch failed: {}", e.getMessage());
        }
    }

    // kingdom_active_quests: completed_window_id (lock da daily por janela) [DAILY_QUESTS]
    // + pending_option_id (escolha guardada quando a Luna interrompe a missão) [LUNA_INTERRUPT]
    private void patchKingdomQuestWindowColumn() {
        try {
            jdbc.execute("ALTER TABLE kingdom_active_quests ADD COLUMN IF NOT EXISTS completed_window_id bigint NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE kingdom_active_quests ADD COLUMN IF NOT EXISTS pending_option_id varchar(40)");
            log.info("[SchemaMigrator] kingdom_active_quests columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] kingdom_active_quests columns patch failed: {}", e.getMessage());
        }
    }

    // [TREINO_IDLE] coluna free no training_sessions (treino idle grátis vs pago instantâneo).
    private void patchTrainingFreeColumn() {
        try {
            jdbc.execute("ALTER TABLE training_sessions ADD COLUMN IF NOT EXISTS free boolean NOT NULL DEFAULT false");
            log.info("[SchemaMigrator] training_sessions.free column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] training_sessions.free patch failed: {}", e.getMessage());
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
            // [DAILY] anexo de recurso (peixe da daily / overflow de bag cheia)
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS resource_type      varchar(40)");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS resource_qty       integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS resource_collected boolean NOT NULL DEFAULT false");
            // [INCURSAO_PVP] replay anexado (mail de raid): log + eventos + cena
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS battle_log         text");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS battle_events_json text");
            jdbc.execute("ALTER TABLE mail ADD COLUMN IF NOT EXISTS battle_scene       varchar(20)");
            log.info("[SchemaMigrator] mail item columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] mail item columns patch failed: {}", e.getMessage());
        }
    }

    // zone_activities.kingdom: garante a coluna (coleta unificada por reino). [UNIFICAÇÃO_ZONA]
    // (Os checks de role/status/skill_type desta tabela são derrubados genericamente por
    //  dropStaleEnumCheckConstraints — não precisam mais de patch dedicado. [ENUM_CHECKS])
    private void patchZoneActivityKingdomColumn() {
        try {
            jdbc.execute("ALTER TABLE zone_activities ADD COLUMN IF NOT EXISTS kingdom varchar(40)");
            log.info("[SchemaMigrator] zone_activities.kingdom column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] zone_activities.kingdom patch failed: {}", e.getMessage());
        }
    }

    // players.language: idioma preferido do jogador (en/pt). [I18N]
    private void patchPlayerLanguageColumn() {
        try {
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS language varchar(5) NOT NULL DEFAULT 'en'");
            log.info("[SchemaMigrator] players.language column ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] players.language patch failed: {}", e.getMessage());
        }
    }

    // players: add VIP Status columns
    private void patchPlayerVipColumns() {
        try {
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS vip_expires_at           timestamp");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS last_vip_heal_at         timestamp");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS arena_fights_today       integer NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS last_arena_fight_date    date");
            jdbc.execute("ALTER TABLE players ADD COLUMN IF NOT EXISTS last_arena_window_id     bigint NOT NULL DEFAULT 0"); // [ARENA_JANELA] janela de 6h
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
            "arena_matches", "zone_activities", "mail", "players",
            // [AUDITORIA_DUPE] fluxos de item: serializa sell/list/buy/cancel/stash concorrentes.
            "inventory_items", "auction_listings", "resource_inventory",
            // [VARREDURA] warriors: serializa spend-point / XP / HP (read-modify-write concorrente).
            "warriors"
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

    /**
     * [VARREDURA] Índice único PARCIAL (Postgres): no máx UMA sessão IN_PROGRESS por player. Fecha a corrida
     * de double-start (work/quest/training) que o @Version NÃO pega — são INSERTs concorrentes, não UPDATEs
     * da mesma linha. O guard de app (existsByPlayerAndStatus) cobre o caso sequencial; este índice cobre a
     * corrida real. H2 não suporta WHERE em índice → cada try-catch ignora (a violação vira 409 no
     * GlobalExceptionHandler). Um try por tabela: duplicatas pré-existentes numa não bloqueiam as outras.
     */
    private void patchSingleSessionUniqueIndexes() {
        String[][] idx = {
            {"uk_work_one_active",     "work_sessions"},
            {"uk_quest_one_active",    "kingdom_active_quests"},
            {"uk_training_one_active", "training_sessions"},
        };
        for (String[] i : idx) {
            try {
                jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + i[0] + " ON " + i[1]
                        + "(player_id) WHERE status = 'IN_PROGRESS'");
            } catch (Exception e) {
                log.warn("[SchemaMigrator] partial unique index {} skipped (H2 ou duplicatas): {}", i[0], e.getMessage());
            }
        }
        log.info("[SchemaMigrator] single-active-session partial unique indexes ensured (Postgres)");
    }

    /**
     * [HARDENING P2-2] Índice único PARCIAL (Postgres): no máx UM item equipado por (player, ItemType).
     * Fecha a corrida de equip concorrente (2 abas equipando itens diferentes do mesmo slot) que o
     * @Version do InventoryItem NÃO pega — são UPDATEs em LINHAS distintas, não na mesma. Sem isto, os
     * dois ficam equipped=true e os stats empilham (e fura o arco-sem-escudo). O guard de app desequipa o
     * atual no caso sequencial; este índice cobre a corrida real (a 2ª transação colide → 409). H2 não
     * suporta WHERE em índice → o try-catch ignora (a defesa-em-profundidade no equippedGear cobre o H2).
     * Se já houver dados corrompidos (2 equipados do mesmo tipo), a criação falha e é logada (sem travar).
     */
    private void patchEquippedUniqueIndex() {
        try {
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_inventory_one_equipped_per_slot "
                    + "ON inventory_items(player_id, type) WHERE equipped = true");
            log.info("[SchemaMigrator] one-equipped-per-slot partial unique index ensured (Postgres)");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] equipped unique index skipped (H2 ou duplicatas pré-existentes): {}", e.getMessage());
        }
    }

    // inventory_items: add durability column (economic sink — items wear down in combat)
    // + item_level (Itens V3: nível do item, requisito pra equipar).
    private void patchInventoryItemDurabilityColumn() {
        try {
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS durability integer NOT NULL DEFAULT 100");
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS item_level integer NOT NULL DEFAULT 1");
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS pvp_locked boolean NOT NULL DEFAULT false"); // [PVP_FLAG]
            jdbc.execute("ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS power_pct integer NOT NULL DEFAULT 100"); // [DESGASTE]
            log.info("[SchemaMigrator] inventory_items durability + item_level + pvp_locked + power_pct columns ensured");
        } catch (Exception e) {
            log.warn("[SchemaMigrator] inventory_items durability/item_level column patch failed: {}", e.getMessage());
        }
    }
}
