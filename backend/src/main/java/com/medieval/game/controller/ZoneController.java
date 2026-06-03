package com.medieval.game.controller;

import com.medieval.game.enums.ActivityRole;
import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.Zone;
import com.medieval.game.model.Player;
import com.medieval.game.model.ZoneActivity;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.ZoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/zones")
@RequiredArgsConstructor
public class ZoneController {

    private final ZoneService   zoneService;
    private final PlayerService playerService;

    // Lista todas as zonas com info
    @GetMapping
    public ResponseEntity<?> listZones() {
        var zones = Arrays.stream(Zone.values()).map(z -> Map.of(
            "id",          z.name(),
            "displayName", z.displayName,
            "description", z.description,
            "minLevel",    z.minLevel,
            "multiplier",  z.multiplier,
            "encounterChancePerHour",    z.encounterChancePerHour,
            "npcEncounterChancePerHour", z.npcEncounterChancePerHour
        )).toList();
        return ResponseEntity.ok(zones);
    }

    // Expedição ativa do jogador
    @GetMapping("/current")
    public ResponseEntity<?> getCurrent(Authentication auth) {
        Player player = getPlayer(auth);
        Optional<ZoneActivity> opt = zoneService.getCurrentActivity(player);
        if (opt.isEmpty()) return ResponseEntity.ok(Map.of("active", false));
        return ResponseEntity.ok(toMap(opt.get()));
    }

    // Histórico de expedições
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(Authentication auth) {
        Player player = getPlayer(auth);
        List<?> history = zoneService.getHistory(player).stream()
                .limit(10).map(this::toMap).toList();
        return ResponseEntity.ok(history);
    }

    // Entra numa zona
    @PostMapping("/enter")
    public ResponseEntity<?> enter(@RequestBody EnterRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            log.info("[ZONE-ENTER] player={} zone={} role={} skill={} duration={}min",
                    player.getId(), req.zone(), req.role(), req.skillType(), req.durationMinutes());
            ZoneActivity activity = zoneService.enter(player, req.zone(), req.role(),
                    req.skillType(), req.durationMinutes());
            log.info("[ZONE-ENTER] OK → activityId={}", activity.getId());
            return ResponseEntity.ok(toMap(activity));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[ZONE-ENTER] REJECTED → {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Coleta resultado da expedição
    @PostMapping("/{id}/collect")
    public ResponseEntity<?> collect(@PathVariable Long id, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            ZoneService.CollectResult result = zoneService.collect(player, id);

            var dropsResponse = result.drops().stream().map(d -> Map.of(
                "type",        d.type().name(),
                "displayName", d.type().displayName,
                "quantity",    d.quantity()
            )).toList();

            return ResponseEntity.ok(Map.ofEntries(
                Map.entry("status",       result.activity().getStatus().name()),
                Map.entry("wasAttacked",  result.wasAttacked()),
                Map.entry("survived",     result.survived()),
                Map.entry("drops",        dropsResponse),
                Map.entry("xpGained",     result.activity().getXpGained()),
                Map.entry("bronzeGained", result.activity().getBronzeGained()),
                Map.entry("bronzeLost",   result.activity().getBronzeLost()),
                Map.entry("lostItemName", result.lostItemName() != null ? result.lostItemName() : ""),
                Map.entry("attackerName", result.activity().getAttackerWarriorName() != null
                        ? result.activity().getAttackerWarriorName() : ""),
                Map.entry("battleLog",    result.activity().getBattleLog() != null
                        ? Arrays.asList(result.activity().getBattleLog().split("\n"))
                        : List.of())
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Cancela expedição
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        try {
            zoneService.cancel(getPlayer(auth), id);
            return ResponseEntity.ok(Map.of("message", "Expedition cancelled."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Continuar expedição após ser emboscado (dispensa o dialog de aviso)
    @PostMapping("/{id}/continue")
    public ResponseEntity<?> continueExpedition(@PathVariable Long id, Authentication auth) {
        try {
            zoneService.acknowledgeAmbush(getPlayer(auth), id);
            return ResponseEntity.ok(Map.of("message", "Expedition continues."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private Map<String, Object> toMap(ZoneActivity a) {
        long secs = a.getStatus().name().equals("IN_PROGRESS")
                ? Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), a.getEndsAt()))
                : 0;
        return Map.ofEntries(
            Map.entry("active",          true),
            Map.entry("id",              a.getId()),
            Map.entry("zone",            a.getZone().name()),
            Map.entry("zoneName",        a.getZone().displayName),
            Map.entry("role",            a.getRole().name()),
            Map.entry("skillType",       a.getSkillType() != null ? a.getSkillType().name() : ""),
            Map.entry("durationMinutes", a.getDurationMinutes()),
            Map.entry("status",          a.getStatus().name()),
            Map.entry("secondsRemaining",secs),
            Map.entry("readyToCollect",  a.isReadyToCollect() || a.isDefeated()),
            Map.entry("attacked",        a.isAttacked()),
            Map.entry("survived",        a.isSurvivedAttack()),
            Map.entry("attackerName",    a.getAttackerWarriorName() != null ? a.getAttackerWarriorName() : ""),
            Map.entry("bronzeLost",      a.getBronzeLost()),
            Map.entry("bronzeGained",    a.getBronzeGained()),
            Map.entry("xpGained",        a.getXpGained()),
            // Ambush dialog data
            Map.entry("ambushPending",   a.isAmbushPending()),
            Map.entry("ambushCount",     a.getAmbushCount()),
            Map.entry("lastAmbusherName",     a.getLastAmbusherName()     != null ? a.getLastAmbusherName()     : ""),
            Map.entry("lastAmbushBronzeLost", a.getLastAmbushBronzeLost()),
            Map.entry("lastAmbushItemLost",   a.getLastAmbushItemLost()   != null ? a.getLastAmbushItemLost()   : ""),
            Map.entry("lastAmbushLog",        a.getLastAmbushLog()        != null
                    ? Arrays.asList(a.getLastAmbushLog().split("\n")) : List.of())
        );
    }

    record EnterRequest(Zone zone, ActivityRole role, SkillType skillType, int durationMinutes) {}
}
