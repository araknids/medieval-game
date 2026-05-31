package com.medieval.game.controller;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final PlayerService playerService;

    @GetMapping
    public ResponseEntity<List<ItemResponse>> getInventory(Authentication auth) {
        Player player = getPlayer(auth);
        return ResponseEntity.ok(
            inventoryService.getInventory(player).stream().map(ItemResponse::from).toList()
        );
    }

    @PostMapping("/{id}/equip")
    public ResponseEntity<?> equip(@PathVariable Long id, Authentication auth) {
        try {
            InventoryItem item = inventoryService.equip(getPlayer(auth), id);
            return ResponseEntity.ok(ItemResponse.from(item));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/unequip")
    public ResponseEntity<?> unequip(@PathVariable Long id, Authentication auth) {
        try {
            InventoryItem item = inventoryService.unequip(getPlayer(auth), id);
            return ResponseEntity.ok(ItemResponse.from(item));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/sell")
    public ResponseEntity<?> sell(@PathVariable Long id, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            InventoryItem item = inventoryService.sell(player, id);
            return ResponseEntity.ok(Map.of(
                "message",   item.getName() + " vendido!",
                "goldEarned", item.getSellPrice(),
                "gold",       player.getGold()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    record ItemResponse(Long id, String name, String type, String typeDisplay,
                        int attackBonus, int defenseBonus, int healthBonus,
                        int rarity, String rarityName, long sellPrice, boolean equipped) {
        static ItemResponse from(InventoryItem i) {
            return new ItemResponse(
                i.getId(), i.getName(),
                i.getType().name(), i.getType().displayName,
                i.getAttackBonus(), i.getDefenseBonus(), i.getHealthBonus(),
                i.getRarity(), rarityName(i.getRarity()), i.getSellPrice(), i.isEquipped()
            );
        }
        static String rarityName(int r) {
            return switch (r) {
                case 2 -> "Incomum"; case 3 -> "Raro"; case 4 -> "Épico"; default -> "Comum";
            };
        }
    }
}
