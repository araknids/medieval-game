package com.medieval.game.controller;

import com.medieval.game.enums.PetType;
import com.medieval.game.model.Player;
import com.medieval.game.service.PetService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService    petService;
    private final PlayerService playerService;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        Player player = getPlayer(auth);
        var pets = petService.list(player).stream().map(v -> Map.of(
            "type",           v.type().name(),
            "displayName",    com.medieval.game.service.Messages.tr("pet." + v.type().name() + ".name", v.displayName()),
            "icon",           v.icon(),
            "hpBonusPercent", v.hpBonusPercent(),
            "dexBonus",       v.dexBonus(),
            "soulStoneCost",  v.soulStoneCost(),
            "owned",          v.owned(),
            "equipped",       v.equipped()
        )).toList();
        return ResponseEntity.ok(pets);
    }

    // Comprar um pet com SoulStone (mercado VIP). [PETS]
    @PostMapping("/buy/{petType}")
    public ResponseEntity<?> buy(@PathVariable PetType petType, Authentication auth) {
        Player player = getPlayer(auth);
        petService.buy(player, petType);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("toast.pet_adopted", "{0} adopted!", com.medieval.game.service.Messages.tr("pet." + petType.name() + ".name", petType.displayName)),
            "soulStones", player.getSoulStones()
        ));
    }

    @PostMapping("/equip/{petType}")
    public ResponseEntity<?> equip(@PathVariable PetType petType, Authentication auth) {
        petService.equip(getPlayer(auth), petType);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("toast.pet_equipped", "{0} equipped.", com.medieval.game.service.Messages.tr("pet." + petType.name() + ".name", petType.displayName))));
    }

    @PostMapping("/unequip")
    public ResponseEntity<?> unequip(Authentication auth) {
        petService.unequip(getPlayer(auth));
        return ResponseEntity.ok(Map.of("message", "Pet unequipped."));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
