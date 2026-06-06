package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.service.AchievementService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Achievements + seleção de título. [TITULOS] */
@RestController
@RequestMapping("/api/achievements")
@RequiredArgsConstructor
public class AchievementController {

    private final AchievementService achievementService;
    private final PlayerService      playerService;

    /** Catálogo com status do player + título ativo (roda checkAndUnlock antes). */
    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        Player player = playerService.findById((Long) auth.getPrincipal());
        return ResponseEntity.ok(achievementService.list(player));
    }

    /** Seleciona o título ativo. body: { "id": "LEVEL_10" } ou { "id": null } / "none" p/ limpar. */
    @PostMapping("/title")
    public ResponseEntity<?> selectTitle(@RequestBody(required = false) TitleRequest req, Authentication auth) {
        try {
            Player player = playerService.findById((Long) auth.getPrincipal());
            String title = achievementService.selectTitle(player, req != null ? req.id() : null);
            return ResponseEntity.ok(Map.of("activeTitle", title));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    record TitleRequest(String id) {}
}
