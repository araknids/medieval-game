package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.model.TowerRun;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.TowerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/tower")
@RequiredArgsConstructor
public class TowerController {

    private final TowerService      towerService;
    private final PlayerService     playerService;
    private final WarriorRepository warriorRepository;

    // Estado atual do jogador na torre
    @GetMapping("/current")
    public ResponseEntity<?> getCurrent(Authentication auth) {
        Player player = getPlayer(auth);
        Optional<TowerRun> run = towerService.getCurrentRun(player);

        if (run.isEmpty()) return ResponseEntity.ok(Map.of("active", false));

        TowerRun r = run.get();
        var boss = towerService.bossForFloor(r.getCurrentFloor());

        return ResponseEntity.ok(Map.of(
            "active",        true,
            "runId",         r.getId(),
            "currentFloor",  r.getCurrentFloor(),
            "highestFloor",  r.getHighestFloor(),
            "bossName",      boss.name(),
            "bossHp",        boss.health(),
            "bossAtk",       boss.attack(),
            "bossDef",       boss.defense(),
            "bossEvasion",   boss.evasion()
        ));
    }

    // Ranking global
    @GetMapping("/ranking")
    public ResponseEntity<List<?>> getRanking() {
        var ranking = towerService.getRanking().stream().map(p -> {
            String warriorName = warriorRepository.findByPlayer(p)
                    .map(w -> w.getName()).orElse(p.getUsername());
            return Map.of(
                "warriorName", warriorName,
                "bestFloor",   p.getTowerBestFloor()
            );
        }).toList();
        return ResponseEntity.ok(ranking);
    }

    // Lista info do boss de um andar específico
    @GetMapping("/boss/{floor}")
    public ResponseEntity<?> getBoss(@PathVariable int floor) {
        var boss = towerService.bossForFloor(floor);
        return ResponseEntity.ok(Map.of(
            "floor",    floor,
            "name",     boss.name(),
            "hp",       boss.health(),
            "atk",      boss.attack(),
            "def",      boss.defense(),
            "evasion",  boss.evasion()
        ));
    }

    // Entra na torre
    @PostMapping("/enter")
    public ResponseEntity<?> enter(Authentication auth) {
        try {
            Player  player = getPlayer(auth);
            TowerRun run   = towerService.enter(player);
            var boss = towerService.bossForFloor(run.getCurrentFloor());
            return ResponseEntity.ok(Map.of(
                "active",       true,
                "runId",        run.getId(),
                "currentFloor", run.getCurrentFloor(),
                "highestFloor", run.getHighestFloor(),
                "bossName",     boss.name(),
                "bossHp",       boss.health(),
                "bossAtk",      boss.attack(),
                "bossDef",      boss.defense(),
                "bossEvasion",  boss.evasion()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Luta contra o chefe do andar atual
    @PostMapping("/fight")
    public ResponseEntity<?> fight(Authentication auth) {
        try {
            Player player = getPlayer(auth);
            var result = towerService.fight(player);
            return ResponseEntity.ok(Map.of(
                "won",          result.won(),
                "floor",        result.floor(),
                "bossName",     result.bossName(),
                "bronzeEarned", result.bronzeEarned(),
                "expEarned",    result.expEarned(),
                "log",          result.log(),
                "runOver",      result.runOver()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Sai da torre voluntariamente
    @PostMapping("/exit")
    public ResponseEntity<?> exit(Authentication auth) {
        try {
            towerService.exit(getPlayer(auth));
            return ResponseEntity.ok(Map.of("message", "You left the tower."));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
