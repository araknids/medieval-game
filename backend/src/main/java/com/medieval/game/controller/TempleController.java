package com.medieval.game.controller;

import com.medieval.game.enums.BuffType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.TempleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/temple")
@RequiredArgsConstructor
public class TempleController {

    private final TempleService     templeService;
    private final PlayerService     playerService;
    private final WarriorRepository warriorRepository;

    // Info do templo para o jogador atual
    @GetMapping
    public ResponseEntity<?> getInfo(Authentication auth) {
        Player  player  = getPlayer(auth);
        Warrior warrior = warriorRepository.findByPlayer(player).orElse(null);

        int  hpPct  = warrior != null ? warrior.getCalculatedHpPercent() : 100;
        long healCost = warrior != null ? templeService.healCost(warrior) : 0;
        long protectedCount = templeService.countProtected(player);

        String activeBuff    = warrior != null && warrior.hasActiveBuff()
                ? warrior.getActiveBuff().name() : null;
        long buffSecondsLeft = 0;
        if (warrior != null && warrior.hasActiveBuff()) {
            buffSecondsLeft = Math.max(0, ChronoUnit.SECONDS.between(
                    LocalDateTime.now(), warrior.getBuffExpiresAt()));
        }

        var buffs = Arrays.stream(BuffType.values()).map(b -> Map.of(
            "id",          b.name(),
            "displayName", b.displayName,
            "icon",        b.icon,
            "effect",      b.effect,
            "bronzeCost",  b.bronzeCost
        )).toList();

        return ResponseEntity.ok(Map.of(
            "hpPercent",        hpPct,
            "isKnockedOut",     warrior != null && warrior.isKnockedOut(),
            "healCost",         healCost,
            "healFree",         healCost == 0,
            "protectedCount",   protectedCount,
            "maxProtected",     3,
            "activeBuff",       activeBuff != null ? activeBuff : "",
            "buffSecondsLeft",  buffSecondsLeft,
            "buffs",            buffs
        ));
    }

    // Curar
    @PostMapping("/heal")
    public ResponseEntity<?> heal(Authentication auth) {
        try {
            templeService.heal(getPlayer(auth));
            return ResponseEntity.ok(Map.of("message", "Warrior healed! HP restored to 100%."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Aplicar bênção
    @PostMapping("/buff/{buffType}")
    public ResponseEntity<?> applyBuff(@PathVariable BuffType buffType, Authentication auth) {
        try {
            templeService.applyBuff(getPlayer(auth), buffType);
            return ResponseEntity.ok(Map.of(
                "message", buffType.displayName + " ativado por 1 hora!",
                "buff",    buffType.name()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Proteger item
    @PostMapping("/protect/{itemId}")
    public ResponseEntity<?> protect(@PathVariable Long itemId, Authentication auth) {
        try {
            templeService.protectItem(getPlayer(auth), itemId);
            return ResponseEntity.ok(Map.of("message", "Item protegido pelo Templo!"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Remover proteção
    @PostMapping("/unprotect/{itemId}")
    public ResponseEntity<?> unprotect(@PathVariable Long itemId, Authentication auth) {
        try {
            templeService.unprotectItem(getPlayer(auth), itemId);
            return ResponseEntity.ok(Map.of("message", "Protection removed."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
