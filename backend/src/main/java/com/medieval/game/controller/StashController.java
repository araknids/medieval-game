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
                "typeDisplay",  com.medieval.game.service.Messages.tr("itemtype." + i.getType().name() + ".name", i.getType().displayName),
                "rarity",       i.getRarity(),
                "rarityName",   rarityName(i.getRarity()),
                "itemLevel",    i.getItemLevel(),
                "attackBonus",  i.getAttackBonus(),
                "defenseBonus", i.getDefenseBonus(),
                "healthBonus",  i.getHealthBonus()
        )).toList();

        List<Map<String, Object>> resources = stashService.stashResources(p).stream().map(r -> Map.<String, Object>of(
                "type",        r.getResourceType().name(),
                "displayName", com.medieval.game.service.Messages.tr("resource." + r.getResourceType().name() + ".name", r.getResourceType().displayName),
                "category",    r.getResourceType().category.name(),
                "quantity",    r.getQuantity()
        )).toList();

        return ResponseEntity.ok(Map.of(
                "items",     items,
                "resources", resources,
                "used",      stashService.stashSize(p),
                "max",       -1, // stash ilimitado (UI mostra ∞)
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
        return ResponseEntity.ok(result(p, com.medieval.game.service.Messages.tr("toast.stash_in", "{0} moved to stash.", com.medieval.game.service.Messages.tr("resource." + type.name() + ".name", type.displayName))));
    }

    @PostMapping("/withdraw/resource/{type}")
    public ResponseEntity<?> withdrawResource(@PathVariable ResourceType type, @RequestBody QtyRequest req, Authentication auth) {
        Player p = getPlayer(auth);
        stashService.withdrawResource(p, type, req.quantity());
        return ResponseEntity.ok(result(p, com.medieval.game.service.Messages.tr("toast.stash_out", "{0} taken from stash.", com.medieval.game.service.Messages.tr("resource." + type.name() + ".name", type.displayName))));
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
