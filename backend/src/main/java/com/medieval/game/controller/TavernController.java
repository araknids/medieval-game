package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.model.TavernMessage;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.TavernService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** [TAVERNA] Beber + buff + chat + avisos. Tempo real por polling. Desenho: docs/PLANO_TAVERNA.md. */
@RestController
@RequestMapping("/api/tavern")
@RequiredArgsConstructor
public class TavernController {

    private final TavernService tavernService;
    private final PlayerService playerService;

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication auth) {
        return ResponseEntity.ok(tavernService.status(getPlayer(auth)));
    }

    @PostMapping("/drink")
    public ResponseEntity<?> drink(@RequestBody(required = false) DrinkRequest req, Authentication auth) {
        boolean success = req != null && req.success();
        return ResponseEntity.ok(tavernService.drink(getPlayer(auth), success));
    }

    @GetMapping("/feed")
    public ResponseEntity<?> feed(@RequestParam(required = false) Long since, Authentication auth) {
        List<Map<String, Object>> msgs = tavernService.feed(since).stream().map(TavernController::toMap).toList();
        return ResponseEntity.ok(Map.of("messages", msgs));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest req, Authentication auth) {
        TavernMessage m = tavernService.postMessage(getPlayer(auth), req != null ? req.text() : null);
        return ResponseEntity.ok(toMap(m));
    }

    private static Map<String, Object> toMap(TavernMessage m) {
        return Map.of(
            "id",     m.getId(),
            "sender", m.getSenderName(),
            "text",   m.getText(),
            "type",   m.getType(),
            "system", m.getSenderPlayerId() == 0L
        );
    }

    record DrinkRequest(boolean success) {}
    record ChatRequest(String text) {}
}
