package com.medieval.game.controller;

import com.medieval.game.model.GuildWar;
import com.medieval.game.model.Player;
import com.medieval.game.service.GuildWarService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Guerra de Guilda (declarar / atacar / status / alvos). [GUERRA_GUILDA] */
@RestController
@RequestMapping("/api/guild/war")
@RequiredArgsConstructor
public class GuildWarController {

    private final GuildWarService guildWarService;
    private final PlayerService   playerService;

    // Status da guerra atual + membros inimigos atacáveis
    @GetMapping
    public ResponseEntity<?> status(Authentication auth) {
        GuildWarService.WarStatus s = guildWarService.statusFor(getPlayer(auth));
        return ResponseEntity.ok(Map.of(
            "atWar",          s.atWar(),
            "warId",          s.warId() != null ? s.warId() : -1,
            "enemyGuildName", s.enemyGuildName() != null ? s.enemyGuildName() : "",
            "myKills",        s.myKills(),
            "enemyKills",     s.enemyKills(),
            "secondsLeft",    s.secondsLeft(),
            "enemies",        s.enemies().stream().map(e -> Map.of(
                "playerId",    e.playerId(),
                "warriorName", e.warriorName(),
                "title",       e.title() != null ? e.title() : "", // [TITULOS]
                "level",       e.level(),
                "hpPercent",   e.hpPercent(),
                "knockedOut",  e.knockedOut(),
                "shielded",    e.shielded()
            )).toList()
        ));
    }

    // Guildas elegíveis para declarar guerra
    @GetMapping("/targets")
    public ResponseEntity<?> targets(Authentication auth) {
        List<?> list = guildWarService.eligibleTargets(getPlayer(auth)).stream().map(t -> Map.of(
            "id",    t.id(),
            "name",  t.name(),
            "level", t.level()
        )).toList();
        return ResponseEntity.ok(list);
    }

    // Declarar guerra (líder)
    @PostMapping("/declare/{guildId}")
    public ResponseEntity<?> declare(@PathVariable Long guildId, Authentication auth) {
        GuildWar war = guildWarService.declare(getPlayer(auth), guildId);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.war_declared", "War declared!"), "warId", war.getId()));
    }

    // Atacar um membro da guilda inimiga (qualquer membro)
    @PostMapping("/attack/{playerId}")
    public ResponseEntity<?> attack(@PathVariable Long playerId, Authentication auth) {
        GuildWarService.AttackResult r = guildWarService.attack(getPlayer(auth), playerId);
        return ResponseEntity.ok(Map.of(
            "won",          r.won(),
            "opponentName", r.opponentName(),
            "loot",         r.loot(),
            "myKills",      r.myKills(),
            "enemyKills",   r.enemyKills(),
            "battleLog",    r.log(),
            "battleEvents", r.battleEvents(),          // [BATALHA_ANIMADA] replay 3D
            "scene",        "fortress"
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
