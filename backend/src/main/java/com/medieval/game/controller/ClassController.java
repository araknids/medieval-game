package com.medieval.game.controller;

import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Player;
import com.medieval.game.service.ClassChangeService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Path Trial: estado da escolha de classe + tentativa da Trial (Lv10, RECRUIT → WARRIOR/ARCHER). [CLASSES] */
@RestController
@RequestMapping("/api/class")
@RequiredArgsConstructor
public class ClassController {

    private final ClassChangeService classService;
    private final PlayerService      playerService;

    @GetMapping
    public ResponseEntity<?> info(Authentication auth) {
        return ResponseEntity.ok(classService.info(getPlayer(auth)));
    }

    @PostMapping("/trial/{path}")
    public ResponseEntity<?> trial(@PathVariable String path, Authentication auth) {
        WarriorClass wc;
        try {
            wc = WarriorClass.valueOf(path.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown class path: " + path));
        }
        return ResponseEntity.ok(classService.attemptTrial(getPlayer(auth), wc));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
