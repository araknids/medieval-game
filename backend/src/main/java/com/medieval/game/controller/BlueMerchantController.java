package com.medieval.game.controller;

import com.medieval.game.model.Consignment;
import com.medieval.game.model.Player;
import com.medieval.game.service.BlueMerchantService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * [MERCADO_STEAM] Mercador Azul: consignar item (escrow → exporta pra Steam quando ligado), cancelar
 * (devolve), linkar conta Steam, e ver o estado. Inerte na Steam enquanto app.steam.enabled=false —
 * mas o fluxo de escrow/devolução já funciona. Ver docs/PLANO_MERCADO_STEAM.md.
 */
@RestController
@RequestMapping("/api/blue-merchant")
@RequiredArgsConstructor
public class BlueMerchantController {

    private final BlueMerchantService blueMerchant;
    private final PlayerService       playerService;

    @GetMapping
    public ResponseEntity<?> state(Authentication auth) {
        return ResponseEntity.ok(blueMerchant.state(getPlayer(auth)));
    }

    @PostMapping("/consign/{itemId}")
    public ResponseEntity<?> consign(@PathVariable Long itemId, Authentication auth) {
        Consignment c = blueMerchant.consign(getPlayer(auth), itemId);
        return ResponseEntity.ok(Map.of(
                "message", c.getStatus() == Consignment.Status.LINKED
                        ? com.medieval.game.service.Messages.tr("msg.consign_linked", "Item linked to the Steam marketplace!")
                        : com.medieval.game.service.Messages.tr("msg.consign_held", "Item handed to the Blue Merchant (held until Steam selling opens)."),
                "consignmentId", c.getId(),
                "status", c.getStatus().name()));
    }

    @PostMapping("/cancel/{id}")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        blueMerchant.cancel(getPlayer(auth), id);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.consign_cancelled", "Consignment cancelled — item returned to your bag.")));
    }

    @PostMapping("/link")
    public ResponseEntity<?> link(@RequestBody LinkRequest req, Authentication auth) {
        blueMerchant.linkSteam(getPlayer(auth), req.steamId());
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.steam_linked", "Steam account linked.")));
    }

    public record LinkRequest(String steamId) {}

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
