package com.medieval.game.service;

import com.medieval.game.enums.BuffType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TempleService {

    private static final long HEAL_COST_BRONZE  = 100; // 1 prata (grátis ≤ lv 10)
    private static final long PROTECT_COST      = 50;  // bronze por item
    private static final int  MAX_PROTECTED     = 3;
    private static final long BUFF_DURATION_MIN = 60;  // 1 hora

    private final WarriorRepository       warriorRepository;
    private final InventoryItemRepository inventoryRepository;
    private final PlayerService           playerService;

    // ── Curar guerreiro ──

    /** Retorna o custo de cura (0 se grátis) */
    public long healCost(Warrior warrior) {
        return warrior.getLevel() <= 10 ? 0 : HEAL_COST_BRONZE;
    }

    @Transactional
    public void heal(Player player) {
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.getCalculatedHpPercent() >= 100) {
            throw new IllegalStateException("Your warrior already has full HP!");
        }

        long cost = healCost(warrior);
        if (cost > 0) playerService.spendBronze(player, cost);

        warrior.healFull();
        warriorRepository.save(warrior);
    }

    // ── Buff / Bênção ──

    @Transactional
    public void applyBuff(Player player, BuffType buffType) {
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        playerService.spendBronze(player, buffType.bronzeCost);

        warrior.setActiveBuff(buffType);
        warrior.setBuffExpiresAt(LocalDateTime.now().plusMinutes(BUFF_DURATION_MIN));
        warriorRepository.save(warrior);
    }

    // ── Proteger item ──

    public long countProtected(Player player) {
        return inventoryRepository.findAllByPlayer(player).stream()
                .filter(InventoryItem::isGuarded).count();
    }

    @Transactional
    public void protectItem(Player player, Long itemId) {
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Item does not belong to you");
        if (item.isGuarded())
            throw new IllegalStateException("Item is already protected");
        if (countProtected(player) >= MAX_PROTECTED)
            throw new IllegalStateException("Maximum of " + MAX_PROTECTED + " protected items reached");

        playerService.spendBronze(player, PROTECT_COST);
        item.setGuarded(true);
        inventoryRepository.save(item);
    }

    @Transactional
    public void unprotectItem(Player player, Long itemId) {
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Item does not belong to you");

        item.setGuarded(false);
        inventoryRepository.save(item);
    }

    // ── Info dos buffs disponíveis ──

    public List<BuffType> getAllBuffs() {
        return Arrays.asList(BuffType.values());
    }
}
