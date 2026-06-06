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
            "displayName",    v.displayName(),
            "icon",           v.icon(),
            "hpBonusPercent", v.hpBonusPercent(),
            "owned",          v.owned(),
            "equipped",       v.equipped()
        )).toList();
        return ResponseEntity.ok(pets);
    }

    @PostMapping("/equip/{petType}")
    public ResponseEntity<?> equip(@PathVariable PetType petType, Authentication auth) {
        petService.equip(getPlayer(auth), petType);
        return ResponseEntity.ok(Map.of("message", petType.displayName + " equipped."));
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
