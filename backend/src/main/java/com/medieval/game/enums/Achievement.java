package com.medieval.game.enums;

/**
 * Catálogo de achievements. Cada um desbloqueia um TÍTULO ao bater o marco
 * ({@code valor(metric) >= threshold}). O AchievementService calcula a métrica e persiste o desbloqueio.
 * Números/nomes são placeholders p/ tuning no playtest. [TITULOS]
 *
 * Campos: (category, title, displayName, description, metric, threshold)
 */
public enum Achievement {

    // ── Classe ───────────────────────────────────────────────────────────────
    PATH_WARRIOR (AchievementCategory.CLASS, "Blade",  "Path of the Blade",  "Walk the Warrior's path.",  AchievementMetric.CLASS_WARRIOR,  1),
    PATH_ARCHER  (AchievementCategory.CLASS, "Hunter", "Path of the Hunter", "Walk the Archer's path.",   AchievementMetric.CLASS_ARCHER,   1),
    PATH_MERCHANT(AchievementCategory.CLASS, "Trader", "Path of the Trader", "Walk the Merchant's path.", AchievementMetric.CLASS_MERCHANT, 1),

    // ── Nível / Veterania ────────────────────────────────────────────────────
    LEVEL_10(AchievementCategory.LEVEL, "Adventurer", "Adventurer", "Reach level 10.", AchievementMetric.LEVEL, 10),
    LEVEL_25(AchievementCategory.LEVEL, "Veteran",    "Veteran",    "Reach level 25.", AchievementMetric.LEVEL, 25),
    LEVEL_50(AchievementCategory.LEVEL, "Legend",     "Legend",     "Reach level 50.", AchievementMetric.LEVEL, 50),

    // ── Arena / PvP ──────────────────────────────────────────────────────────
    ARENA_10 (AchievementCategory.ARENA, "Duelist",   "Duelist",   "Win 10 arena duels.",     AchievementMetric.ARENA_WINS,  10),
    ARENA_50 (AchievementCategory.ARENA, "Gladiator", "Gladiator", "Win 50 arena duels.",     AchievementMetric.ARENA_WINS,  50),
    RANK_1500(AchievementCategory.ARENA, "Champion",  "Champion",  "Reach 1500 rank points.", AchievementMetric.RANK_POINTS, 1500),

    // ── Torre ────────────────────────────────────────────────────────────────
    TOWER_10(AchievementCategory.TOWER, "Tower Climber",   "Tower Climber",   "Reach tower floor 10.", AchievementMetric.TOWER_FLOOR, 10),
    TOWER_25(AchievementCategory.TOWER, "Tower Conqueror", "Tower Conqueror", "Reach tower floor 25.", AchievementMetric.TOWER_FLOOR, 25),

    // ── Riqueza ──────────────────────────────────────────────────────────────
    WEALTH_RICH   (AchievementCategory.WEALTH, "Wealthy", "Wealthy", "Hold 100,000 bronze.",   AchievementMetric.WEALTH, 100_000),
    WEALTH_MAGNATE(AchievementCategory.WEALTH, "Magnate", "Magnate", "Hold 1,000,000 bronze.", AchievementMetric.WEALTH, 1_000_000),

    // ── Guilda ───────────────────────────────────────────────────────────────
    GUILD_MEMBER(AchievementCategory.GUILD, "Kin",         "Kin",         "Join a guild.",       AchievementMetric.GUILD_MEMBER, 1),
    GUILD_LEADER(AchievementCategory.GUILD, "Guildmaster", "Guildmaster", "Lead a guild.",       AchievementMetric.GUILD_LEADER, 1),

    // ── História (OCULTOS — anti-spoiler; dirigidos por evento via grant()) ──────
    // A escolha no topo da Torre [LORE.md]: matar ou poupar o Rei Arka. Ficam invisíveis na lista até
    // serem desbloqueados, pra não revelar que o Rei "que você veio salvar" é o chefe final.
    REGICIDE    (AchievementCategory.STORY, "Regicide",     "Regicide",     "You struck down King Arka with your own hand.",            AchievementMetric.MANUAL, 1, true),
    THE_MERCIFUL(AchievementCategory.STORY, "The Merciful", "The Merciful", "You spared King Arka — and watched him fall all the same.", AchievementMetric.MANUAL, 1, true);

    public final AchievementCategory category;
    public final String              title;       // prefixo exibido antes do nome
    public final String              displayName; // nome do achievement (página)
    public final String              description;
    public final AchievementMetric   metric;
    public final long                threshold;
    public final boolean             hidden;      // [TITULOS] some da lista até ser desbloqueado (anti-spoiler)

    Achievement(AchievementCategory category, String title, String displayName,
                String description, AchievementMetric metric, long threshold) {
        this(category, title, displayName, description, metric, threshold, false);
    }

    Achievement(AchievementCategory category, String title, String displayName,
                String description, AchievementMetric metric, long threshold, boolean hidden) {
        this.category    = category;
        this.title       = title;
        this.displayName = displayName;
        this.description = description;
        this.metric      = metric;
        this.threshold   = threshold;
        this.hidden      = hidden;
    }
}
