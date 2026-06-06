package com.medieval.game.service;

import com.medieval.game.enums.BuffType;
import com.medieval.game.enums.Element;
import com.medieval.game.enums.ResourceType;
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

    private static final long PROTECT_COST          = 50;
    private static final int  MAX_PROTECTED         = 3;
    private static final long BUFF_DURATION_MIN     = 60;
    private static final int  SS_HEAL_COST          = 1;   // SoulStones
    private static final int  SS_HEAL_CD_MINUTES    = 30;

    private final WarriorRepository       warriorRepository;
    private final InventoryItemRepository inventoryRepository;
    private final PlayerRepository        playerRepository;
    private final PlayerService           playerService;
    private final VipService              vipService;
    private final GatheringService        gatheringService; // consumo de essência no encantamento [ELEMENTOS]

    // ── Encantamento elemental (buff temporário 1h) [ELEMENTOS] ──
    private static final long ENCHANT_BRONZE_COST = 100;
    private static final int  ENCHANT_DURATION_MIN = 60;

    // ── Curar guerreiro ──

    /** Retorna o custo de cura: grátis até o nível 10, depois escala (nível × 10 bronze). */
    public long healCost(Warrior warrior) {
        return warrior.getLevel() <= 10 ? 0 : (long) warrior.getLevel() * 10;
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

    public long soulstoneHealCooldownSecs(Player player) {
        if (player.getLastSoulstoneHealAt() == null) return 0;
        long elapsed = Duration.between(player.getLastSoulstoneHealAt(), LocalDateTime.now()).toSeconds();
        return Math.max(0, SS_HEAL_CD_MINUTES * 60L - elapsed);
    }

    @Transactional
    public void soulstoneHeal(Player player) {
        log.info("[TempleService] player={} action=soulstoneHeal", player.getId());

        long cdSecs = soulstoneHealCooldownSecs(player);
        if (cdSecs > 0) {
            log.warn("[TempleService] player={} REJECTED: soulstoneHeal on cooldown {}s", player.getId(), cdSecs);
            throw new IllegalStateException("Instant heal on cooldown. Wait " + (cdSecs / 60) + "m " + (cdSecs % 60) + "s.");
        }
        if (player.getSoulStones() < SS_HEAL_COST) {
            log.warn("[TempleService] player={} REJECTED: not enough SoulStones ({}<{})", player.getId(), player.getSoulStones(), SS_HEAL_COST);
            throw new IllegalStateException("Not enough SoulStones. Required: " + SS_HEAL_COST);
        }

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        if (warrior.getCalculatedHpPercent() >= 100) {
            log.warn("[TempleService] player={} REJECTED: warrior already at full HP", player.getId());
            throw new IllegalStateException("Your warrior already has full HP!");
        }

        player.setSoulStones(player.getSoulStones() - SS_HEAL_COST);
        player.setLastSoulstoneHealAt(LocalDateTime.now());
        playerRepository.save(player);

        warrior.healFull();
        warriorRepository.save(warrior);
        log.info("[TempleService] player={} action=soulstoneHeal OK stonesLeft={}", player.getId(), player.getSoulStones());
    }

    // ── VIP Heal (grátis, CD 10 min) ──

    public long vipHealCooldownSecs(Player player) {
        return vipService.vipHealCooldownSecs(player);
    }

    @Transactional
    public void vipHeal(Player player) {
        log.info("[TempleService] player={} action=vipHeal", player.getId());
        if (!player.isVip()) {
            log.warn("[TempleService] player={} REJECTED: vipHeal requires VIP", player.getId());
            throw new IllegalStateException("VIP required for free healing.");
        }
        long cdSecs = vipService.vipHealCooldownSecs(player);
        if (cdSecs > 0) {
            log.warn("[TempleService] player={} REJECTED: vipHeal on cooldown {}s", player.getId(), cdSecs);
            throw new IllegalStateException("VIP heal on cooldown. Wait " + (cdSecs / 60) + "m " + (cdSecs % 60) + "s.");
        }
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        if (warrior.getCalculatedHpPercent() >= 100) {
            log.warn("[TempleService] player={} REJECTED: warrior already at full HP", player.getId());
            throw new IllegalStateException("Your warrior already has full HP!");
        }
        player.setLastVipHealAt(LocalDateTime.now());
        playerRepository.save(player);
        warrior.healFull();
        warriorRepository.save(warrior);
        log.info("[TempleService] player={} action=vipHeal OK", player.getId());
    }

    // ── Buff / Bênção ──

    @Transactional
    public void applyBuff(Player player, BuffType buffType) {
        log.info("[TempleService] player={} action=applyBuff buffType={}", player.getId(), buffType);
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        playerService.spendBronze(player, buffType.bronzeCost);

        // VIP: se o slot 1 já está ocupado por buff diferente, usa slot 2
        if (warrior.hasActiveBuff() && warrior.getActiveBuff() != buffType && player.isVip()) {
            if (warrior.hasActiveBuff2() && warrior.getActiveBuff2() == buffType) {
                log.warn("[TempleService] player={} REJECTED: buff {} already in slot 2", player.getId(), buffType);
                throw new IllegalStateException("This buff is already active in slot 2.");
            }
            warrior.setActiveBuff2(buffType);
            warrior.setBuffExpiresAt2(LocalDateTime.now().plusMinutes(BUFF_DURATION_MIN));
            log.info("[TempleService] player={} action=applyBuff OK (slot2) buffType={}", player.getId(), buffType);
        } else if (warrior.hasActiveBuff() && warrior.getActiveBuff() == buffType) {
            // Refresh do mesmo buff → renova o slot 1
            warrior.setBuffExpiresAt(LocalDateTime.now().plusMinutes(BUFF_DURATION_MIN));
            log.info("[TempleService] player={} action=applyBuff OK (refresh slot1) buffType={}", player.getId(), buffType);
        } else if (!warrior.hasActiveBuff()) {
            warrior.setActiveBuff(buffType);
            warrior.setBuffExpiresAt(LocalDateTime.now().plusMinutes(BUFF_DURATION_MIN));
            log.info("[TempleService] player={} action=applyBuff OK (slot1) buffType={}", player.getId(), buffType);
        } else {
            // Free player, slot 1 ocupado por buff diferente → substitui
            warrior.setActiveBuff(buffType);
            warrior.setBuffExpiresAt(LocalDateTime.now().plusMinutes(BUFF_DURATION_MIN));
            log.info("[TempleService] player={} action=applyBuff OK (replace slot1) buffType={}", player.getId(), buffType);
        }
        warriorRepository.save(warrior);
        log.info("[TempleService] player={} action=applyBuff cost={}", player.getId(), buffType.bronzeCost);
    }

    // ── Encantamento elemental (arma/armadura, buff 1h) [ELEMENTOS] ──

    @Transactional
    public void enchantWeapon(Player player, Element element) { applyEnchant(player, element, true); }

    @Transactional
    public void enchantArmor(Player player, Element element) { applyEnchant(player, element, false); }

    private void applyEnchant(Player player, Element element, boolean weapon) {
        log.info("[TempleService] player={} action=enchant {} element={}", player.getId(), weapon ? "weapon" : "armor", element);
        if (element == null) throw new IllegalArgumentException("Choose an element.");
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        ResourceType essence = element.essence();
        if (gatheringService.resourceQuantity(player, essence) < 1) {
            log.warn("[TempleService] player={} REJECTED: no {} to enchant", player.getId(), essence);
            throw new IllegalStateException("Not enough " + essence.displayName + ". Farm the "
                    + element.displayName + " area to gather it.");
        }

        playerService.spendBronze(player, ENCHANT_BRONZE_COST);   // lança se não tiver saldo (rollback)
        gatheringService.removeResource(player, essence, 1);

        LocalDateTime until = LocalDateTime.now().plusMinutes(ENCHANT_DURATION_MIN);
        if (weapon) { warrior.setWeaponElement(element); warrior.setWeaponElementUntil(until); }
        else        { warrior.setArmorElement(element);  warrior.setArmorElementUntil(until); }
        warriorRepository.save(warrior);
        log.info("[TempleService] player={} action=enchant OK {} element={} until={}", player.getId(), weapon ? "weapon" : "armor", element, until);
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
        if (item.isPvpLocked() && player.isPvpFlagged()) {
            log.warn("[TempleService] player={} REJECTED: item {} is PvP-locked (exposed)", player.getId(), itemId);
            throw new IllegalStateException("Item exposto no PvP — não pode proteger no Templo enquanto flagged.");
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
