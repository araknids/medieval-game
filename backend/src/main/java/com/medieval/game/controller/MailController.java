package com.medieval.game.controller;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Mail;
import com.medieval.game.model.Player;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.MailService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailController {

    private final MailService     mailService;
    private final PlayerService   playerService;
    private final InventoryService inventoryService;

    // ── Inbox ─────────────────────────────────────────────────────────────────
    @GetMapping("/inbox")
    public ResponseEntity<?> inbox(Authentication auth) {
        Player player = getPlayer(auth);
        List<?> letters = mailService.inbox(player).stream().map(m -> toMap(m, false)).toList();
        long unread = mailService.unreadCount(player);
        return ResponseEntity.ok(Map.of("letters", letters, "unread", unread));
    }

    // ── Sent ──────────────────────────────────────────────────────────────────
    @GetMapping("/sent")
    public ResponseEntity<?> sent(Authentication auth) {
        List<?> letters = mailService.sent(getPlayer(auth)).stream()
                .map(m -> toMap(m, true)).toList();
        return ResponseEntity.ok(letters);
    }

    // ── Send letter ───────────────────────────────────────────────────────────
    @PostMapping("/send")
    public ResponseEntity<?> send(@Valid @RequestBody SendRequest req, Authentication auth) {
        Player sender = getPlayer(auth);
        Mail mail = mailService.send(sender,
                req.recipientWarriorName(), req.message(), req.goldAmount());
        return ResponseEntity.ok(Map.of(
            "message", com.medieval.game.service.Messages.tr("msg.letter_sent", "Letter sent to {0}!", req.recipientWarriorName()),
            "id",      mail.getId()
        ));
    }

    // ── Mark read + return full letter ────────────────────────────────────────
    @PostMapping("/{id}/read")
    public ResponseEntity<?> read(@PathVariable Long id, Authentication auth) {
        Mail mail = mailService.markRead(getPlayer(auth), id);
        return ResponseEntity.ok(toMap(mail, false));
    }

    // ── Collect gold ──────────────────────────────────────────────────────────
    @PostMapping("/{id}/collect")
    public ResponseEntity<?> collect(@PathVariable Long id, Authentication auth) {
        Player player = getPlayer(auth);
        Mail mail = mailService.collectGold(player, id);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("msg.gold_collected", "Collected {0} gold!", mail.getGoldAmount()),
            "goldAmount", mail.getGoldAmount()
        ));
    }

    // ── Claim item from mail ──────────────────────────────────────────────────
    @PostMapping("/{id}/claim-item")
    public ResponseEntity<?> claimItem(@PathVariable Long id, Authentication auth) {
        Player player = getPlayer(auth);
        InventoryItem item = mailService.claimItem(player, id, inventoryService);
        return ResponseEntity.ok(Map.of(
            "message",  com.medieval.game.service.Messages.tr("msg.item_claimed", "Item ''{0}'' added to your bag!", item.getName()),
            "itemName", item.getName()
        ));
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        mailService.delete(getPlayer(auth), id);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.letter_deleted", "Letter deleted.")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private Map<String, Object> toMap(Mail m, boolean isSent) {
        return Map.ofEntries(
            Map.entry("id",              m.getId()),
            Map.entry("from",            m.getSenderWarriorName()),
            Map.entry("message",         m.getMessage()),
            Map.entry("goldAmount",      m.getGoldAmount()),
            Map.entry("sentAt",          m.getSentAt().toString()),
            Map.entry("isRead",          m.isRead()),
            Map.entry("isCollected",     m.isCollected()),
            Map.entry("hasGold",         m.getGoldAmount() > 0 && !m.isCollected()),
            Map.entry("hasItem",         m.hasItem()),
            Map.entry("itemName",        m.getItemName()  != null ? m.getItemName()  : ""),
            Map.entry("itemCollected",   m.isItemCollected()),
            Map.entry("isExpired",       m.isExpired()),
            Map.entry("expiresAt",       m.getExpiresAt() != null ? m.getExpiresAt().toString() : "")
        );
    }

    record SendRequest(@NotBlank String recipientWarriorName, @NotBlank @Size(max = 500) String message,
                       @Min(0) long goldAmount) {}
}
