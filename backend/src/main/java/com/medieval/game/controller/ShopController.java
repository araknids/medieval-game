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
                Map.entry("typeDisplay",  com.medieval.game.service.Messages.tr("itemtype." + i.type().name() + ".name", i.type().displayName)),
                Map.entry("attackBonus",  i.atk()),
                Map.entry("defenseBonus", i.def()),
                Map.entry("healthBonus",  i.hp()),
                Map.entry("strBonus",     i.str()), // [CLASSES_ARMAS]
                Map.entry("dexBonus",     i.dex()),
                Map.entry("lukBonus",     i.luk()),
                Map.entry("rarity",       i.rarity()),
                Map.entry("rarityName",   i.rarityName()),
                Map.entry("itemLevel",    i.itemLevel()),
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
        Player        player = playerService.findById((Long) auth.getPrincipal());
        InventoryItem item   = shopService.buy(player, shopItemId);
        if (item == null) { // bag cheia → item foi pro mail
            return ResponseEntity.ok(Map.of(
                    "message", "Bag full — the item was sent to your mail.",
                    "gold",    player.getGold()
            ));
        }
        return ResponseEntity.ok(Map.of(
                "message", item.getName() + " bought successfully!",
                "gold",    player.getGold(),
                "itemId",  item.getId()
        ));
    }
}
