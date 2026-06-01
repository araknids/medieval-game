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

    private final ShopService  shopService;
    private final PlayerService playerService;

    @GetMapping
    public ResponseEntity<?> getShop() {
        var items = shopService.getItems().stream().map(i -> Map.of(
                "id",           i.id(),
                "name",         i.name(),
                "type",         i.type().name(),
                "typeDisplay",  i.type().displayName,
                "attackBonus",  i.atk(),
                "defenseBonus", i.def(),
                "healthBonus",  i.hp(),
                "rarity",       i.rarity(),
                "rarityName",   i.rarityName(),
                "price",        i.price()
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
            Player       player = playerService.findById((Long) auth.getPrincipal());
            InventoryItem item  = shopService.buy(player, shopItemId);
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
