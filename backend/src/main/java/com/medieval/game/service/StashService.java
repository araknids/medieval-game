package com.medieval.game.service;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.ResourceInventory;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.ResourceInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stash (Inventário V2): armazenamento extra ILIMITADO, fora da bag. Cobra uma taxa fixa de
 * bronze por operação (depositar ou retirar). Itens e recursos guardados ficam com stashed=true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StashService {

    public static final long STASH_FEE = 50;   // bronze por operação (depositar/retirar). Stash sem limite de slots.

    private final InventoryItemRepository     inventoryRepository;
    private final ResourceInventoryRepository resourceRepository;
    private final PlayerService               playerService;
    private final InventoryService            inventoryService;

    // ── Contagem ──
    public int stashSize(Player player) {
        long items = inventoryRepository.findAllByPlayer(player).stream()
                .filter(InventoryItem::isStashed).count();
        long res = resourceRepository.findAllByPlayerAndStashed(player, true).stream()
                .mapToLong(ResourceInventory::getQuantity).sum();
        return (int) (items + res);
    }

    public List<InventoryItem> stashItems(Player player) {
        return inventoryRepository.findAllByPlayer(player).stream().filter(InventoryItem::isStashed).toList();
    }

    public List<ResourceInventory> stashResources(Player player) {
        return resourceRepository.findAllByPlayerAndStashed(player, true).stream()
                .filter(r -> r.getQuantity() > 0).toList();
    }

    // ── Itens ──
    @Transactional
    public void depositItem(Player player, Long itemId) {
        InventoryItem item = ownedItem(player, itemId);
        if (item.isStashed())  throw new IllegalStateException("Item already in the stash.");
        if (item.isEquipped()) throw new IllegalStateException("Unequip the item before stashing it.");
        if (item.isRunPending()) throw new IllegalStateException("Item is in a Delve run (not yet extracted)."); // [INCURSAO]
        if (item.isPvpLocked() && player.isPvpFlagged())
            throw new IllegalStateException("Item exposto no PvP — não pode guardar no stash enquanto flagged.");
        playerService.spendBronze(player, STASH_FEE);
        item.setStashed(true);
        inventoryRepository.save(item);
        log.info("[StashService] player={} action=depositItem itemId={}", player.getId(), itemId);
    }

    @Transactional
    public void withdrawItem(Player player, Long itemId) {
        InventoryItem item = ownedItem(player, itemId);
        if (!item.isStashed()) throw new IllegalStateException("Item is not in the stash.");
        if (inventoryService.bagSpaceLeft(player) < 1) throw new IllegalStateException("Bag full. Make room first.");
        playerService.spendBronze(player, STASH_FEE);
        item.setStashed(false);
        inventoryRepository.save(item);
        log.info("[StashService] player={} action=withdrawItem itemId={}", player.getId(), itemId);
    }

    // ── Recursos ──
    @Transactional
    public void depositResource(Player player, ResourceType type, long qty) {
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be > 0");
        // [PVP_FLAG] Só a zona VERMELHA arrisca/trava recursos; a amarela não (perde só bronze+XP). [FORTALEZA_ZONAS]
        if (player.isPvpFlagged() && player.getPvpFlaggedZone() == com.medieval.game.enums.Zone.HIGH_RISK)
            throw new IllegalStateException("Recursos expostos no PvP — não pode guardar no stash enquanto flagged na zona vermelha.");
        ResourceInventory bag = resourceRepository.findByPlayerAndResourceTypeAndStashed(player, type, false)
                .orElseThrow(() -> new IllegalStateException("You don't have that resource."));
        if (bag.getQuantity() < qty) throw new IllegalStateException("Insufficient quantity.");
        playerService.spendBronze(player, STASH_FEE);
        bag.setQuantity(bag.getQuantity() - qty);
        resourceRepository.save(bag);
        ResourceInventory st = stashRow(player, type);
        st.setQuantity(st.getQuantity() + qty);
        resourceRepository.save(st);
        log.info("[StashService] player={} action=depositResource type={} qty={}", player.getId(), type, qty);
    }

    @Transactional
    public void withdrawResource(Player player, ResourceType type, long qty) {
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be > 0");
        ResourceInventory st = resourceRepository.findByPlayerAndResourceTypeAndStashed(player, type, true)
                .orElseThrow(() -> new IllegalStateException("That resource isn't in your stash."));
        if (st.getQuantity() < qty) throw new IllegalStateException("Insufficient quantity in stash.");
        if (inventoryService.resourceSpaceLeft(player) < qty) throw new IllegalStateException("Not enough bag space."); // [BAG_WEIGHT] recurso = 0.2 slot
        playerService.spendBronze(player, STASH_FEE);
        st.setQuantity(st.getQuantity() - qty);
        resourceRepository.save(st);
        ResourceInventory bag = bagRow(player, type);
        bag.setQuantity(bag.getQuantity() + qty);
        resourceRepository.save(bag);
        log.info("[StashService] player={} action=withdrawResource type={} qty={}", player.getId(), type, qty);
    }

    // ── Helpers ──
    private InventoryItem ownedItem(Player player, Long itemId) {
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This item does not belong to you");
        return item;
    }

    private ResourceInventory stashRow(Player player, ResourceType type) {
        return resourceRepository.findByPlayerAndResourceTypeAndStashed(player, type, true)
                .orElseGet(() -> newRow(player, type, true));
    }
    private ResourceInventory bagRow(Player player, ResourceType type) {
        return resourceRepository.findByPlayerAndResourceTypeAndStashed(player, type, false)
                .orElseGet(() -> newRow(player, type, false));
    }
    private ResourceInventory newRow(Player player, ResourceType type, boolean stashed) {
        ResourceInventory r = new ResourceInventory();
        r.setPlayer(player); r.setResourceType(type); r.setStashed(stashed);
        return r;
    }
}
