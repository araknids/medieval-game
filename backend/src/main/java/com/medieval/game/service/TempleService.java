package com.medieval.game.service;

import com.medieval.game.enums.BuffType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TempleService {

    private static final long HEAL_COST_BRONZE      = 100; // 1 prata (grátis ≤ lv 10)
    private static final long PROTECT_COST          = 50;  // bronze por item
    private static final int  MAX_PROTECTED         = 3;
    private static final long BUFF_DURATION_MIN     = 60;  // 1 hora
    private static final int  SOULSTONE_HEAL_COST   = 1;   // SoulStones por cura instantânea
    private static final int  SOULSTONE_HEAL_CD_MIN = 30;  // minutos de cooldown

    private final WarriorRepository       warriorRepository;
    private final InventoryItemRepository inventoryRepository;
    private final PlayerRepository        playerRepository;
    private final PlayerService           playerService;

    // ── Curar guerreiro ──

    /** Retorna o custo de cura (0 se grátis) */
    public long healCost(Warrior warrior) {
        return warrior.getLevel() <= 10 ? 0 : HEAL_COST_BRONZE;
    }

    @Transactional
    public void heal(Player player) {
        log.info("[TempleService] player={} action=heal", player.getId());
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.getCalculatedHpPercent() >= 100) {
            log.warn("[TempleService] player={} REJECTED: warrior already has full HP", player.getId());
            throw new IllegalStateException("Your warrior already has full HP!");
        }

        long cost = healCost(warrior);
        if (cost > 0) playerService.spendBronze(player, cost);

        warrior.healFull();
        warriorRepository.save(warrior);
        log.info("[TempleService] player={} action=heal OK cost={}", player.getId(), cost);
    }

    // ── Cura instantânea via SoulStone ──

    /** Segundos restantes no CD da cura por SoulStone (0 = pronto) */
    public long soulstoneHealCooldownSecs(Player player) {
        if (player.getLastSoulstoneHealAt() == null) return 0;
        long elapsed = Duration.between(player.getLastSoulstoneHealAt(), LocalDateTime.now()).toSeconds();
        long cd = SOULSTONE_HEAL_CD_MIN * 60L;
        return Math.max(0, cd - elapsed);
    }

    @Transactional
    public void soulstoneHeal(Player player) {
        log.info("[TempleService] player={} action=soulstoneHeal", player.getId());

        long cdSecs = soulstoneHealCooldownSecs(player);
        if (cdSecs > 0) {
            log.warn("[TempleService] player={} REJECTED: soulstoneHeal on cooldown {}s", player.getId(), cdSecs);
            throw new IllegalStateException("Instant heal on cooldown. Wait " + (cdSecs / 60) + "m " + (cdSecs % 60) + "s.");
        }
        if (player.getSoulStones() < SOULSTONE_HEAL_COST) {
            log.warn("[TempleService] player={} REJECTED: not enough SoulStones ({}<{})", player.getId(), player.getSoulStones(), SOULSTONE_HEAL_COST);
            throw new IllegalStateException("Not enough SoulStones. Required: " + SOULSTONE_HEAL_COST);
        }

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.getCalculatedHpPercent() >= 100) {
            log.warn("[TempleService] player={} REJECTED: warrior already at full HP", player.getId());
            throw new IllegalStateException("Your warrior already has full HP!");
        }

        player.setSoulStones(player.getSoulStones() - SOULSTONE_HEAL_COST);
        player.setLastSoulstoneHealAt(LocalDateTime.now());
        playerRepository.save(player);

        warrior.healFull();
        warriorRepository.save(warrior);
        log.info("[TempleService] player={} action=soulstoneHeal OK stones_remaining={}", player.getId(), player.getSoulStones());
    }

    // ── Buff / Bênção ──

    @Transactional
    public void applyBuff(Player player, BuffType buffType) {
        log.info("[TempleService] player={} action=applyBuff buffType={}", player.getId(), buffType);
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        playerService.spendBronze(player, buffType.bronzeCost);

        warrior.setActiveBuff(buffType);
        warrior.setBuffExpiresAt(LocalDateTime.now().plusMinutes(BUFF_DURATION_MIN));
        warriorRepository.save(warrior);
        log.info("[TempleService] player={} action=applyBuff OK buffType={} cost={}", player.getId(), buffType, buffType.bronzeCost);
    }

    // ── Proteger item ──

    public long countProtected(Player player) {
        return inventoryRepository.findAllByPlayer(player).stream()
                .filter(InventoryItem::isGuarded).count();
    }

    @Transactional
    public void protectItem(Player player, Long itemId) {
        log.info("[TempleService] player={} action=protect itemId={}", player.getId(), itemId);
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId())) {
            log.warn("[TempleService] player={} REJECTED: item {} does not belong to this player", player.getId(), itemId);
            throw new IllegalStateException("Item does not belong to you");
        }
        if (item.isGuarded()) {
            log.warn("[TempleService] player={} REJECTED: item {} is already protected", player.getId(), itemId);
            throw new IllegalStateException("Item is already protected");
        }
        if (countProtected(player) >= MAX_PROTECTED) {
            log.warn("[TempleService] player={} REJECTED: max {} protected items reached", player.getId(), MAX_PROTECTED);
            throw new IllegalStateException("Maximum of " + MAX_PROTECTED + " protected items reached");
        }

        playerService.spendBronze(player, PROTECT_COST);
        item.setGuarded(true);
        inventoryRepository.save(item);
        log.info("[TempleService] player={} action=protect OK itemId={} name={}", player.getId(), itemId, item.getName());
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
