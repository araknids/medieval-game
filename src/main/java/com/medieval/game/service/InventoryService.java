package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryItemRepository inventoryRepository;
    private final PlayerRepository        playerRepository;

    public List<InventoryItem> getInventory(Player player) {
        return inventoryRepository.findAllByPlayer(player);
    }

    @Transactional
    public InventoryItem equip(Player player, Long itemId) {
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item não encontrado"));

        if (!item.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("Este item não é seu");
        }
        if (item.isEquipped()) {
            throw new IllegalStateException("Item já equipado");
        }

        // Desequipa o item atual do mesmo slot, se houver
        inventoryRepository.findByPlayerAndTypeAndEquippedTrue(player, item.getType())
                .ifPresent(current -> {
                    current.setEquipped(false);
                    inventoryRepository.save(current);
                });

        item.setEquipped(true);
        return inventoryRepository.save(item);
    }

    @Transactional
    public InventoryItem unequip(Player player, Long itemId) {
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item não encontrado"));

        if (!item.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("Este item não é seu");
        }
        if (!item.isEquipped()) {
            throw new IllegalStateException("Item não está equipado");
        }

        item.setEquipped(false);
        return inventoryRepository.save(item);
    }

    @Transactional
    public InventoryItem sell(Player player, Long itemId) {
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item não encontrado"));
        if (!item.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("Este item não é seu");
        }
        if (item.isEquipped()) {
            throw new IllegalStateException("Desequipe o item antes de vender");
        }
        player.setGold(player.getGold() + item.getSellPrice());
        playerRepository.save(player);
        inventoryRepository.delete(item);
        return item;
    }

    @Transactional
    public void giveStarterItems(Player player) {
        make(player, "Elmo de Ferro",       ItemType.HELMET,   0, 2, 10, 1, 25);
        make(player, "Armadura de Couro",   ItemType.ARMOR,    0, 3, 15, 1, 25);
        make(player, "Espada de Ferro",     ItemType.WEAPON,   4, 0,  0, 1, 25);
        make(player, "Escudo de Madeira",   ItemType.SHIELD,   0, 3,  0, 1, 25);
        make(player, "Botas de Couro",      ItemType.BOOTS,    0, 1,  5, 1, 25);
        make(player, "Luvas de Couro",      ItemType.GLOVES,   1, 1,  0, 1, 25);
        make(player, "Calça de Couro",      ItemType.PANTS,    0, 2,  8, 1, 25);
    }

    @Transactional
    public InventoryItem make(Player player, String name, ItemType type,
                              int atk, int def, int hp, int rarity, long sellPrice) {
        InventoryItem item = new InventoryItem();
        item.setPlayer(player);
        item.setName(name);
        item.setType(type);
        item.setAttackBonus(atk);
        item.setDefenseBonus(def);
        item.setHealthBonus(hp);
        item.setRarity(rarity);
        item.setSellPrice(sellPrice);
        return inventoryRepository.save(item);
    }
}
