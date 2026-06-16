package com.medieval.game.controller;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.*;
import com.medieval.game.model.TerritoryDeclaration.DeclarationStatus;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.TerritoryDeclarationRepository;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.TerritoryService;
import com.medieval.game.service.TerritoryService.TerritoryBonus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/territory")
@RequiredArgsConstructor
public class TerritoryController {

    private final TerritoryService               territoryService;
    private final PlayerService                  playerService;
    private final PlayerRepository               playerRepository;
    private final TerritoryDeclarationRepository declarationRepo;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper(); // [GUERRA_GAUNTLET] parse dos eventos guardados

    // ── List all territories ──────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> listAll(Authentication auth) {
        Player player    = getPlayer(auth);
        TerritoryBonus myBonus = territoryService.getBonusForPlayer(player);

        // Get player's guild id for declaration check (avoid lazy proxy)
        Long myGuildId = playerRepository.findGuildByPlayerId(player.getId())
                .map(g -> g.getId()).orElse(null);
        long nextCycleId = territoryService.currentCycleId() + 1;

        List<?> territories = territoryService.warKingdoms().stream().map(t -> {
            TerritoryControl ctrl = territoryService.getTerritory(t);
            long secsUntilNext = 21600 - (Instant.now().getEpochSecond() % 21600);
            String guildName = ctrl.isNeutral() ? "" : ctrl.getControllingGuild().getName();

            // Pending declarations for this territory in next cycle
            List<TerritoryDeclaration> pending = declarationRepo
                    .findByTerritoryAndStatusOrderByDeclaredAtAsc(t, DeclarationStatus.PENDING)
                    .stream()
                    .filter(d -> d.getBattleCycleId() == nextCycleId)
                    .toList();

            List<String> declaringGuilds = pending.stream()
                    .map(d -> d.getGuild().getName())
                    .toList();

            boolean myGuildDeclared = myGuildId != null && pending.stream()
                    .anyMatch(d -> d.getGuild().getId().equals(myGuildId));

            // [AUDITORIA_UI_TERRITORIO] o cliente só mostra o botão "assistir" quando há replay —
            // mata o clique-morto ("Sem batalha pra assistir") da lista.
            boolean hasLastBattle = territoryService.getLatestBattleWithEvents(t).isPresent();

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
                Map.entry("exclusiveBonus",  t.exclusiveBonus),
                Map.entry("declaringGuilds", declaringGuilds),
                Map.entry("myGuildDeclared", myGuildDeclared),
                Map.entry("hasLastBattle",   hasLastBattle)
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
    public ResponseEntity<?> declare(@PathVariable Kingdom territory, Authentication auth) {
        Player player = getPlayer(auth);
        TerritoryDeclaration decl = territoryService.declare(player, territory);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("msg.attack_declared", "Attack declared on {0}!", territory.displayName),
            "territory",  territory.name(),
            "cycleId",    decl.getBattleCycleId()
        ));
    }

    // ── Cancel declaration ────────────────────────────────────────────────────
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel(Authentication auth) {
        territoryService.cancelDeclaration(getPlayer(auth));
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.declaration_cancelled", "Declaration cancelled.")));
    }

    // ── Battle history ────────────────────────────────────────────────────────
    @GetMapping("/{territory}/history")
    public ResponseEntity<?> history(@PathVariable Kingdom territory) {
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

    // ── Replay da última batalha (eventos do gauntlet p/ o 3D) ───────────────────
    @GetMapping("/{territory}/replay")
    public ResponseEntity<?> replay(@PathVariable Kingdom territory) {
        var opt = territoryService.getLatestBattleWithEvents(territory);
        if (opt.isEmpty()) return ResponseEntity.ok(Map.of("hasReplay", false));
        TerritoryBattleLog l = opt.get();
        Object events;
        try { events = MAPPER.readValue(l.getBattleEvents(), Object.class); }
        catch (Exception e) { return ResponseEntity.ok(Map.of("hasReplay", false)); }
        return ResponseEntity.ok(Map.of(
            "hasReplay",  true,
            "events",     events,
            "scene",      "castle",
            "attacker",   nz(l.getAttackerGuildName()),
            "defender",   nz(l.getDefenderGuildName()),
            "winner",     nz(l.getWinnerGuildName()),
            "resolvedAt", l.getResolvedAt().toString()
        ));
    }

    private static String nz(String s) { return s != null ? s : ""; }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
