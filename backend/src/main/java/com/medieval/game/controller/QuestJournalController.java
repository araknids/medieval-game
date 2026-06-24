package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.service.KingdomService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// [DIARIO_QUEST] Diário de quest unificado em 3 grupos: "Pra pegar" (disponíveis) / "Em progresso"
// (aceitas-não-resolvidas = to-do) / "Completadas" (só ÚNICAS — deveres do recruta hoje; cresce com
// quests de história). Read-only — aceitar/resolver continuam nos endpoints de
// /api/world/{kingdom}/quests/start|collect e /api/starter-quests/*.
@RestController
@RequestMapping("/api/quests")
@RequiredArgsConstructor
public class QuestJournalController {

    private final KingdomService kingdomService;
    private final PlayerService  playerService;

    @GetMapping("/journal")
    public ResponseEntity<?> journal(Authentication auth) {
        Player player = playerService.findById((Long) auth.getPrincipal());
        return ResponseEntity.ok(kingdomService.questJournal(player));
    }
}
