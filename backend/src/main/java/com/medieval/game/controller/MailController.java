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
    private final com.medieval.game.service.GatheringService gatheringService; // [DAILY] claim de recurso

    // ── Inbox ─────────────────────────────────────────────────────────────────
    @GetMapping("/inbox")
    public ResponseEntity<?> inbox(Authentication auth) {
        Player player = getPlayer(auth);
        List<?> letters = mailService.inbox(player).stream().map(m -> toMap(m, false, false)).toList();
        long unread = mailService.unreadCount(player);
        return ResponseEntity.ok(Map.of("letters", letters, "unread", unread));
    }

    // ── Sent ──────────────────────────────────────────────────────────────────
    @GetMapping("/sent")
    public ResponseEntity<?> sent(Authentication auth) {
        List<?> letters = mailService.sent(getPlayer(auth)).stream()
                .map(m -> toMap(m, true, false)).toList();
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
        return ResponseEntity.ok(toMap(mail, false, true)); // full=true: o /read entrega o replay (log/eventos/cena)
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

    // ── Claim resource from mail (ex.: peixe da daily / overflow de bag cheia) [DAILY] ──
    @PostMapping("/{id}/claim-resource")
    public ResponseEntity<?> claimResource(@PathVariable Long id, Authentication auth) {
        Player player = getPlayer(auth);
        long added = mailService.claimResource(player, id, gatheringService);
        return ResponseEntity.ok(Map.of(
            "message", com.medieval.game.service.Messages.tr("msg.resource_claimed", "{0} added to your bag!", added),
            "added",   added
        ));
    }

    // ── Recolher TUDO (ouro + itens + recursos de todas as cartas) [MAIL_CLAIM_ALL] ──
    @PostMapping("/claim-all")
    public ResponseEntity<?> claimAll(Authentication auth) {
        Player player = getPlayer(auth);
        MailService.ClaimAllResult r = mailService.claimAll(player, inventoryService, gatheringService);
        return ResponseEntity.ok(Map.of(
            "message",       com.medieval.game.service.Messages.tr("msg.mail_claim_all", "Collected: {0} bronze, {1} item(s), {2} resource(s).", r.gold(), r.items(), r.resources()),
            "gold",          r.gold(),
            "items",         r.items(),
            "resources",     r.resources(),
            "leftItems",     r.leftItems(),
            "leftResources", r.leftResources()
        ));
    }

    // ── Apagar TODAS as cartas [MAIL_CLAIM_ALL] ──────────────────────────────────
    @PostMapping("/delete-all")
    public ResponseEntity<?> deleteAll(Authentication auth) {
        int n = mailService.deleteAll(getPlayer(auth));
        return ResponseEntity.ok(Map.of(
            "message", com.medieval.game.service.Messages.tr("msg.mail_deleted_all", "Deleted {0} letter(s).", n),
            "deleted", n
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

    // full=false (listas inbox/sent): omite os blobs pesados de replay (battleLog/eventsJson/scene) — a lista
    // só precisa do flag hasReplay; o cliente busca o replay no /read. Evita serializar JSON de batalha de N
    // cartas de raid de uma vez (risco de heap). full=true: entrega tudo (usado no /read). [LAUNCH_HARDENING]
    private Map<String, Object> toMap(Mail m, boolean isSent, boolean full) {
        return Map.ofEntries(
            Map.entry("id",              m.getId()),
            Map.entry("from",            m.getSenderWarriorName()),
            // [MAIL_ABAS] 0 = remetente do SISTEMA (recompensa/aviso); !=0 = carta de outro jogador.
            Map.entry("senderPlayerId",  m.getSenderPlayerId() != null ? m.getSenderPlayerId() : 0L),
            Map.entry("message",         m.getMessage()),
            Map.entry("goldAmount",      m.getGoldAmount()),
            Map.entry("sentAt",          m.getSentAt().toString()),
            Map.entry("isRead",          m.isRead()),
            Map.entry("isCollected",     m.isCollected()),
            Map.entry("hasGold",         m.getGoldAmount() > 0 && !m.isCollected()),
            Map.entry("hasItem",         m.hasItem()),
            Map.entry("itemName",        m.getItemName()  != null ? m.getItemName()  : ""),
            Map.entry("itemType",        m.getItemType()  != null ? m.getItemType()  : ""),  // [SLOT_WEAPON_IMG] ícone na UI
            Map.entry("outfitTheme",     m.getItemName()  != null ? com.medieval.game.service.InventoryService.outfitThemeFor(m.getItemName()) : ""),  // [OUTFITS_CLASSE]
            Map.entry("itemCollected",   m.isItemCollected()),
            Map.entry("hasResource",     m.hasResource() && !m.isResourceCollected()), // [DAILY]
            Map.entry("resourceType",    m.getResourceType() != null ? m.getResourceType() : ""),
            Map.entry("resourceQty",     m.getResourceQty()),
            Map.entry("resourceName",    m.hasResource()
                    ? com.medieval.game.service.Messages.tr("resource." + m.getResourceType() + ".name",
                          com.medieval.game.enums.ResourceType.valueOf(m.getResourceType()).displayName)
                    : ""),
            Map.entry("resourceCollected", m.isResourceCollected()),
            Map.entry("isExpired",       m.isExpired()),
            Map.entry("expiresAt",       m.getExpiresAt() != null ? m.getExpiresAt().toString() : ""),
            // [INCURSAO_PVP] replay anexado (mail de raid): log + eventos (JSON) + cena → o cliente toca o replay
            Map.entry("hasReplay",       m.hasReplay()),
            Map.entry("battleLog",       full && m.getBattleLog()        != null ? m.getBattleLog()        : ""),
            Map.entry("battleEventsJson",full && m.getBattleEventsJson() != null ? m.getBattleEventsJson() : ""),
            Map.entry("battleScene",     full && m.getBattleScene()      != null ? m.getBattleScene()      : "")
        );
    }

    record SendRequest(@NotBlank String recipientWarriorName, @NotBlank @Size(max = 500) String message,
                       @Min(0) long goldAmount) {}
}
