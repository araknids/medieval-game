package com.medieval.game.controller;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.SkillType;
import com.medieval.game.model.GatheringSession;
import com.medieval.game.model.Player;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.PlayerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/gathering")
@RequiredArgsConstructor
public class GatheringController {

    private final GatheringService gatheringService;
    private final PlayerService    playerService;

    // Skills do jogador
    @GetMapping("/skills")
    public ResponseEntity<?> getSkills(Authentication auth) {
        Player player = getPlayer(auth);
        var skills = gatheringService.getAllSkills(player).stream().map(s -> Map.of(
            "skillType",   s.getSkillType().name(),
            "displayName", s.getSkillType().displayName,
            "icon",        s.getSkillType().icon,
            "level",       s.getLevel(),
            "experience",  s.getExperience(),
            "expNeeded",   s.expNeededForNextLevel()
        )).toList();
        return ResponseEntity.ok(skills);
    }

    // Inventário de recursos
    @GetMapping("/resources")
    public ResponseEntity<?> getResources(Authentication auth) {
        Player player = getPlayer(auth);
        var resources = gatheringService.getResources(player).stream().map(r -> Map.of(
            "type",        r.getResourceType().name(),
            "displayName", r.getResourceType().displayName,
            "category",    r.getResourceType().category.name(),
            "quantity",    r.getQuantity()
        )).toList();
        return ResponseEntity.ok(resources);
    }

    // Sessão ativa
    @GetMapping("/current")
    public ResponseEntity<?> getCurrent(Authentication auth) {
        Player player = getPlayer(auth);
        Optional<GatheringSession> session = gatheringService.getCurrentSession(player);
        if (session.isEmpty()) return ResponseEntity.ok(Map.of("active", false));
        GatheringSession s = session.get();
        long secs = Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), s.getFinishesAt()));
        return ResponseEntity.ok(Map.of(
            "active",         true,
            "id",             s.getId(),
            "skillType",      s.getSkillType().name(),
            "displayName",    s.getSkillType().displayName,
            "durationMinutes",s.getDurationMinutes(),
            "xpReward",       s.getXpReward(),
            "secondsRemaining", secs,
            "readyToCollect", s.isReadyToCollect()
        ));
    }

    // Inicia coleta
    @PostMapping("/start")
    public ResponseEntity<?> start(@Valid @RequestBody StartRequest req, Authentication auth) {
        Player          player  = getPlayer(auth);
        GatheringSession session = gatheringService.startGathering(
                player, req.skillType(), req.durationMinutes(), req.kingdom());
        long secs = Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), session.getFinishesAt()));
        return ResponseEntity.ok(Map.of(
            "active",           true,
            "id",               session.getId(),
            "skillType",        session.getSkillType().name(),
            "displayName",      session.getSkillType().displayName,
            "durationMinutes",  session.getDurationMinutes(),
            "xpReward",         session.getXpReward(),
            "secondsRemaining", secs,
            "readyToCollect",   session.isReadyToCollect()
        ));
    }

    // Coleta resultado
    @PostMapping("/{id}/collect")
    public ResponseEntity<?> collect(@PathVariable Long id, Authentication auth) {
        Player player = getPlayer(auth);
        GatheringService.CollectResult result = gatheringService.collectGathering(player, id);
        var dropsResponse = result.drops().stream().map(d -> Map.of(
            "type",        d.type().name(),
            "displayName", d.type().displayName,
            "category",    d.type().category.name(),
            "quantity",    d.quantity()
        )).toList();
        return ResponseEntity.ok(Map.of(
            "drops",     dropsResponse,
            "narrative", result.narrative()
        ));
    }

    // Cancela coleta
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        gatheringService.cancelGathering(getPlayer(auth), id);
        return ResponseEntity.ok(Map.of("message", "Coleta cancelada."));
    }

    // Consome peixe (restaura stamina E HP)
    @PostMapping("/consume/{resourceType}")
    public ResponseEntity<?> consume(@PathVariable ResourceType resourceType, Authentication auth) {
        Player player = getPlayer(auth);
        var result = gatheringService.consumeFish(player, resourceType);
        return ResponseEntity.ok(Map.of(
            "message",    resourceType.displayName + " consumido!",
            "newStamina", result.newStamina(),
            "newHpPercent", result.newHpPercent()
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    // kingdom opcional — define o pool de drops (ex.: Mar Abençoado = peixe de vida). [REINOS_V2]
    record StartRequest(@NotNull SkillType skillType, @Min(5) @Max(60) int durationMinutes,
                        com.medieval.game.enums.Kingdom kingdom) {}
}
