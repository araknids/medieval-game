package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// SEGURANÇA: só existe em dev. Em produção o bean não é registrado (endpoint → 404),
// evitando que qualquer jogador conceda SoulStones (moeda premium) a si mesmo. [AUDITORIA C1]
@RestController
@RequestMapping("/api/admin")
@Profile("dev")
@RequiredArgsConstructor
public class AdminController {

    private final PlayerService    playerService;
    private final PlayerRepository playerRepository;

    @PostMapping("/grant-soulstones")
    public ResponseEntity<?> grantSoulStones(
            @RequestBody Map<String, Integer> body,
            Authentication auth) {
        int amount = body.getOrDefault("amount", 5);
        if (amount <= 0 || amount > 100)
            return ResponseEntity.badRequest().body(Map.of("error", "amount must be 1-100"));

        Player player = playerService.findById((Long) auth.getPrincipal());
        player.setSoulStones(player.getSoulStones() + amount);
        playerRepository.save(player);

        return ResponseEntity.ok(Map.of(
            "message",    "Granted " + amount + " SoulStones.",
            "soulStones", player.getSoulStones()
        ));
    }
}
