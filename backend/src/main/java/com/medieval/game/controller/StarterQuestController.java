package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.StarterQuestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** [ONBOARDING] Deveres do Recruta — quests de entrega únicas. Desenho: docs/PLANO_ONBOARDING.md. */
@RestController
@RequestMapping("/api/starter-quests")
@RequiredArgsConstructor
public class StarterQuestController {

    private final StarterQuestService starterQuestService;
    private final PlayerService       playerService;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        Player player = playerService.findById((Long) auth.getPrincipal());
        return ResponseEntity.ok(starterQuestService.status(player));
    }

    @PostMapping("/{which}/turn-in")
    public ResponseEntity<?> turnIn(@PathVariable String which, Authentication auth) {
        Player player = playerService.findById((Long) auth.getPrincipal());
        return ResponseEntity.ok(starterQuestService.turnIn(player, which));
    }
}
