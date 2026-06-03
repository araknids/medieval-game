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

        long ssHealCdSecs = templeService.soulstoneHealCooldownSecs(player);

        boolean isVip          = player.isVip();
        long vipHealCdSecs     = isVip ? templeService.vipHealCooldownSecs(player) : -1;

        // Second buff info (VIP)
        String activeBuff2 = null;
        long   buff2SecsLeft = 0;
        if (warrior != null && warrior.hasActiveBuff2()) {
            activeBuff2 = warrior.getActiveBuff2().name();
            buff2SecsLeft = Math.max(0, ChronoUnit.SECONDS.between(
                    LocalDateTime.now(), warrior.getBuffExpiresAt2()));
        }

        return ResponseEntity.ok(Map.ofEntries(
            Map.entry("hpPercent",          hpPct),
            Map.entry("isKnockedOut",        warrior != null && warrior.isKnockedOut()),
            Map.entry("healCost",            healCost),
            Map.entry("healFree",            healCost == 0),
            Map.entry("protectedCount",      protectedCount),
            Map.entry("maxProtected",        3),
            Map.entry("activeBuff",          activeBuff != null ? activeBuff : ""),
            Map.entry("buffSecondsLeft",     buffSecondsLeft),
            Map.entry("activeBuff2",         activeBuff2 != null ? activeBuff2 : ""),
            Map.entry("buff2SecondsLeft",    buff2SecsLeft),
            Map.entry("buffs",               buffs),
            Map.entry("soulStones",          player.getSoulStones()),
            Map.entry("ssHealCooldownSecs",  ssHealCdSecs),
            Map.entry("ssHealReady",         ssHealCdSecs == 0),
            Map.entry("isVip",               isVip),
            Map.entry("vipHealCooldownSecs", isVip ? vipHealCdSecs : -1L),
            Map.entry("vipHealReady",        isVip && vipHealCdSecs == 0)
        ));
    }

    // Curar
    @PostMapping("/heal")
    public ResponseEntity<?> heal(Authentication auth) {
        templeService.heal(getPlayer(auth));
        return ResponseEntity.ok(Map.of("message", "Warrior healed! HP restored to 100%."));
    }

    // Aplicar bênção
    @PostMapping("/buff/{buffType}")
    public ResponseEntity<?> applyBuff(@PathVariable BuffType buffType, Authentication auth) {
        templeService.applyBuff(getPlayer(auth), buffType);
        return ResponseEntity.ok(Map.of(
            "message", buffType.displayName + " ativado por 1 hora!",
            "buff",    buffType.name()
        ));
    }

    // VIP — cura grátis (CD 10 min)
    @PostMapping("/vip-heal")
    public ResponseEntity<?> vipHeal(Authentication auth) {
        Player player = getPlayer(auth);
        templeService.vipHeal(player);
        return ResponseEntity.ok(Map.of("message", "VIP Heal! HP restored to 100% for free."));
    }

    // Proteger item
    @PostMapping("/protect/{itemId}")
    public ResponseEntity<?> protect(@PathVariable Long itemId, Authentication auth) {
        templeService.protectItem(getPlayer(auth), itemId);
        return ResponseEntity.ok(Map.of("message", "Item protegido pelo Templo!"));
    }

    // Remover proteção
    @PostMapping("/unprotect/{itemId}")
    public ResponseEntity<?> unprotect(@PathVariable Long itemId, Authentication auth) {
        templeService.unprotectItem(getPlayer(auth), itemId);
        return ResponseEntity.ok(Map.of("message", "Protection removed."));
    }

    // SoulStone — cura instantânea (1 💎, CD 30 min)
    @PostMapping("/soulstone-heal")
    public ResponseEntity<?> soulstoneHeal(Authentication auth) {
        Player player = getPlayer(auth);
        templeService.soulstoneHeal(player);
        return ResponseEntity.ok(Map.of(
            "message",    "Warrior instantly healed! HP restored to 100%.",
            "soulStones", player.getSoulStones()
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
