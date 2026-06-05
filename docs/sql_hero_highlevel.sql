-- ============================================================
-- Herói high-level pra testar PvP (player 1 / araknids)
-- Rodar no console do Postgres (Railway). Ajuste o id se precisar.
-- combatStats: atk = attack+strength | def = defense | hp = health+constitution*8
--   → atk 500 · def 300 · hp 1300 · AC 40 (dex) · crit/luck 30
-- ============================================================

-- Guerreiro forte nível 50
UPDATE warriors SET
    level               = 50,
    experience          = 0,
    attack              = 300,
    defense             = 300,
    health              = 500,
    strength            = 200,
    constitution        = 100,
    dexterity           = 30,
    luck                = 30,
    intellect           = 20,
    available_points    = 0,
    current_hp_snapshot = 100,
    hp_updated_at       = NOW(),
    on_mission          = false
WHERE player_id = 1;

-- Dinheiro + estamina cheia + limpa qualquer flag/escudo de PvP (estado limpo)
UPDATE players SET
    bronze            = 50000,
    silver            = 100,
    gold              = 100,
    current_stamina   = 100,
    stamina_updated_at = NOW(),
    pvp_flagged_zone  = NULL,
    pvp_flagged_until = NULL,
    pvp_shield_until  = NULL
WHERE id = 1;

-- (opcional) destrava expedições/coletas/trabalhos presos como "em progresso"
UPDATE zone_activities   SET status = 'CANCELLED' WHERE player_id = 1 AND status = 'IN_PROGRESS';
UPDATE gathering_sessions SET status = 'CANCELLED' WHERE player_id = 1 AND status = 'IN_PROGRESS';
UPDATE work_sessions      SET status = 'CANCELLED' WHERE player_id = 1 AND status = 'IN_PROGRESS';
