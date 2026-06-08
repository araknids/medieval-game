package com.medieval.game.controller;

import com.medieval.game.enums.MountType;
import com.medieval.game.model.Player;
import com.medieval.game.service.EstabuloService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Estábulo: compra/equipa montarias que reduzem estamina. Ver docs/PLANO_ESTABULO.md.
@RestController
@RequestMapping("/api/stable")
@RequiredArgsConstructor
public class EstabuloController {

    private final EstabuloService estabuloService;
    private final PlayerService   playerService;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        Player player = getPlayer(auth);
        List<?> mounts = estabuloService.list(player).stream().map(v -> Map.ofEntries(
            Map.entry("id",                  v.type().name()),
            Map.entry("displayName",         com.medieval.game.service.Messages.tr("mount." + v.type().name() + ".name", v.type().displayName)),
            Map.entry("icon",                v.type().icon),
            Map.entry("staminaReductionPct", v.type().staminaReductionPct),
            Map.entry("attackBonus",         v.type().attackBonus),
            Map.entry("defenseBonus",        v.type().defenseBonus),
            Map.entry("healthBonus",         v.type().healthBonus),
            Map.entry("priceGold",           v.type().priceGold),
            Map.entry("priceSoulStones",     v.type().priceSoulStones),
            Map.entry("vipOnly",             v.type().vipOnly),
            Map.entry("owned",               v.owned()),
            Map.entry("equipped",            v.equipped())
        )).toList();
        return ResponseEntity.ok(Map.of(
            "mounts",     mounts,
            "gold",       player.getGold(),
            "soulStones", player.getSoulStones(),
            "isVip",      player.isVip()
        ));
    }

    @PostMapping("/buy/{mountType}")
    public ResponseEntity<?> buy(@PathVariable MountType mountType, Authentication auth) {
        Player player = getPlayer(auth);
        estabuloService.buy(player, mountType);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("toast.mount_bought", "{0} bought!", com.medieval.game.service.Messages.tr("mount." + mountType.name() + ".name", mountType.displayName)),
            "gold",       player.getGold(),
            "soulStones", player.getSoulStones()
        ));
    }

    @PostMapping("/equip/{mountType}")
    public ResponseEntity<?> equip(@PathVariable MountType mountType, Authentication auth) {
        estabuloService.equip(getPlayer(auth), mountType);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("toast.mount_equipped", "{0} equipped!", com.medieval.game.service.Messages.tr("mount." + mountType.name() + ".name", mountType.displayName))));
    }

    @PostMapping("/unequip")
    public ResponseEntity<?> unequip(Authentication auth) {
        estabuloService.unequip(getPlayer(auth));
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.mount_unequipped", "Mount unequipped.")));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
