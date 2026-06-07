package com.medieval.game.controller;

import com.medieval.game.model.ArenaMatch;
import com.medieval.game.model.Player;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.ArenaService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/arena")
@RequiredArgsConstructor
public class ArenaController {

    private final ArenaService arenaService;
    private final PlayerService playerService;
    private final WarriorRepository warriorRepository;

    @GetMapping("/rank")
    public ResponseEntity<List<RankEntry>> getRank() {
        List<Player> top = arenaService.getRanking().stream().limit(20).toList();
        // [AUDITORIA_2 A5] 1 query batch p/ os nomes em vez de findByPlayer por linha (N+1)
        Map<Long, String> names = warriorRepository.findByPlayerIn(top).stream()
                .collect(java.util.stream.Collectors.toMap(w -> w.getPlayer().getId(),
                        com.medieval.game.model.Warrior::getName, (a, b) -> a));
        List<RankEntry> rank = top.stream()
                .map(p -> new RankEntry(names.getOrDefault(p.getId(), "?"),
                        com.medieval.game.service.AchievementService.titleString(p), // [TITULOS]
                        p.getRankPoints(), p.getArenaWins(), p.getArenaLosses()))
                .toList();
        return ResponseEntity.ok(rank);
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentFight(Authentication auth) {
        Player player = getPlayer(auth);
        Optional<ArenaMatch> active = arenaService.getActiveFight(player);
        if (active.isEmpty()) return ResponseEntity.ok(Map.of("active", false));
        return ResponseEntity.ok(MatchResponse.from(active.get()));
    }

    // [SEM_TIMER] Duelo instantâneo: resolve e retorna o resultado completo numa chamada só.
    @PostMapping("/fight")
    public ResponseEntity<?> startFight(Authentication auth) {
        Player player = getPlayer(auth);
        ArenaMatch match = arenaService.startFight(player);
        return ResponseEntity.ok(Map.of(
                "id",         match.getId(),
                "won",        match.isChallengerWon(),
                "opponent",   match.getOpponentName(),
                "goldEarned", match.getGoldReward(),
                "rankChange", match.getRankChange(),
                "log",        Arrays.asList(match.getBattleLog().split("\n"))
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    record MatchResponse(Long id, String opponentName, String status,
                         long secondsRemaining, long goldReward, int rankChange) {
        static MatchResponse from(ArenaMatch m) {
            long secs = Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), m.getFinishesAt()));
            return new MatchResponse(m.getId(), m.getOpponentName(),
                    m.getStatus().name(), secs, m.getGoldReward(), m.getRankChange());
        }
    }

    record RankEntry(String warriorName, String title, int rankPoints, int wins, int losses) {}
}
