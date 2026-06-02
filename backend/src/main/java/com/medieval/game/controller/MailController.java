package com.medieval.game.controller;

import com.medieval.game.model.Mail;
import com.medieval.game.model.Player;
import com.medieval.game.service.MailService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailController {

    private final MailService  mailService;
    private final PlayerService playerService;

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
    public ResponseEntity<?> send(@RequestBody SendRequest req, Authentication auth) {
        try {
            Player sender = getPlayer(auth);
            Mail mail = mailService.send(sender,
                    req.recipientWarriorName(), req.message(), req.goldAmount());
            return ResponseEntity.ok(Map.of(
                "message", "Letter sent to " + req.recipientWarriorName() + "!",
                "id",      mail.getId()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Mark read + return full letter ────────────────────────────────────────
    @PostMapping("/{id}/read")
    public ResponseEntity<?> read(@PathVariable Long id, Authentication auth) {
        try {
            Mail mail = mailService.markRead(getPlayer(auth), id);
            return ResponseEntity.ok(toMap(mail, false));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Collect gold ──────────────────────────────────────────────────────────
    @PostMapping("/{id}/collect")
    public ResponseEntity<?> collect(@PathVariable Long id, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            Mail mail = mailService.collectGold(player, id);
            return ResponseEntity.ok(Map.of(
                "message",    "Collected " + mail.getGoldAmount() + " gold!",
                "goldAmount", mail.getGoldAmount()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        try {
            mailService.delete(getPlayer(auth), id);
            return ResponseEntity.ok(Map.of("message", "Letter deleted."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private Map<String, Object> toMap(Mail m, boolean isSent) {
        return Map.ofEntries(
            Map.entry("id",          m.getId()),
            Map.entry("from",        m.getSenderWarriorName()),
            Map.entry("message",     m.getMessage()),
            Map.entry("goldAmount",  m.getGoldAmount()),
            Map.entry("sentAt",      m.getSentAt().toString()),
            Map.entry("isRead",      m.isRead()),
            Map.entry("isCollected", m.isCollected()),
            Map.entry("hasGold",     m.getGoldAmount() > 0 && !m.isCollected())
        );
    }

    record SendRequest(String recipientWarriorName, String message, long goldAmount) {}
}
