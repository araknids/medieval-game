package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.service.AuctionService;
import com.medieval.game.service.AuctionService.AuctionView;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Casa de Leilão: browse / minhas / postar / comprar / cancelar. [LEILAO] */
@RestController
@RequestMapping("/api/auction")
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;
    private final PlayerService  playerService;

    @GetMapping
    public ResponseEntity<?> browse(Authentication auth) {
        return ResponseEntity.ok(auctionService.browse(getPlayer(auth)).stream().map(this::toMap).toList());
    }

    @GetMapping("/mine")
    public ResponseEntity<?> mine(Authentication auth) {
        return ResponseEntity.ok(auctionService.mine(getPlayer(auth)).stream().map(this::toMap).toList());
    }

    @PostMapping("/list")
    public ResponseEntity<?> list(@RequestBody ListRequest req, Authentication auth) {
        var l = auctionService.list(getPlayer(auth), req.itemId(), req.price());
        return ResponseEntity.ok(Map.of("message", "Item listed!", "listingId", l.getId()));
    }

    @PostMapping("/buy/{id}")
    public ResponseEntity<?> buy(@PathVariable Long id, Authentication auth) {
        AuctionView v = auctionService.buy(getPlayer(auth), id);
        return ResponseEntity.ok(Map.of("message", "Bought " + v.name() + "!", "price", v.price()));
    }

    @PostMapping("/cancel/{id}")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        auctionService.cancel(getPlayer(auth), id);
        return ResponseEntity.ok(Map.of("message", "Listing cancelled — item returned to your bag."));
    }

    private Map<String, Object> toMap(AuctionView v) {
        return Map.ofEntries(
            Map.entry("listingId",    v.listingId()),
            Map.entry("price",        v.price()),
            Map.entry("sellerPayout", v.sellerPayout()),
            Map.entry("sellerName",   v.sellerName()),
            Map.entry("secondsLeft",  v.secondsLeft()),
            Map.entry("isMine",       v.isMine()),
            Map.entry("itemId",       v.itemId()),
            Map.entry("name",         v.name()),
            Map.entry("type",         v.type()),
            Map.entry("typeDisplay",  v.typeDisplay()),
            Map.entry("rarity",       v.rarity()),
            Map.entry("rarityName",   v.rarityName()),
            Map.entry("attackBonus",  v.attackBonus()),
            Map.entry("defenseBonus", v.defenseBonus()),
            Map.entry("healthBonus",  v.healthBonus()),
            Map.entry("sockets",      v.sockets()),
            Map.entry("durability",   v.durability()),
            Map.entry("itemLevel",    v.itemLevel()),
            Map.entry("affixes",      v.affixes()),
            Map.entry("gems",         v.gems())
        );
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    record ListRequest(Long itemId, long price) {}
}
