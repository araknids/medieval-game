package com.medieval.game.enums;

/**
 * Métrica que o AchievementService mapeia pra um valor do jogador. O achievement desbloqueia
 * quando {@code valor(metric) >= threshold}. Mantém o enum Achievement puro (sem dep de service). [TITULOS]
 */
public enum AchievementMetric {
    LEVEL,           // warrior.getLevel()
    ARENA_WINS,      // player.getArenaWins()
    RANK_POINTS,     // player.getRankPoints()
    TOWER_FLOOR,     // player.getTowerBestFloor()
    WEALTH,          // player.totalBronze()
    CLASS_WARRIOR,   // warriorClass == WARRIOR  ? 1 : 0
    CLASS_ARCHER,    // warriorClass == ARCHER   ? 1 : 0
    CLASS_MERCHANT,  // warriorClass == MERCHANT ? 1 : 0
    GUILD_MEMBER,    // está numa guilda ? 1 : 0
    GUILD_LEADER     // é líder de guilda ? 1 : 0
}
