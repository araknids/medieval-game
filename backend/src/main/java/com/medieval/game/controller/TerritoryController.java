package com.medieval.game.controller;

import com.medieval.game.enums.Territory;
import com.medieval.game.model.*;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.TerritoryService;
import com.medieval.game.service.TerritoryService.TerritoryBonus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/territory")
@RequiredArgsConstructor
public class TerritoryController {

    private final TerritoryService territoryService;
    private final PlayerService    playerService;

    // ── List all territories ──────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> listAll(Authentication auth) {
        Player player    = getPlayer(auth);
        TerritoryBonus myBonus = territoryService.getBonusForPlayer(player);

        List<?> territories = Arrays.stream(Territory.values()).map(t -> {
            TerritoryControl ctrl = territoryService.getTerritory(t);
            long secsUntilNext = 21600 - (Instant.now().getEpochSecond() % 21600);
            // Load guild name safely (avoid lazy proxy + null in Map.of)
            String guildName = ctrl.isNeutral() ? "" : ctrl.getControllingGuild().getName();
            return Map.ofEntries(
                Map.entry("territory",       t.name()),
                Map.entry("displayName",     t.displayName),
                Map.entry("lore",            t.lore),
                Map.entry("controllingGuild", guildName),
                Map.entry("defenseStreak",   ctrl.getDefenseStreak()),
                Map.entry("debuffPercent",   ctrl.debuffPercent()),
                Map.entry("isNeutral",       ctrl.isNeutral()),
                Map.entry("isMine",          myBonus.territory() == t),
                Map.entry("secsUntilBattle", secsUntilNext),
                Map.entry("exclusiveBonus",  t.exclusiveBonus)
            );
        }).toList();

        return ResponseEntity.ok(territories);
    }

    // ── My guild's territory ──────────────────────────────────────────────────
    @GetMapping("/my")
    public ResponseEntity<?> myTerritory(Authentication auth) {
        Player player = getPlayer(auth);
        TerritoryBonus bonus = territoryService.getBonusForPlayer(player);
        if (bonus.territory() == null) {
            return ResponseEntity.ok(Map.of("hasTerritory", false));
        }
        TerritoryControl ctrl = territoryService.getTerritory(bonus.territory());
        return ResponseEntity.ok(Map.of(
            "hasTerritory",  true,
            "territory",     bonus.territory().name(),
            "displayName",   bonus.territory().displayName,
            "defenseStreak", ctrl.getDefenseStreak(),
            "debuffPercent", ctrl.debuffPercent(),
            "xpBonus",       bonus.xpBonus(),
            "bronzeBonus",   bonus.bronzeBonus(),
            "miningBonus",   bonus.miningBonus(),
            "fishingBonus",  bonus.fishingBonus(),
            "questXpBonus",  bonus.questXpBonus()
        ));
    }

    // ── Declare attack ────────────────────────────────────────────────────────
    @PostMapping("/{territory}/declare")
    public ResponseEntity<?> declare(@PathVariable Territory territory, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            TerritoryDeclaration decl = territoryService.declare(player, territory);
            return ResponseEntity.ok(Map.of(
                "message",    "Attack declared on " + territory.displayName + "!",
                "territory",  territory.name(),
                "cycleId",    decl.getBattleCycleId()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Cancel declaration ────────────────────────────────────────────────────
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(Authentication auth) {
        try {
            territoryService.cancelDeclaration(getPlayer(auth));
            return ResponseEntity.ok(Map.of("message", "Declaration cancelled."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Battle history ────────────────────────────────────────────────────────
    @GetMapping("/{territory}/history")
    public ResponseEntity<?> history(@PathVariable Territory territory) {
        List<TerritoryBattleLog> logs = territoryService.getHistory(territory);
        List<?> result = logs.stream().map(l -> Map.of(
            "attacker",   l.getAttackerGuildName() != null ? l.getAttackerGuildName() : "?",
            "defender",   l.getDefenderGuildName() != null ? l.getDefenderGuildName() : "NPC",
            "winner",     l.getWinnerGuildName()   != null ? l.getWinnerGuildName()   : "?",
            "resolvedAt", l.getResolvedAt().toString(),
            "log",        l.getBattleLog() != null ? l.getBattleLog() : ""
        )).toList();
        return ResponseEntity.ok(result);
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
