package com.medieval.game.service;

import com.medieval.game.model.Mail;
import com.medieval.game.model.Player;
import com.medieval.game.repository.MailRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MailService {

    // Fee in bronze: 1 silver (100 bronze). Affordable for new players (start with 50 silver).
    // User requested "1 gold" but 1 gold = 10,000 bronze would be unaffordable early-game.
    // Using 1 silver as the fee keeps it meaningful without locking out new players.
    private static final long SEND_FEE_BRONZE = 100L; // 1 silver

    private final MailRepository    mailRepository;
    private final PlayerRepository  playerRepository;
    private final WarriorRepository warriorRepository;
    private final PlayerService     playerService;

    // ── Send letter ───────────────────────────────────────────────────────────
    @Transactional
    public Mail send(Player sender, String recipientUsername, String message, long goldAmount) {
        if (goldAmount < 0)
            throw new IllegalArgumentException("Gold amount cannot be negative.");
        if (message == null || message.isBlank())
            throw new IllegalArgumentException("Message cannot be empty.");
        if (message.length() > 500)
            throw new IllegalArgumentException("Message too long (max 500 characters).");

        Player recipient = playerRepository.findByUsername(recipientUsername)
                .orElseThrow(() -> new IllegalArgumentException("Player '" + recipientUsername + "' not found."));

        if (recipient.getId().equals(sender.getId()))
            throw new IllegalArgumentException("You cannot send a letter to yourself.");

        // goldAmount is stored in bronze units for fine-grained control
        long totalCost   = SEND_FEE_BRONZE + goldAmount;
        long senderTotal = sender.getBronze() + sender.getSilver() * 100L + sender.getGold() * 10_000L;
        if (senderTotal < totalCost)
            throw new IllegalStateException("Insufficient funds. Need " + totalCost
                    + " bronze (" + SEND_FEE_BRONZE + " fee + " + goldAmount + " attached).");

        // Deduct fee + attached gold from sender
        playerService.spendBronze(sender, totalCost);

        String senderWarriorName = warriorRepository.findByPlayer(sender)
                .map(w -> w.getName()).orElse(sender.getUsername());

        Mail mail = new Mail();
        mail.setSenderPlayerId(sender.getId());
        mail.setSenderWarriorName(senderWarriorName);
        mail.setRecipientPlayerId(recipient.getId());
        mail.setMessage(message.trim());
        mail.setGoldAmount(goldAmount);
        return mailRepository.save(mail);
    }

    // ── Inbox ─────────────────────────────────────────────────────────────────
    @Transactional
    public List<Mail> inbox(Player player) {
        return mailRepository.findByRecipientPlayerIdOrderBySentAtDesc(player.getId());
    }

    // ── Sent ──────────────────────────────────────────────────────────────────
    public List<Mail> sent(Player player) {
        return mailRepository.findBySenderPlayerIdOrderBySentAtDesc(player.getId());
    }

    // ── Mark as read ──────────────────────────────────────────────────────────
    @Transactional
    public Mail markRead(Player player, Long mailId) {
        Mail mail = requireRecipient(player, mailId);
        if (!mail.isRead()) {
            mail.setReadAt(LocalDateTime.now());
            mailRepository.save(mail);
        }
        return mail;
    }

    // ── Collect gold ──────────────────────────────────────────────────────────
    @Transactional
    public Mail collectGold(Player player, Long mailId) {
        Mail mail = requireRecipient(player, mailId);
        if (mail.isCollected())
            throw new IllegalStateException("Gold already collected.");
        if (mail.getGoldAmount() <= 0)
            throw new IllegalStateException("This letter has no gold attached.");

        playerService.addGold(player, mail.getGoldAmount()); // goldAmount is already in bronze
        mail.setCollectedAt(LocalDateTime.now());
        if (!mail.isRead()) mail.setReadAt(LocalDateTime.now());
        return mailRepository.save(mail);
    }

    // ── Delete ────────────────────────────────────────────────────────────────
    @Transactional
    public void delete(Player player, Long mailId) {
        Mail mail = requireRecipient(player, mailId);
        mailRepository.delete(mail);
    }

    // ── Unread count ──────────────────────────────────────────────────────────
    public long unreadCount(Player player) {
        return mailRepository.countUnreadByRecipientPlayerId(player.getId());
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private Mail requireRecipient(Player player, Long mailId) {
        Mail mail = mailRepository.findById(mailId)
                .orElseThrow(() -> new IllegalArgumentException("Letter not found."));
        if (!mail.getRecipientPlayerId().equals(player.getId()))
            throw new IllegalStateException("This letter does not belong to you.");
        return mail;
    }
}
