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
        List<RankEntry> rank = arenaService.getRanking().stream()
                .limit(20)
                .map(p -> {
                    String warriorName = warriorRepository.findByPlayer(p)
                            .map(w -> w.getName())
                            .orElse("?");
                    return new RankEntry(warriorName, p.getRankPoints(),
                            p.getArenaWins(), p.getArenaLosses());
                })
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

    @PostMapping("/fight")
    public ResponseEntity<?> startFight(Authentication auth) {
        Player player = getPlayer(auth);
        ArenaMatch match = arenaService.startFight(player);
        return ResponseEntity.ok(MatchResponse.from(match));
    }

    @PostMapping("/{id}/collect")
    public ResponseEntity<?> collect(@PathVariable Long id, Authentication auth) {
        Player player = getPlayer(auth);
        ArenaMatch match = arenaService.collectResult(player, id);
        return ResponseEntity.ok(Map.of(
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

    record RankEntry(String warriorName, int rankPoints, int wins, int losses) {}
}
