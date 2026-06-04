package com.medieval.game.controller;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.StashService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stash")
@RequiredArgsConstructor
public class StashController {

    private final StashService     stashService;
    private final PlayerService    playerService;
    private final InventoryService inventoryService;

    @GetMapping
    public ResponseEntity<?> getStash(Authentication auth) {
        Player p = getPlayer(auth);

        List<Map<String, Object>> items = stashService.stashItems(p).stream().map(i -> Map.<String, Object>of(
                "id",           i.getId(),
                "name",         i.getName(),
                "type",         i.getType().name(),
                "typeDisplay",  i.getType().displayName,
                "rarity",       i.getRarity(),
                "rarityName",   rarityName(i.getRarity()),
                "attackBonus",  i.getAttackBonus(),
                "defenseBonus", i.getDefenseBonus(),
                "healthBonus",  i.getHealthBonus()
        )).toList();

        List<Map<String, Object>> resources = stashService.stashResources(p).stream().map(r -> Map.<String, Object>of(
                "type",        r.getResourceType().name(),
                "displayName", r.getResourceType().displayName,
                "category",    r.getResourceType().category.name(),
                "quantity",    r.getQuantity()
        )).toList();

        return ResponseEntity.ok(Map.of(
                "items",     items,
                "resources", resources,
                "used",      stashService.stashSize(p),
                "max",       StashService.STASH_MAX,
                "fee",       StashService.STASH_FEE,
                "bagUsed",   inventoryService.bagSize(p),
                "bagMax",    p.getMaxInventorySlots()
        ));
    }

    @PostMapping("/deposit/item/{id}")
    public ResponseEntity<?> depositItem(@PathVariable Long id, Authentication auth) {
        Player p = getPlayer(auth);
        stashService.depositItem(p, id);
        return ResponseEntity.ok(result(p, "Item moved to stash."));
    }

    @PostMapping("/withdraw/item/{id}")
    public ResponseEntity<?> withdrawItem(@PathVariable Long id, Authentication auth) {
        Player p = getPlayer(auth);
        stashService.withdrawItem(p, id);
        return ResponseEntity.ok(result(p, "Item taken from stash."));
    }

    @PostMapping("/deposit/resource/{type}")
    public ResponseEntity<?> depositResource(@PathVariable ResourceType type, @RequestBody QtyRequest req, Authentication auth) {
        Player p = getPlayer(auth);
        stashService.depositResource(p, type, req.quantity());
        return ResponseEntity.ok(result(p, type.displayName + " moved to stash."));
    }

    @PostMapping("/withdraw/resource/{type}")
    public ResponseEntity<?> withdrawResource(@PathVariable ResourceType type, @RequestBody QtyRequest req, Authentication auth) {
        Player p = getPlayer(auth);
        stashService.withdrawResource(p, type, req.quantity());
        return ResponseEntity.ok(result(p, type.displayName + " taken from stash."));
    }

    private Map<String, Object> result(Player p, String message) {
        return Map.of(
                "message",  message,
                "stashUsed", stashService.stashSize(p),
                "bagUsed",   inventoryService.bagSize(p),
                "bagMax",    p.getMaxInventorySlots()
        );
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private static String rarityName(int r) {
        return switch (r) {
            case 2 -> "Incomum"; case 3 -> "Raro"; case 4 -> "Épico"; case 5 -> "Lendário"; default -> "Comum";
        };
    }

    record QtyRequest(long quantity) {}
}
