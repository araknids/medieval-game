package com.medieval.game.controller;

import com.medieval.game.config.I18nConfig;
import com.medieval.game.model.Player;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [I18N] Preferências do jogador. Hoje: idioma (en/pt). O cliente lê {@code GET /api/settings} no boot
 * pra saber o idioma e então manda {@code Accept-Language} em toda chamada (o backend serve o conteúdo
 * traduzido por isso). Ver docs/PLANO_I18N_BACKEND.md.
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final PlayerService playerService;

    @GetMapping
    public ResponseEntity<?> get(Authentication auth) {
        Player p = playerService.findById((Long) auth.getPrincipal());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", p.getLanguage() == null ? "en" : p.getLanguage());
        body.put("supportedLanguages", I18nConfig.SUPPORTED_TAGS);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/language")
    public ResponseEntity<?> setLanguage(@RequestBody Map<String, String> req, Authentication auth) {
        Player p = playerService.findById((Long) auth.getPrincipal());
        try {
            String lang = playerService.setLanguage(p, req.get("language"));
            return ResponseEntity.ok(Map.of("language", lang));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
