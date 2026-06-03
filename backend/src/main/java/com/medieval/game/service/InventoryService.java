package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final int SS_EXPAND_COST = 3; // SoulStones para expandir bag

    private final InventoryItemRepository inventoryRepository;
    private final PlayerRepository        playerRepository;
    private final ItemLoreGenerator       loreGenerator;

    public List<InventoryItem> getInventory(Player player) {
        return inventoryRepository.findAllByPlayer(player);
    }

    public int bagSize(Player player) {
        return (int) inventoryRepository.findAllByPlayer(player).stream()
                .filter(i -> !i.isEquipped()).count();
    }

    @Transactional
    public void expandInventory(Player player) {
        log.info("[InventoryService] player={} action=expandInventory", player.getId());
        if (player.isInventoryExpanded()) {
            log.warn("[InventoryService] player={} REJECTED: already expanded", player.getId());
            throw new IllegalStateException("Inventory already expanded to 20 slots.");
        }
        if (player.getSoulStones() < SS_EXPAND_COST) {
            log.warn("[InventoryService] player={} REJECTED: not enough SoulStones ({}<{})", player.getId(), player.getSoulStones(), SS_EXPAND_COST);
            throw new IllegalStateException("Not enough SoulStones. Required: " + SS_EXPAND_COST);
        }
        player.setSoulStones(player.getSoulStones() - SS_EXPAND_COST);
        player.setInventoryExpanded(true);
        playerRepository.save(player);
        log.info("[InventoryService] player={} action=expandInventory OK stonesLeft={}", player.getId(), player.getSoulStones());
    }

    @Transactional
    public InventoryItem equip(Player player, Long itemId) {
        log.info("[InventoryService] player={} action=equip itemId={}", player.getId(), itemId);
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        if (!item.getPlayer().getId().equals(player.getId())) {
            log.warn("[InventoryService] player={} REJECTED: item {} does not belong to this player", player.getId(), itemId);
            throw new IllegalStateException("This item does not belong to you");
        }
        if (item.isEquipped()) {
            log.warn("[InventoryService] player={} REJECTED: item {} already equipped", player.getId(), itemId);
            throw new IllegalStateException("Item already equipped");
        }

        // Desequipa o item atual do mesmo slot, se houver
        inventoryRepository.findByPlayerAndTypeAndEquippedTrue(player, item.getType())
                .ifPresent(current -> {
                    current.setEquipped(false);
                    inventoryRepository.save(current);
                });

        item.setEquipped(true);
        InventoryItem saved = inventoryRepository.save(item);
        log.info("[InventoryService] player={} action=equip OK itemId={} name={}", player.getId(), itemId, item.getName());
        return saved;
    }

    @Transactional
    public InventoryItem unequip(Player player, Long itemId) {
        log.info("[InventoryService] player={} action=unequip itemId={}", player.getId(), itemId);
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));

        if (!item.getPlayer().getId().equals(player.getId())) {
            log.warn("[InventoryService] player={} REJECTED: item {} does not belong to this player", player.getId(), itemId);
            throw new IllegalStateException("This item does not belong to you");
        }
        if (!item.isEquipped()) {
            log.warn("[InventoryService] player={} REJECTED: item {} is not equipped", player.getId(), itemId);
            throw new IllegalStateException("Item is not equipped");
        }

        item.setEquipped(false);
        InventoryItem saved = inventoryRepository.save(item);
        log.info("[InventoryService] player={} action=unequip OK itemId={}", player.getId(), itemId);
        return saved;
    }

    @Transactional
    public InventoryItem sell(Player player, Long itemId) {
        log.info("[InventoryService] player={} action=sell itemId={}", player.getId(), itemId);
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId())) {
            log.warn("[InventoryService] player={} REJECTED: item {} does not belong to this player", player.getId(), itemId);
            throw new IllegalStateException("This item does not belong to you");
        }
        if (item.isEquipped()) {
            log.warn("[InventoryService] player={} REJECTED: item {} is equipped, unequip first", player.getId(), itemId);
            throw new IllegalStateException("Desequipe o item antes de vender");
        }
        player.addBronzeAmount(item.getSellPrice()); // sell price é em bronze
        playerRepository.save(player);
        inventoryRepository.delete(item);
        log.info("[InventoryService] player={} action=sell OK itemId={} name={} bronze={}", player.getId(), itemId, item.getName(), item.getSellPrice());
        return item;
    }

    @Transactional
    public void giveStarterItems(Player player) {
        String origin = loreGenerator.originStarter();
        java.util.Random rng = new java.util.Random();
        make(player, "Elmo de Ferro",     ItemType.HELMET, 0, 2, 10, 1, 20, loreGenerator.generateLore(1, ItemType.HELMET, rng), origin);
        make(player, "Armadura de Couro", ItemType.ARMOR,  0, 3, 15, 1, 20, loreGenerator.generateLore(1, ItemType.ARMOR,  rng), origin);
        make(player, "Espada de Ferro",   ItemType.WEAPON, 4, 0,  0, 1, 20, loreGenerator.generateLore(1, ItemType.WEAPON, rng), origin);
        make(player, "Escudo de Madeira", ItemType.SHIELD, 0, 3,  0, 1, 20, loreGenerator.generateLore(1, ItemType.SHIELD, rng), origin);
        make(player, "Botas de Couro",    ItemType.BOOTS,  0, 1,  5, 1, 20, loreGenerator.generateLore(1, ItemType.BOOTS,  rng), origin);
        make(player, "Luvas de Couro",    ItemType.GLOVES, 1, 1,  0, 1, 20, loreGenerator.generateLore(1, ItemType.GLOVES, rng), origin);
        make(player, "Calça de Couro",    ItemType.PANTS,  0, 2,  8, 1, 20, loreGenerator.generateLore(1, ItemType.PANTS,  rng), origin);
    }

    @Transactional
    public InventoryItem make(Player player, String name, ItemType type,
                              int atk, int def, int hp, int rarity, long sellPrice) {
        return make(player, name, type, atk, def, hp, rarity, sellPrice, null, null);
    }

    @Transactional
    public InventoryItem make(Player player, String name, ItemType type,
                              int atk, int def, int hp, int rarity, long sellPrice,
                              String description, String origin) {
        int max = player.getMaxInventorySlots();
        if (bagSize(player) >= max) {
            log.warn("[InventoryService] player={} bag full ({}/{}) — item '{}' not added", player.getId(), bagSize(player), max, name);
            throw new IllegalStateException("Inventory full (" + max + " slots). Sell items or expand with SoulStones.");
        }
        InventoryItem item = new InventoryItem();
        item.setPlayer(player);
        item.setName(name);
        item.setType(type);
        item.setAttackBonus(atk);
        item.setDefenseBonus(def);
        item.setHealthBonus(hp);
        item.setRarity(rarity);
        item.setSellPrice(sellPrice);
        item.setDescription(description);
        item.setOrigin(origin);
        return inventoryRepository.save(item);
    }
}
