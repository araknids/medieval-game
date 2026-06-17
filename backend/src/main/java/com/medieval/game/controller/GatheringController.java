package com.medieval.game.controller;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.SkillType;
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
        var skills = gatheringService.getAllSkills(player).stream().map(s -> Map.<String, Object>of(
            "skillType",   s.getSkillType().name(),
            "displayName", com.medieval.game.service.Messages.tr("skill." + s.getSkillType().name() + ".name", s.getSkillType().displayName),
            "icon",        s.getSkillType().icon,
            "level",       s.getLevel(),
            "experience",  s.getExperience(),
            "expNeeded",   s.expNeededForNextLevel(),
            // [PROFISSAO_SUCCESS] próximo nível que libera um tier de recurso melhor (0 = Forja/sem tiers ou maxado)
            "nextTierLevel", s.getSkillType() == SkillType.SMITHING ? 0 : gatheringService.nextTierLevel(s.getLevel())
        )).toList();
        return ResponseEntity.ok(skills);
    }

    // Inventário de recursos
    @GetMapping("/resources")
    public ResponseEntity<?> getResources(Authentication auth) {
        Player player = getPlayer(auth);
        var resources = gatheringService.getResources(player).stream().map(r -> Map.of(
            "type",        r.getResourceType().name(),
            "displayName", com.medieval.game.service.Messages.tr("resource." + r.getResourceType().name() + ".name", r.getResourceType().displayName),
            "category",    r.getResourceType().category.name(),
            "quantity",    r.getQuantity(),
            // [RECURSOS] peixe: estamina/HP restaurados ao consumir (0 = não se aplica) — UI mostra no hover
            "consumeStamina", com.medieval.game.service.GatheringService.fishStaminaValue(r.getResourceType()),
            "consumeHp",      com.medieval.game.service.GatheringService.fishHpValue(r.getResourceType())
        )).toList();
        return ResponseEntity.ok(resources);
    }

    // Consome peixe (restaura stamina E HP)
    @PostMapping("/consume/{resourceType}")
    public ResponseEntity<?> consume(@PathVariable ResourceType resourceType, Authentication auth) {
        Player player = getPlayer(auth);
        var result = gatheringService.consumeFish(player, resourceType);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("toast.resource_consumed", "{0} consumed!", com.medieval.game.service.Messages.tr("resource." + resourceType.name() + ".name", resourceType.displayName)),
            "newStamina", result.newStamina(),
            "newHpPercent", result.newHpPercent()
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
