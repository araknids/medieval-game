package com.medieval.game.controller;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [LEADERBOARDS] Rankings do servidor. Formato de linha único pra UI:
 *   jogador → {rank, playerId, warriorName, title, level, classId, gender, value}
 *   guilda  → {rank, guildId, guildName, level, value}
 * Tudo paginado (?page=). Categorias de jogador: level/arena/tower/hunter/slayer/wealth.
 */
@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @GetMapping("/{category}")
    public ResponseEntity<?> players(@PathVariable String category,
                                     @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(rankedPlayers(page, leaderboardService.players(category, page)));
    }

    @GetMapping("/guild/{subcat}")
    public ResponseEntity<?> guilds(@PathVariable String subcat,
                                    @RequestParam(defaultValue = "0") int page) {
        List<LeaderboardService.GuildRow> rows = leaderboardService.guilds(subcat, page);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            LeaderboardService.GuildRow g = rows.get(i);
            out.add(Map.of(
                    "rank",      page * LeaderboardService.PAGE_SIZE + i + 1,
                    "guildId",   g.guildId(),
                    "guildName", g.name(),
                    "level",     g.level(),
                    "value",     g.value()));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/territory/{kingdom}")
    public ResponseEntity<?> territory(@PathVariable String kingdom,
                                       @RequestParam(defaultValue = "0") int page) {
        Kingdom k;
        try {
            k = Kingdom.valueOf(kingdom.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown territory"));
        }
        return ResponseEntity.ok(rankedPlayers(page, leaderboardService.territory(k, page)));
    }

    /** Lista de territórios p/ o picker da aba "Território". */
    @GetMapping("/territories")
    public ResponseEntity<?> territories() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Kingdom k : Kingdom.values()) {
            out.add(Map.of("code", k.name(), "name", k.displayName, "icon", k.icon));
        }
        return ResponseEntity.ok(out);
    }

    // ── helper ───────────────────────────────────────────────────────────────
    private List<Map<String, Object>> rankedPlayers(int page, List<LeaderboardService.PlayerRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            LeaderboardService.PlayerRow r = rows.get(i);
            out.add(Map.of(
                    "rank",        page * LeaderboardService.PAGE_SIZE + i + 1,
                    "playerId",    r.playerId(),
                    "warriorName", r.name(),
                    "title",       r.title(),
                    "level",       r.level(),
                    "classId",     r.classId(),
                    "gender",      r.gender(),
                    "value",       r.value()));
        }
        return out;
    }
}
