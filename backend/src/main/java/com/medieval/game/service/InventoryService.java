package com.medieval.game.service;

import com.medieval.game.enums.Affix;
import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.ItemAffix;
import com.medieval.game.model.Player;
import com.medieval.game.model.ResourceInventory;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.ItemAffixRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.ResourceInventoryRepository;
import com.medieval.game.repository.SocketedGemRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final int SS_EXPAND_COST = 3; // SoulStones para expandir bag

    private final InventoryItemRepository inventoryRepository;
    private final PlayerRepository        playerRepository;
    private final ItemLoreGenerator       loreGenerator;
    private final ItemAffixRepository     affixRepository;
    private final SocketedGemRepository   gemRepository;
    private final ResourceInventoryRepository resourceRepository;
    private final WarriorRepository       warriorRepository;

    private static final java.util.Random RNG = new java.util.Random();

    @Transactional
    public List<InventoryItem> getInventory(Player player) {
        List<InventoryItem> items = inventoryRepository.findAllByPlayer(player);
        // [PVP_FLAG] Flag expirou → destrava os itens travados (lazy, sem scheduler).
        if (!player.isPvpFlagged()) {
            boolean any = false;
            for (InventoryItem i : items) if (i.isPvpLocked()) { i.setPvpLocked(false); any = true; }
            if (any) inventoryRepository.saveAll(items);
        }
        return items.stream().filter(i -> !i.isListed()).toList(); // [LEILAO] itens no leilão não aparecem na bag
    }

    /**
     * Desgasta os itens equipados do jogador após uma batalha.
     * Cada item tem 70% de chance de perder 1 ponto de durabilidade por batalha (desgaste lento;
     * 30% das vezes não perde nada). Itens já quebrados (durability 0) permanecem em 0.
     * Retorna nº de itens equipados verificados.
     */
    @Transactional
    public int wearEquippedItems(Player player) {
        List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player)
                .stream().filter(InventoryItem::isEquipped).toList();
        for (InventoryItem item : equipped) {
            if (item.getDurability() <= 0) continue;
            if (RNG.nextInt(100) < 70) { // 70% de chance de perder 1% por batalha
                item.setDurability(Math.max(0, item.getDurability() - 1));
                inventoryRepository.save(item);
            }
        }
        if (!equipped.isEmpty()) {
            log.info("[InventoryService] player={} action=wearEquipped items={}", player.getId(), equipped.size());
        }
        return equipped.size();
    }

    // Inventário V2: bag unificada — conta itens não-equipados (na bag) + recursos na bag POR UNIDADE.
    public int bagSize(Player player) {
        long items = inventoryRepository.findAllByPlayer(player).stream()
                .filter(i -> !i.isEquipped() && !i.isStashed() && !i.isListed()).count(); // [LEILAO] listado não conta na bag
        long resources = resourceRepository.findAllByPlayerAndStashed(player, false).stream()
                .mapToLong(ResourceInventory::getQuantity).sum();
        return (int) (items + resources);
    }

    /** Slots livres na bag (nunca negativo). */
    public int bagSpaceLeft(Player player) {
        return Math.max(0, player.getMaxInventorySlots() - bagSize(player));
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
        if (item.isStashed()) {
            throw new IllegalStateException("Withdraw the item from the stash first.");
        }
        if (item.isListed()) {
            throw new IllegalStateException("Item is listed in the Auction House.");
        }
        // Itens V3: requisito de nível — só equipa se itemLevel ≤ nível do guerreiro. [ITENS_V3]
        int level = warriorRepository.findByPlayer(player).map(Warrior::getLevel).orElse(1);
        if (item.getItemLevel() > level) {
            log.warn("[InventoryService] player={} REJECTED: item {} requires level {} (have {})", player.getId(), itemId, item.getItemLevel(), level);
            throw new IllegalStateException("Requires level " + item.getItemLevel() + " to equip.");
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
        if (item.isListed()) {
            throw new IllegalStateException("Item is listed in the Auction House.");
        }
        if (item.isPvpLocked() && player.isPvpFlagged()) {
            log.warn("[InventoryService] player={} REJECTED: item {} is PvP-locked (exposed)", player.getId(), itemId);
            throw new IllegalStateException("Item exposto no PvP — não pode vender enquanto você está flagged.");
        }
        // Preço efetivo escala com a durabilidade (piso 30%) — evita "lavar" o desgaste
        // vendendo um item surrado pelo preço cheio em vez de reparar. [AUDITORIA M1]
        long effectivePrice = Math.round(item.getSellPrice() * Math.max(0.30, item.getDurability() / 100.0));
        player.addBronzeAmount(effectivePrice); // sell price é em bronze
        playerRepository.save(player);
        gemRepository.deleteAllByItem(item);    // limpa joias (FK) antes de remover o item
        affixRepository.deleteByItem(item);     // limpa afixos (FK) — Itens V2
        inventoryRepository.delete(item);
        item.setSellPrice(effectivePrice); // reflete na resposta o valor efetivamente recebido
        log.info("[InventoryService] player={} action=sell OK itemId={} name={} bronze={}", player.getId(), itemId, item.getName(), effectivePrice);
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
        return make(player, name, type, atk, def, hp, rarity, sellPrice, 1, description, origin);
    }

    // Itens V3: overload com nível do item (itemLevel). [ITENS_V3]
    @Transactional
    public InventoryItem make(Player player, String name, ItemType type,
                              int atk, int def, int hp, int rarity, long sellPrice,
                              int itemLevel, String description, String origin) {
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
        item.setItemLevel(Math.max(1, itemLevel));
        item.setSellPrice(sellPrice);
        item.setDescription(description);
        item.setOrigin(origin);
        if (rarity >= 5) item.setSockets(3); // Lendário: sockets no máximo [ITENS_V2]
        InventoryItem saved = inventoryRepository.save(item);
        rollAffixesFor(saved, true); // Itens V2: afixos por raridade (no-op p/ Comum), renomeia com prefixo
        return saved;
    }

    /**
     * Itens V3: rola {atk, def, hp} a partir do NÍVEL DO ITEM × multiplicador de raridade.
     * Poder cresce com o nível; raridade é um multiplicador → "lvl100 Comum > lvl1 Épico".
     */
    public int[] rollItemStats(int itemLevel, int rarity) {
        return rollItemStats(itemLevel, rarity, java.util.concurrent.ThreadLocalRandom.current());
    }

    /** Overload com Random próprio (semeado) — usado pela loja p/ preview == compra serem idênticos. */
    public int[] rollItemStats(int itemLevel, int rarity, java.util.Random rng) {
        double mult = switch (rarity) { case 2 -> 1.2; case 3 -> 1.45; case 4 -> 1.75; case 5 -> 2.1; default -> 1.0; };
        double scale = Math.max(1, itemLevel) * mult;
        int atk = rng.nextInt((int) Math.round(scale * 0.6) + 1);
        int def = rng.nextInt((int) Math.round(scale * 0.6) + 1);
        int hp  = rng.nextInt((int) Math.round(scale * 2.2) + 1);
        if (atk == 0 && def == 0 && hp == 0) hp = (int) Math.round(scale); // garante ≥1 stat
        return new int[]{atk, def, hp};
    }

    // ── Afixos (Itens V2) ───────────────────────────────────────────────────────

    /**
     * Rola os afixos do item conforme a raridade (Comum 0 … Lendário 4), substituindo os anteriores.
     * Distintos, sorteados de um pool embaralhado. Se {@code rename}, prefixa o 1º adjetivo (PREFIX)
     * no nome (usado na criação; reforge mantém o nome). Atributos (STR/DEX/LUK) entram no combate
     * via WarriorStatsService. No-op para raridade < 2.
     */
    @Transactional
    public void rollAffixesFor(InventoryItem item, boolean rename) {
        affixRepository.deleteByItem(item); // limpa antigos (reforge re-rola)
        int count = item.getRarity() - 1;   // 1→0, 2→1, 3→2, 4→3, 5→4
        if (count <= 0) return;

        List<Affix> pool = new ArrayList<>(List.of(Affix.values()));
        Collections.shuffle(pool, RNG);
        List<Affix> chosen = pool.subList(0, Math.min(count, pool.size()));

        for (Affix a : chosen) {
            ItemAffix ia = new ItemAffix();
            ia.setItem(item);
            ia.setAffix(a);
            ia.setMagnitude(a.rollMagnitude(item.getItemLevel(), item.getRarity()));
            affixRepository.save(ia);
        }

        if (rename) {
            chosen.stream()
                    .filter(a -> a.position == Affix.Position.PREFIX)
                    .findFirst()
                    .ifPresent(p -> {
                        item.setName(p.word + " " + item.getName());
                        inventoryRepository.save(item);
                    });
        }
    }
}
