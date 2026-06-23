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

import java.util.LinkedHashMap;
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

    /** Estado da run + preview do andar atual (atmosfera/monstros/MVP). */
    private Map<String, Object> runState(TowerRun r) {
        TowerService.FloorView fv = towerService.floorView(r.getCurrentFloor());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", true);
        m.put("runId", r.getId());
        m.put("currentFloor", r.getCurrentFloor());
        m.put("highestFloor", r.getHighestFloor());
        m.put("maxFloor", com.medieval.game.service.TowerFloors.maxFloor());
        m.put("atmosphere", fv.atmosphere());            // [TORRE_NARRATIVA]
        m.put("isMvp", fv.isMvp());
        m.put("monsters", fv.monsters());
        m.put("bossName", fv.primary().name());
        m.put("bossHp", fv.primary().health());
        m.put("bossAtk", fv.primary().attack());
        m.put("bossDef", fv.primary().defense());
        m.put("bossAc", 10 + fv.primary().dex());
        m.put("recommendedLevel", fv.recommendedLevel());
        return m;
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrent(Authentication auth) {
        Player player = getPlayer(auth);
        Optional<TowerRun> run = towerService.getCurrentRun(player);
        if (run.isPresent()) return ResponseEntity.ok(runState(run.get()));
        // [TORRE_PREVIEW] Sem run ativa: devolve quem espera no PRÓXIMO andar (towerBestFloor+1) —
        // enter() começa exatamente nesse andar — p/ o lobby mostrar o retrato do inimigo.
        int next = Math.min(player.getTowerBestFloor() + 1, com.medieval.game.service.TowerFloors.maxFloor());
        TowerService.FloorView fv = towerService.floorView(next);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", false);
        m.put("nextFloor", next);
        m.put("highestFloor", player.getTowerBestFloor());
        m.put("isMvp", fv.isMvp());
        m.put("bossName", fv.primary().name());
        // [TORRE] lobby também mostra a lore/descrição + stats do PRÓXIMO andar (mesmo painel do andar ativo)
        m.put("atmosphere", fv.atmosphere());
        m.put("monsters", fv.monsters());
        m.put("bossHp", fv.primary().health());
        m.put("bossAtk", fv.primary().attack());
        m.put("bossDef", fv.primary().defense());
        m.put("bossAc", 10 + fv.primary().dex());
        m.put("recommendedLevel", fv.recommendedLevel());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/ranking")
    public ResponseEntity<List<?>> getRanking(@RequestParam(defaultValue = "0") int page) {
        var top = towerService.getRanking(page, 20); // [PAGINACAO] página de 20 (offset no DB)
        // [VARREDURA] 1 query batch p/ os nomes em vez de findByPlayer por linha (N+1) — espelha ArenaController.
        Map<Long, String> names = warriorRepository.findByPlayerIn(top).stream()
                .collect(java.util.stream.Collectors.toMap(w -> w.getPlayer().getId(),
                        com.medieval.game.model.Warrior::getName, (a, b) -> a));
        var ranking = top.stream().map(p -> Map.of(
                "warriorName", names.getOrDefault(p.getId(), p.getUsername()),
                "title", com.medieval.game.service.AchievementService.titleString(p), // [TITULOS]
                "bestFloor", p.getTowerBestFloor())).toList();
        return ResponseEntity.ok(ranking);
    }

    /** Preview de um andar específico. */
    @GetMapping("/boss/{floor}")
    public ResponseEntity<?> getBoss(@PathVariable int floor) {
        TowerService.FloorView fv = towerService.floorView(floor);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("floor", fv.floor());
        m.put("atmosphere", fv.atmosphere());
        m.put("isMvp", fv.isMvp());
        m.put("monsters", fv.monsters());
        m.put("name", fv.primary().name());
        m.put("hp", fv.primary().health());
        m.put("atk", fv.primary().attack());
        m.put("def", fv.primary().defense());
        m.put("ac", 10 + fv.primary().dex());
        m.put("recommendedLevel", fv.recommendedLevel());
        return ResponseEntity.ok(m);
    }

    @PostMapping("/enter")
    public ResponseEntity<?> enter(Authentication auth) {
        return ResponseEntity.ok(runState(towerService.enter(getPlayer(auth))));
    }

    @PostMapping("/fight")
    public ResponseEntity<?> fight(Authentication auth) {
        var result = towerService.fight(getPlayer(auth));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("won", result.won());
        m.put("floor", result.floor());
        m.put("bossName", result.bossName());
        m.put("atmosphere", result.atmosphere());           // [TORRE_NARRATIVA]
        m.put("bronzeEarned", result.bronzeEarned());
        m.put("expEarned", result.expEarned());
        m.put("log", result.log());
        m.put("runOver", result.runOver());
        m.put("arkaChoicePending", result.arkaChoicePending()); // topo: a escolha (poupar/matar)
        m.put("battleEvents", result.events()); // [BATALHA_ANIMADA] replay do andar (gauntlet)
        m.put("scene", "tower");                 // [BATALHA_ANIMADA]
        return ResponseEntity.ok(m);
    }

    /** [TORRE_NARRATIVA] A escolha no topo: poupar (spare=true) ou matar o Rei Arka → título oculto. */
    @PostMapping("/arka")
    public ResponseEntity<?> arkaChoice(@RequestBody ArkaRequest req, Authentication auth) {
        String narrative = towerService.resolveArkaChoice(getPlayer(auth), req.spare());
        return ResponseEntity.ok(Map.of("message", narrative, "spared", req.spare()));
    }

    public record ArkaRequest(boolean spare) {}

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
