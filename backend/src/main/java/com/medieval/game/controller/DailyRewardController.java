package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.service.DailyRewardService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** [DAILY] Recompensa de login diária (ciclo de 7 dias). Desenho: docs/PLANO_RETENCAO_NOVATO.md. */
@RestController
@RequestMapping("/api/daily-reward")
@RequiredArgsConstructor
public class DailyRewardController {

    private final DailyRewardService dailyRewardService;
    private final PlayerService      playerService;

    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication auth) {
        Player player = playerService.findById((Long) auth.getPrincipal());
        return ResponseEntity.ok(dailyRewardService.status(player));
    }

    @PostMapping("/claim")
    public ResponseEntity<?> claim(Authentication auth) {
        Player player = playerService.findById((Long) auth.getPrincipal());
        return ResponseEntity.ok(dailyRewardService.claim(player));
    }
}
