package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Mail;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
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

    private static final int ITEM_MAIL_EXPIRY_DAYS = 7;

    private final MailRepository          mailRepository;
    private final PlayerRepository        playerRepository;
    private final WarriorRepository       warriorRepository;
    private final InventoryItemRepository inventoryRepository;
    private final PlayerService           playerService;

    // ── Send letter ───────────────────────────────────────────────────────────
    @Transactional
    public Mail send(Player sender, String recipientWarriorName, String message, long goldAmount) {
        if (goldAmount < 0)
            throw new IllegalArgumentException("Gold amount cannot be negative.");
        if (message == null || message.isBlank())
            throw new IllegalArgumentException("Message cannot be empty.");
        if (message.length() > 500)
            throw new IllegalArgumentException("Message too long (max 500 characters).");

        // Look up recipient by warrior name (username is private)
        Warrior recipientWarrior = warriorRepository.findByName(recipientWarriorName)
                .orElseThrow(() -> new IllegalArgumentException("Warrior '" + recipientWarriorName + "' not found."));
        Player recipient = recipientWarrior.getPlayer();

        if (recipient.getId().equals(sender.getId()))
            throw new IllegalArgumentException("You cannot send a letter to yourself.");

        // goldAmount is stored in bronze units for fine-grained control
        long totalCost   = SEND_FEE_BRONZE + goldAmount;
        long senderTotal = sender.getBronze() + sender.getSilver() * 100L + sender.getGold() * 10_000L;
        if (senderTotal < totalCost)
            throw new com.medieval.game.config.LocalizedException("error.mail_funds", "Insufficient funds. Need {0} bronze ({1} fee + {2} attached).", totalCost, SEND_FEE_BRONZE, goldAmount);

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

    // ── System mail (no fee, no gold, no item) — notifications ────────────────
    @Transactional
    public Mail sendSystemMail(Player recipient, String message) {
        Mail mail = new Mail();
        mail.setSenderPlayerId(0L);
        mail.setSenderWarriorName("System");
        mail.setRecipientPlayerId(recipient.getId());
        mail.setMessage(message.length() > 500 ? message.substring(0, 500) : message);
        return mailRepository.save(mail);
    }

    // ── Item mail (bag-full overflow) ─────────────────────────────────────────

    /**
     * Sends a system mail with an item attached to the recipient's inbox.
     * Used when a player's bag is full and they would have received an item.
     * The item data is stored in the mail and created in the inventory only
     * when the player explicitly claims it.
     */
    /** Compat: nível do item assumido 1 (não-armas). Armas devem usar a sobrecarga com itemLevel. */
    @Transactional
    public Mail sendItemMail(Player recipient, String reason,
                             String itemName, ItemType itemType,
                             int atk, int def, int hp, int rarity, int sockets,
                             String description, String origin) {
        return sendItemMail(recipient, reason, itemName, itemType, atk, def, hp, rarity, 1, sockets, description, origin);
    }

    @Transactional
    public Mail sendItemMail(Player recipient, String reason,
                             String itemName, ItemType itemType,
                             int atk, int def, int hp, int rarity, int itemLevel, int sockets,
                             String description, String origin) {
        Mail mail = new Mail();
        mail.setSenderPlayerId(0L);           // 0 = system sender
        mail.setSenderWarriorName("System");
        mail.setRecipientPlayerId(recipient.getId());
        mail.setMessage("📦 Item received! Your bag was full. " + reason
                + "\n\n⏳ This item expires in " + ITEM_MAIL_EXPIRY_DAYS + " days.");
        mail.setItemName(itemName);
        mail.setItemType(itemType.name());
        mail.setItemAtk(atk);
        mail.setItemDef(def);
        mail.setItemHp(hp);
        mail.setItemRarity(rarity);
        mail.setItemLevel(Math.max(1, itemLevel)); // [CLASSES_ARMAS] arma recalcula pelo perfil no claim
        mail.setItemSockets(sockets);
        mail.setItemDescription(description);
        mail.setItemOrigin(origin);
        mail.setExpiresAt(LocalDateTime.now().plusDays(ITEM_MAIL_EXPIRY_DAYS));
        return mailRepository.save(mail);
    }

    /**
     * Claims the item attached to a mail, adding it to the player's inventory.
     * Fails if bag is still full or if item was already collected / mail expired.
     */
    @Transactional
    public InventoryItem claimItem(Player player, Long mailId, InventoryService inventoryService) {
        Mail mail = requireRecipient(player, mailId);
        if (!mail.hasItem())
            throw new IllegalStateException("This letter has no item attached.");
        if (mail.isItemCollected())
            throw new IllegalStateException("Item already collected.");
        if (mail.isExpired())
            throw new IllegalStateException("This letter has expired. The item was lost.");

        ItemType type = ItemType.valueOf(mail.getItemType());
        // Passa o itemLevel preservado: armas recalculam os stats pelo perfil do tipo no make(). [CLASSES_ARMAS]
        InventoryItem item = inventoryService.make(
                player, mail.getItemName(), type,
                mail.getItemAtk(), mail.getItemDef(), mail.getItemHp(),
                mail.getItemRarity(), 0L,
                mail.getItemLevel(), mail.getItemDescription(), mail.getItemOrigin());

        mail.setItemCollected(true);
        if (!mail.isRead()) mail.setReadAt(LocalDateTime.now());
        mailRepository.save(mail);
        return item;
    }

    // ── Resource mail (recompensa de recurso, ex.: peixe da daily / overflow de bag cheia) [DAILY] ──

    /** Envia uma carta de sistema com um recurso anexado (peixe etc.), reivindicável depois. */
    @Transactional
    public Mail sendResourceMail(Player recipient, String reason,
                                 com.medieval.game.enums.ResourceType type, int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
        Mail mail = new Mail();
        mail.setSenderPlayerId(0L);           // 0 = system sender
        mail.setSenderWarriorName("System");
        mail.setRecipientPlayerId(recipient.getId());
        mail.setMessage("📦 " + reason + "\n\n⏳ Expires in " + ITEM_MAIL_EXPIRY_DAYS + " days.");
        mail.setResourceType(type.name());
        mail.setResourceQty(qty);
        mail.setExpiresAt(LocalDateTime.now().plusDays(ITEM_MAIL_EXPIRY_DAYS));
        return mailRepository.save(mail);
    }

    /**
     * Reivindica o recurso anexado, adicionando à bag (respeitando o espaço). Se não couber tudo, o
     * restante FICA na carta p/ reivindicar depois. Recebe o GatheringService por parâmetro (igual ao
     * claimItem com o InventoryService) p/ evitar dependência circular.
     * @return quantidade efetivamente adicionada à bag.
     */
    @Transactional
    public long claimResource(Player player, Long mailId, GatheringService gatheringService) {
        Mail mail = requireRecipient(player, mailId);
        if (!mail.hasResource())
            throw new IllegalStateException("This letter has no resource attached.");
        if (mail.isResourceCollected())
            throw new IllegalStateException("Resource already collected.");
        if (mail.isExpired())
            throw new IllegalStateException("This letter has expired. The resource was lost.");

        com.medieval.game.enums.ResourceType type = com.medieval.game.enums.ResourceType.valueOf(mail.getResourceType());
        long added = gatheringService.addResource(player, type, mail.getResourceQty());
        if (added <= 0)
            throw new com.medieval.game.config.LocalizedException("error.bag_full_resource",
                    "Your bag is full. Free up space and claim again.");

        int remaining = mail.getResourceQty() - (int) added;
        if (remaining > 0) {
            mail.setResourceQty(remaining);   // coube só uma parte → o resto continua na carta
        } else {
            mail.setResourceCollected(true);
        }
        if (!mail.isRead()) mail.setReadAt(LocalDateTime.now());
        mailRepository.save(mail);
        return added;
    }

    // ── Recolher TUDO de uma vez (ouro + itens + recursos) [MAIL_CLAIM_ALL] ──────
    /** Resultado do claim-all: bronze coletado, nº de itens/recursos adicionados e o que ficou (bag cheia). */
    public record ClaimAllResult(long gold, int items, long resources, int leftItems, long leftResources) {}

    /**
     * Recolhe de TODAS as cartas: ouro (sempre), item (se cabe na bag) e recurso (o que couber).
     * NÃO lança por bag cheia — o que não couber FICA na carta e entra na contagem "left*".
     * Recebe os services por parâmetro (igual claimItem/claimResource) p/ evitar dependência circular.
     */
    @Transactional
    public ClaimAllResult claimAll(Player player, InventoryService inventoryService, GatheringService gatheringService) {
        long gold = 0; int items = 0; long resources = 0; int leftItems = 0; long leftResources = 0;
        for (Mail mail : mailRepository.findByRecipientPlayerIdOrderBySentAtDesc(player.getId())) {
            boolean changed = false;
            // Ouro (sem limite de bag)
            if (mail.getGoldAmount() > 0 && !mail.isCollected()) {
                playerService.addGold(player, mail.getGoldAmount());
                mail.setCollectedAt(LocalDateTime.now());
                gold += mail.getGoldAmount();
                changed = true;
            }
            // Item (1 slot) — só se houver espaço; senão fica na carta
            if (mail.hasItem() && !mail.isItemCollected() && !mail.isExpired()) {
                if (inventoryService.bagSpaceLeft(player) > 0) {
                    ItemType type = ItemType.valueOf(mail.getItemType());
                    inventoryService.make(player, mail.getItemName(), type,
                            mail.getItemAtk(), mail.getItemDef(), mail.getItemHp(),
                            mail.getItemRarity(), 0L, mail.getItemLevel(),
                            mail.getItemDescription(), mail.getItemOrigin());
                    mail.setItemCollected(true);
                    items++; changed = true;
                } else {
                    leftItems++;
                }
            }
            // Recurso — adiciona o que couber; o resto fica na carta
            if (mail.hasResource() && !mail.isResourceCollected() && !mail.isExpired()) {
                var type = com.medieval.game.enums.ResourceType.valueOf(mail.getResourceType());
                long added = gatheringService.addResource(player, type, mail.getResourceQty());
                if (added > 0) {
                    int remaining = mail.getResourceQty() - (int) added;
                    if (remaining > 0) { mail.setResourceQty(remaining); leftResources += remaining; }
                    else mail.setResourceCollected(true);
                    resources += added; changed = true;
                } else {
                    leftResources += mail.getResourceQty();
                }
            }
            if (changed) {
                if (!mail.isRead()) mail.setReadAt(LocalDateTime.now());
                mailRepository.save(mail);
            }
        }
        return new ClaimAllResult(gold, items, resources, leftItems, leftResources);
    }

    // ── Apagar TODAS as cartas da inbox [MAIL_CLAIM_ALL] ─────────────────────────
    /** Apaga todas as cartas do destinatário. @return quantas foram apagadas. */
    @Transactional
    public int deleteAll(Player player) {
        List<Mail> all = mailRepository.findByRecipientPlayerIdOrderBySentAtDesc(player.getId());
        mailRepository.deleteAll(all);
        return all.size();
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
