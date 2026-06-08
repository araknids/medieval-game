package com.medieval.game.controller;

import com.medieval.game.model.Player;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.VipService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/vip")
@RequiredArgsConstructor
public class VipController {

    private final VipService   vipService;
    private final PlayerService playerService;

    // GET /api/vip/status — VIP status + daily counters
    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication auth) {
        Player player = getPlayer(auth);
        vipService.resetDailyCountersIfNeeded(player);
        return ResponseEntity.ok(vipService.status(player));
    }

    // POST /api/vip/buy — purchase or renew VIP (15 SoulStones, 30 days)
    @PostMapping("/buy")
    public ResponseEntity<?> buy(Authentication auth) {
        Player player = getPlayer(auth);
        vipService.buyVip(player);
        return ResponseEntity.ok(Map.of(
            "message",      com.medieval.game.service.Messages.tr("msg.vip_activated", "VIP activated! Enjoy your 30 days of benefits."),
            "vipExpiresAt", player.getVipExpiresAt().toString(),
            "soulStones",   player.getSoulStones()
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
