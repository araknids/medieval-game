package com.medieval.game.controller;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// [UNIFICAÇÃO_ZONA] A coleta (start/collect) migrou pro sistema de Zona (/api/zones).
// Aqui ficam só: skills, inventário de recursos e consumo de peixe.
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
}
