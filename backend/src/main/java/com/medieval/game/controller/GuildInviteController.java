package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.service.GuildInviteService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * [LEADERBOARDS] Convites de guilda. Endpoint separado do GuildController (zero conflito com a tela
 * de Guilda que está sendo editada noutra frente). Aceitar/recusar mora na tela Leaderboards.
 */
@RestController
@RequestMapping("/api/guild-invites")
@RequiredArgsConstructor
public class GuildInviteController {

    private final GuildInviteService guildInviteService;
    private final PlayerService playerService;

    @GetMapping
    public ResponseEntity<?> incoming(Authentication auth) {
        return ResponseEntity.ok(Map.of("invites", guildInviteService.incoming(me(auth))));
    }

    @PostMapping("/invite/{playerId}")
    public ResponseEntity<?> invite(@PathVariable Long playerId, Authentication auth) {
        guildInviteService.invite(me(auth), playerId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<?> accept(@PathVariable Long id, Authentication auth) {
        guildInviteService.accept(me(auth), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<?> decline(@PathVariable Long id, Authentication auth) {
        guildInviteService.decline(me(auth), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Player me(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
