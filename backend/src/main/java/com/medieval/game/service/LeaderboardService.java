package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.Player;
import com.medieval.game.model.TerritoryContribution;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.TerritoryContributionRepository;
import com.medieval.game.repository.TerritoryControlRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * [LEADERBOARDS] Monta os rankings do servidor num formato de linha único (jogador ou guilda).
 * Tudo paginado no DB (índice/limit, payload pequeno), assemblagem dentro de @Transactional p/ ler
 * lazy (player do warrior, etc.) sem N+1 — warriors carregados em batch via findByPlayerIn.
 */
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final PlayerRepository playerRepository;
    private final WarriorRepository warriorRepository;
    private final GuildRepository guildRepository;
    private final TerritoryControlRepository territoryRepository;
    private final TerritoryContributionRepository contributionRepository;

    public static final int PAGE_SIZE = 20;

    /** Linha de ranking de JOGADOR (formato único da UI). */
    public record PlayerRow(long playerId, String name, String title, int level,
                            String classId, String gender, long value) {}

    /** Linha de ranking de GUILDA. */
    public record GuildRow(long guildId, String name, int level, long value) {}

    private static Pageable pageOf(int page) {
        return PageRequest.of(Math.max(0, page), PAGE_SIZE);
    }

    // ── Rankings de jogador ──────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<PlayerRow> players(String category, int page) {
        Pageable pg = pageOf(page);
        return switch (category == null ? "" : category.toLowerCase()) {
            case "level"  -> fromWarriors(warriorRepository.findTopByLevel(pg),    w -> (long) w.getLevel());
            case "hunter" -> fromWarriors(warriorRepository.findTopByMobKills(pg), w -> (long) w.getMobKills());
            case "arena"  -> fromPlayers(playerRepository.findTopByRankPoints(pg), p -> (long) p.getRankPoints());
            case "tower"  -> fromPlayers(
                    playerRepository.findByTowerBestFloorGreaterThanOrderByTowerBestFloorDesc(0, pg),
                    p -> (long) p.getTowerBestFloor());
            case "slayer" -> fromPlayers(playerRepository.findTopByPlayerKills(pg), p -> (long) p.getPlayerKills());
            case "wealth" -> fromPlayers(playerRepository.findTopByWealth(pg),      Player::totalBronze);
            default -> List.of();
        };
    }

    // Linhas a partir de WARRIORS (player eager via @EntityGraph) — métricas no warrior (level, mobKills).
    private List<PlayerRow> fromWarriors(List<Warrior> warriors, ToLongFunction<Warrior> value) {
        List<PlayerRow> out = new ArrayList<>(warriors.size());
        for (Warrior w : warriors) {
            Player p = w.getPlayer();
            out.add(new PlayerRow(p.getId(), w.getName(), AchievementService.titleString(p),
                    w.getLevel(), classOf(w), genderOf(p), value.applyAsLong(w)));
        }
        return out;
    }

    // Linhas a partir de PLAYERS — carrega os warriors em batch (nome/nível/classe). Métrica no player.
    private List<PlayerRow> fromPlayers(List<Player> players, ToLongFunction<Player> value) {
        if (players.isEmpty()) return List.of();
        Map<Long, Warrior> byPlayer = warriorRepository.findByPlayerIn(players).stream()
                .collect(Collectors.toMap(w -> w.getPlayer().getId(), w -> w, (a, b) -> a));
        List<PlayerRow> out = new ArrayList<>(players.size());
        for (Player p : players) {
            Warrior w = byPlayer.get(p.getId());
            out.add(rowOf(p, w, value.applyAsLong(p)));
        }
        return out;
    }

    private static PlayerRow rowOf(Player p, Warrior w, long value) {
        return new PlayerRow(p.getId(),
                w != null ? w.getName() : p.getUsername(),
                AchievementService.titleString(p),
                w != null ? w.getLevel() : 1,
                w != null ? classOf(w) : "recruit",
                genderOf(p), value);
    }

    private static String classOf(Warrior w) {
        return w.getWarriorClass() != null ? w.getWarriorClass().name().toLowerCase() : "recruit";
    }
    private static String genderOf(Player p) {
        return p.getGender() != null ? p.getGender().name().toLowerCase() : "male";
    }

    // ── Rankings de guilda (várias sub-categorias) ───────────────────────────
    @Transactional(readOnly = true)
    public List<GuildRow> guilds(String subcat, int page) {
        Pageable pg = pageOf(page);
        return switch (subcat == null ? "" : subcat.toLowerCase()) {
            case "power"     -> guildRepository.findTopByPower(pg).stream()
                    .map(g -> new GuildRow(g.getId(), g.getName(), g.getLevel(), g.getLifetimeGold())).toList();
            case "warkills"  -> guildRepository.findTopByWarKills(pg).stream()
                    .map(g -> new GuildRow(g.getId(), g.getName(), g.getLevel(), g.getWarKills())).toList();
            case "members"   -> rowsFromCount(guildRepository.topByMemberCount(pg));
            case "territory" -> rowsFromCount(territoryRepository.topGuildsByTerritoryCount(pg));
            default -> List.of();
        };
    }

    // [guildId, name, level, count] (projeção Object[]) → GuildRow
    private List<GuildRow> rowsFromCount(List<Object[]> rows) {
        List<GuildRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new GuildRow(((Number) r[0]).longValue(), (String) r[1],
                    ((Number) r[2]).intValue(), ((Number) r[3]).longValue()));
        }
        return out;
    }

    // ── Ranking de território (incursões por jogador naquele reino) ───────────
    @Transactional(readOnly = true)
    public List<PlayerRow> territory(Kingdom kingdom, int page) {
        List<TerritoryContribution> top = contributionRepository.findTopByKingdom(kingdom, pageOf(page));
        if (top.isEmpty()) return List.of();
        List<Player> players = top.stream().map(TerritoryContribution::getPlayer).toList();
        Map<Long, Warrior> byPlayer = warriorRepository.findByPlayerIn(players).stream()
                .collect(Collectors.toMap(w -> w.getPlayer().getId(), w -> w, (a, b) -> a));
        List<PlayerRow> out = new ArrayList<>(top.size());
        for (TerritoryContribution tc : top) {
            Player p = tc.getPlayer();
            out.add(rowOf(p, byPlayer.get(p.getId()), tc.getIncursions()));
        }
        return out;
    }
}
