-- ============================================================================
--  SOFT WIPE — Fresh start mantendo as contas (login/senha)
-- ----------------------------------------------------------------------------
--  Reseta TODO o progresso de TODOS os jogadores para o estado de recém-criado:
--    • moedas → 50 prata (0 bronze / 0 ouro)
--    • guerreiro → nível 1, atributos 0, HP/stamina cheios, classe WARRIOR
--    • inventário → volta aos 7 itens iniciais (desequipados)
--    • skills, profissões, recursos, sessões (quest/trabalho/coleta/zona/torre/
--      arena/treino), mails, compras de loja → apagados
--    • SoulStones → 0, VIP desligado
--    • guildas → DISSOLVIDAS, territórios → neutros
--  MANTÉM: username, email, senha, data de criação.
--
--  COMO RODAR (Railway): painel do projeto → serviço Postgres → aba de Query
--  (ou conecte com psql/DBeaver usando a connection string) → cole tudo → Run.
--  RODE COM NINGUÉM JOGANDO e REINICIE o serviço do app depois (Deploy → Restart).
--  É transacional: ou aplica tudo, ou nada (rollback em erro).
-- ============================================================================

BEGIN;

-- 1) Apaga dados de progressão (ordem segura de FK) --------------------------
DELETE FROM socketed_gems;
DELETE FROM inventory_items;
DELETE FROM active_quests;
DELETE FROM work_sessions;
DELETE FROM gathering_sessions;
DELETE FROM arena_matches;
DELETE FROM zone_activities;
DELETE FROM tower_runs;
DELETE FROM training_sessions;
DELETE FROM kingdom_active_quests;
DELETE FROM skill_levels;
DELETE FROM work_professions;
DELETE FROM resource_inventory;
DELETE FROM shop_purchases;
DELETE FROM mail;
DELETE FROM password_reset_tokens;
DELETE FROM territory_declarations;
DELETE FROM territory_battle_logs;

-- 2) Territórios voltam a neutro ---------------------------------------------
UPDATE territory_controls
   SET guild_id = NULL, defense_streak = 0, dominant_since = NULL, last_resolved_cycle_id = 0;

-- 3) Tira todos das guildas e dissolve as guildas ----------------------------
UPDATE players SET guild_id = NULL;
DELETE FROM guilds;

-- 4) Reset dos jogadores (mantém login: username/email/senha/created_at) -----
UPDATE players SET
    bronze = 0, silver = 50, gold = 0,
    rank_points = 1000, arena_wins = 0, arena_losses = 0, tower_best_floor = 0,
    current_stamina = 100, stamina_updated_at = NOW(),
    location = 'TAVERN',
    guild_donated_bronze = 0,
    soul_stones = 0, inventory_expanded = false,
    last_soulstone_heal_at = NULL,
    vip_expires_at = NULL, last_vip_heal_at = NULL,
    arena_fights_today = 0, last_arena_fight_date = NULL,
    vip_instant_quests_today = 0, last_vip_quest_date = NULL;

-- 5) Reset dos guerreiros (estado de recém-criado, classe WARRIOR) -----------
UPDATE warriors SET
    level = 1, experience = 0,
    attack = 15, defense = 12, health = 110,
    strength = 0, dexterity = 0, constitution = 0, luck = 0, intellect = 0,
    available_points = 0,
    current_hp_snapshot = 100, hp_updated_at = NOW(),
    on_mission = false,
    active_buff = NULL, buff_expires_at = NULL,
    active_buff2 = NULL, buff_expires_at2 = NULL;

-- 6) Devolve os 7 itens iniciais a cada jogador (desequipados) ---------------
INSERT INTO inventory_items
    (player_id, name, type, attack_bonus, defense_bonus, health_bonus, rarity, sell_price,
     equipped, sockets, guarded, durability, description, origin)
SELECT id, 'Elmo de Ferro',     'HELMET', 0, 2, 10, 1, 20, false, 0, false, 100, 'Equipamento inicial', 'Início da jornada' FROM players
UNION ALL
SELECT id, 'Armadura de Couro', 'ARMOR',  0, 3, 15, 1, 20, false, 0, false, 100, 'Equipamento inicial', 'Início da jornada' FROM players
UNION ALL
SELECT id, 'Espada de Ferro',   'WEAPON', 4, 0,  0, 1, 20, false, 0, false, 100, 'Equipamento inicial', 'Início da jornada' FROM players
UNION ALL
SELECT id, 'Escudo de Madeira', 'SHIELD', 0, 3,  0, 1, 20, false, 0, false, 100, 'Equipamento inicial', 'Início da jornada' FROM players
UNION ALL
SELECT id, 'Botas de Couro',    'BOOTS',  0, 1,  5, 1, 20, false, 0, false, 100, 'Equipamento inicial', 'Início da jornada' FROM players
UNION ALL
SELECT id, 'Luvas de Couro',    'GLOVES', 1, 1,  0, 1, 20, false, 0, false, 100, 'Equipamento inicial', 'Início da jornada' FROM players
UNION ALL
SELECT id, 'Calça de Couro',    'PANTS',  0, 2,  8, 1, 20, false, 0, false, 100, 'Equipamento inicial', 'Início da jornada' FROM players;

COMMIT;

-- Conferência rápida (rode separado, depois do COMMIT):
--   SELECT count(*) AS jogadores FROM players;
--   SELECT count(*) AS itens FROM inventory_items;          -- deve ser jogadores × 7
--   SELECT count(*) AS guildas FROM guilds;                 -- deve ser 0
--   SELECT username, silver, bronze, gold FROM players;
