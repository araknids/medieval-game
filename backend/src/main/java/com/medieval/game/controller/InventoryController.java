package com.medieval.game.controller;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.SocketedGem;
import com.medieval.game.repository.SocketedGemRepository;
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

    private final InventoryService      inventoryService;
    private final PlayerService         playerService;
    private final SocketedGemRepository gemRepository;

    @GetMapping
    public ResponseEntity<List<ItemResponse>> getInventory(Authentication auth) {
        Player player = getPlayer(auth);
        return ResponseEntity.ok(
            inventoryService.getInventory(player).stream()
                .map(i -> ItemResponse.from(i, gemRepository.findAllByItem(i)))
                .toList()
        );
    }

    @PostMapping("/{id}/equip")
    public ResponseEntity<?> equip(@PathVariable Long id, Authentication auth) {
        try {
            InventoryItem item = inventoryService.equip(getPlayer(auth), id);
            return ResponseEntity.ok(ItemResponse.from(item, gemRepository.findAllByItem(item)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/unequip")
    public ResponseEntity<?> unequip(@PathVariable Long id, Authentication auth) {
        try {
            InventoryItem item = inventoryService.unequip(getPlayer(auth), id);
            return ResponseEntity.ok(ItemResponse.from(item, gemRepository.findAllByItem(item)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/sell")
    public ResponseEntity<?> sell(@PathVariable Long id, Authentication auth) {
        try {
            Player        player = getPlayer(auth);
            InventoryItem item   = inventoryService.sell(player, id);
            return ResponseEntity.ok(Map.of(
                "message",    item.getName() + " vendido!",
                "goldEarned", item.getSellPrice(),
                "gold",       player.getGold()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // GET /api/inventory/slots — info de bag para o frontend
    @GetMapping("/slots")
    public ResponseEntity<?> slots(Authentication auth) {
        Player player = getPlayer(auth);
        return ResponseEntity.ok(Map.of(
            "bagSize",           inventoryService.bagSize(player),
            "maxSlots",          player.getMaxInventorySlots(),
            "inventoryExpanded", player.isInventoryExpanded(),
            "soulStones",        player.getSoulStones()
        ));
    }

    // POST /api/inventory/expand — expande bag (3 SoulStones, permanente)
    @PostMapping("/expand")
    public ResponseEntity<?> expand(Authentication auth) {
        try {
            Player player = getPlayer(auth);
            inventoryService.expandInventory(player);
            return ResponseEntity.ok(Map.of(
                "message",    "Inventory expanded to 20 slots!",
                "maxSlots",   player.getMaxInventorySlots(),
                "soulStones", player.getSoulStones()
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
                        int rarity, String rarityName, long sellPrice,
                        int sockets, List<GemSlot> gems,
                        boolean equipped, boolean guarded,
                        String description, String origin) {

        static ItemResponse from(InventoryItem i, List<SocketedGem> socketedGems) {
            List<GemSlot> gems = socketedGems.stream()
                    .map(g -> new GemSlot(g.getSlotIndex(), g.getGemType().name(), g.getGemType().displayName))
                    .toList();
            return new ItemResponse(
                i.getId(), i.getName(),
                i.getType().name(), i.getType().displayName,
                i.getAttackBonus(), i.getDefenseBonus(), i.getHealthBonus(),
                i.getRarity(), rarityName(i.getRarity()), i.getSellPrice(),
                i.getSockets(), gems,
                i.isEquipped(), i.isGuarded(),
                i.getDescription() != null ? i.getDescription() : "",
                i.getOrigin()      != null ? i.getOrigin()      : ""
            );
        }

        static String rarityName(int r) {
            return switch (r) {
                case 2 -> "Incomum"; case 3 -> "Raro"; case 4 -> "Épico"; default -> "Comum";
            };
        }
    }

    record GemSlot(int slot, String gem, String gemName) {}
}
