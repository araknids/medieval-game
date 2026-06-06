package com.medieval.game.controller;

import com.medieval.game.enums.ActivityRole;
import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.Zone;
import com.medieval.game.model.Player;
import com.medieval.game.model.ZoneActivity;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.ZoneCollectCoordinator;
import com.medieval.game.service.ZoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

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

    private final ZoneService             zoneService;
    private final ZoneCollectCoordinator  zoneCollectCoordinator;
    private final PlayerService           playerService;

    // Lista todas as zonas com info
    @GetMapping
    public ResponseEntity<?> listZones() {
        var zones = Arrays.stream(Zone.values()).map(z -> Map.of(
            "id",          z.name(),
            "displayName", z.displayName,
            "description", z.description,
            "minLevel",    z.minLevel,
            "multiplier",  z.multiplier,
            "pvpEncounterChance", z.pvpEncounterChance,
            "npcEncounterChance", z.npcEncounterChance
        )).toList();
        return ResponseEntity.ok(zones);
    }

    // Status de PvP por flag do jogador (exposto/protegido). [PVP_FLAG]
    @GetMapping("/pvp-status")
    public ResponseEntity<?> pvpStatus(Authentication auth) {
        Player player = getPlayer(auth);
        long flagMin = player.isPvpFlagged()
                ? Math.max(0, ChronoUnit.MINUTES.between(LocalDateTime.now(), player.getPvpFlaggedUntil()) + 1) : 0;
        long shieldMin = player.isPvpShielded()
                ? Math.max(0, ChronoUnit.MINUTES.between(LocalDateTime.now(), player.getPvpShieldUntil()) + 1) : 0;
        return ResponseEntity.ok(Map.of(
            "flagged",           player.isPvpFlagged(),
            "flaggedZone",       player.getPvpFlaggedZone() != null ? player.getPvpFlaggedZone().displayName : "",
            "flaggedZoneId",     player.getPvpFlaggedZone() != null ? player.getPvpFlaggedZone().name() : "",
            "flagMinutesLeft",   flagMin,
            "shielded",          player.isPvpShielded(),
            "shieldMinutesLeft", shieldMin
        ));
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
    public ResponseEntity<?> enter(@Valid @RequestBody EnterRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            log.info("[ZONE-ENTER] player={} zone={} role={} skill={} duration={}min",
                    player.getId(), req.zone(), req.role(), req.skillType(), req.durationMinutes());
            ZoneActivity activity = zoneService.enter(player, req.zone(), req.role(),
                    req.skillType(), req.durationMinutes(), req.kingdom(), req.element());
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
        // Retry transparente: a emboscada toca 2 linhas; sob concorrência refaz em transação nova. [BL-1]
        ZoneService.CollectResult result =
                zoneCollectCoordinator.collectWithRetry((Long) auth.getPrincipal(), id);

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
                    : List.of()),
            Map.entry("narrative",    result.narrative() != null ? result.narrative() : "")
        ));
    }

    // Cancela expedição
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        zoneService.cancel(getPlayer(auth), id);
        return ResponseEntity.ok(Map.of("message", "Expedition cancelled."));
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
            Map.entry("xpGained",        a.getXpGained())
        );
    }

    record EnterRequest(@NotNull Zone zone, @NotNull ActivityRole role, SkillType skillType,
                        @Min(5) @Max(720) int durationMinutes,
                        com.medieval.game.enums.Kingdom kingdom,
                        com.medieval.game.enums.Element element) {}
}
