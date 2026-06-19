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
    public ResponseEntity<List<RankEntry>> getRank(@RequestParam(defaultValue = "0") int page) {
        List<Player> top = arenaService.getRanking(page, 20);   // [PAGINACAO] página de 20 (offset no DB)
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

    // [ARENA_ESCOLHA] Oferece 3 oponentes (com stats) p/ o jogador escolher, estilo Shakes & Fidget.
    @GetMapping("/opponents")
    public ResponseEntity<?> opponents(Authentication auth) {
        Player player = getPlayer(auth);
        List<?> cards = arenaService.offerOpponents(player, 3).stream().map(o -> Map.ofEntries(
                Map.entry("opponentId", o.opponentId()),
                Map.entry("name",       o.name()),
                Map.entry("title",      o.title()),
                Map.entry("level",      o.level()),
                Map.entry("classId",    o.classId()),
                Map.entry("gender",     o.gender()),
                Map.entry("rankPoints", o.rankPoints()),
                Map.entry("power",      o.power()),
                Map.entry("atk",        o.atk()),
                Map.entry("def",        o.def()),
                Map.entry("hp",         o.hp()),
                Map.entry("dex",        o.dex()),
                Map.entry("agi",        o.agi()),
                Map.entry("luk",        o.luk()),
                Map.entry("isNpc",      o.isNpc())
        )).toList();
        return ResponseEntity.ok(Map.of("opponents", cards, "yourPower", arenaService.powerOf(player)));
    }

    // [SEM_TIMER] Duelo instantâneo: resolve e retorna o resultado completo numa chamada só.
    // [ARENA_ESCOLHA] body opcional {opponentId} = o card escolhido; ausente/0 → matchmaking normal.
    @PostMapping("/fight")
    public ResponseEntity<?> startFight(@RequestBody(required = false) FightRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        long opponentId = req != null ? req.opponentId() : 0L;
        ArenaService.FightResult fr = arenaService.startFight(player, opponentId);
        ArenaMatch match = fr.match();
        return ResponseEntity.ok(Map.of(
                "id",          match.getId(),
                "won",         match.isChallengerWon(),
                "opponent",    match.getOpponentName(),
                "goldEarned",  match.getGoldReward(),
                "rankChange",  match.getRankChange(),
                "log",         Arrays.asList(match.getBattleLog().split("\n")),
                "battleEvents", fr.events(), // [BATALHA_ANIMADA] eventos do replay
                "scene",       "arena"       // [BATALHA_ANIMADA] fundo da Arena
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

    record FightRequest(long opponentId) {}   // [ARENA_ESCOLHA] id do oponente escolhido (0 = matchmaking)
}
