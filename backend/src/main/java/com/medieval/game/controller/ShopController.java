package com.medieval.game.controller;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService   shopService;
    private final PlayerService playerService;

    @GetMapping
    public ResponseEntity<?> getShop(Authentication auth) {
        Player player = playerService.findById((Long) auth.getPrincipal());

        var items = shopService.getItems(player).stream().map(i -> Map.ofEntries(
                Map.entry("id",           i.id()),
                Map.entry("name",         i.name()),
                Map.entry("type",         i.type().name()),
                Map.entry("typeDisplay",  i.type().displayName),
                Map.entry("attackBonus",  i.atk()),
                Map.entry("defenseBonus", i.def()),
                Map.entry("healthBonus",  i.hp()),
                Map.entry("rarity",       i.rarity()),
                Map.entry("rarityName",   i.rarityName()),
                Map.entry("price",        i.price()),
                Map.entry("purchased",    i.purchased())
        )).toList();

        return ResponseEntity.ok(Map.of(
                "items",            items,
                "rotationId",       shopService.currentRotationId(),
                "secondsUntilNext", shopService.secondsUntilNextRotation(),
                "merchantName",     shopService.merchantName(),
                "merchantQuote",    shopService.merchantQuote()
        ));
    }

    @PostMapping("/buy/{shopItemId}")
    public ResponseEntity<?> buy(@PathVariable long shopItemId, Authentication auth) {
        try {
            Player        player = playerService.findById((Long) auth.getPrincipal());
            InventoryItem item   = shopService.buy(player, shopItemId);
            return ResponseEntity.ok(Map.of(
                    "message", item.getName() + " comprado com sucesso!",
                    "gold",    player.getGold(),
                    "itemId",  item.getId()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
