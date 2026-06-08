package com.medieval.game.controller;

import com.medieval.game.enums.ClassAbility;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.service.AbilityService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Habilidades de classe: árvore + aprender + respec. [HABILIDADES] */
@RestController
@RequestMapping("/api/abilities")
@RequiredArgsConstructor
public class AbilityController {

    private final AbilityService abilityService;
    private final PlayerService  playerService;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        Player  player = getPlayer(auth);
        Warrior w      = abilityService.warriorOf(player);
        var levels = abilityService.levels(w);
        var tree = ClassAbility.forClass(w.getWarriorClass()).stream().map(a -> Map.<String, Object>ofEntries(
            Map.entry("id",          a.name()),
            Map.entry("displayName", com.medieval.game.service.Messages.tr("ability." + a.name() + ".name", a.displayName)), // [I18N]
            Map.entry("icon",        a.icon),
            Map.entry("description", com.medieval.game.service.Messages.tr("ability." + a.name() + ".desc", a.description)), // [I18N]
            Map.entry("kind",        a.kind.name()),
            Map.entry("active",      a.isActive()),
            Map.entry("cooldown",    a.cooldown),
            Map.entry("level",       levels.getOrDefault(a, 0)),
            Map.entry("maxLevel",    ClassAbility.MAX_LEVEL)
        )).toList();
        return ResponseEntity.ok(Map.of(
            "class",         com.medieval.game.service.Messages.tr("class." + w.getWarriorClass().name() + ".name", w.getWarriorClass().displayName), // [I18N]
            "classId",       w.getWarriorClass().name(),
            "abilityPoints", w.getAbilityPoints(),
            "respecCost",    abilityService.respecCost(),
            "abilities",     tree
        ));
    }

    @PostMapping("/learn/{ability}")
    public ResponseEntity<?> learn(@PathVariable ClassAbility ability, Authentication auth) {
        abilityService.learn(getPlayer(auth), ability);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("toast.ability_upgraded", "{0} {1} upgraded!", ability.icon, com.medieval.game.service.Messages.tr("ability." + ability.name() + ".name", ability.displayName))));
    }

    @PostMapping("/respec")
    public ResponseEntity<?> respec(Authentication auth) {
        abilityService.respec(getPlayer(auth));
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.abilities_reset", "Abilities reset — points refunded.")));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
