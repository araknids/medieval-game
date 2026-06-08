package com.medieval.game.service;

import com.medieval.game.enums.Affix;
import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.WeaponType;
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
    private final AbilityService          abilityService; // bônus de venda do Mercador [MERCADOR]

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
        return items.stream().filter(i -> !i.isListed() && !i.isConsigned()).toList(); // [LEILAO/MERCADO_STEAM] leilão/consignado não aparecem na bag
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

    // [BAG_WEIGHT] Bag unificada: 1 item = 1 slot inteiro; cada unidade de RECURSO pesa 0.2 slot
    // (5 recursos = 1 slot). Contamos em "quintos de slot" (inteiro) p/ evitar erro de ponto
    // flutuante com 0.2: item = 5 quintos, recurso = 1 quinto.
    private static final int SLOT_FIFTHS     = 5; // 1 slot = 5 quintos
    private static final int RESOURCE_FIFTHS = 1; // 1 recurso = 1 quinto (0.2 slot)

    /** Peso ocupado na bag, em quintos de slot (itens não-equipados/listados/consignados + recursos não-stashed). */
    private long bagFifths(Player player) {
        long items = inventoryRepository.findAllByPlayer(player).stream()
                .filter(i -> !i.isEquipped() && !i.isStashed() && !i.isListed() && !i.isConsigned()).count(); // [LEILAO/MERCADO_STEAM] listado/consignado não conta na bag
        long resources = resourceRepository.findAllByPlayerAndStashed(player, false).stream()
                .mapToLong(ResourceInventory::getQuantity).sum();
        return items * SLOT_FIFTHS + resources * RESOURCE_FIFTHS;
    }

    private long freeFifths(Player player) {
        return Math.max(0, (long) player.getMaxInventorySlots() * SLOT_FIFTHS - bagFifths(player));
    }

    /** Slots ocupados (arredonda p/ cima): 1 item = 1 slot, 5 recursos = 1 slot. [BAG_WEIGHT] */
    public int bagSize(Player player) {
        return (int) ((bagFifths(player) + SLOT_FIFTHS - 1) / SLOT_FIFTHS); // ceil
    }

    /** Slots livres p/ ITENS (cada item ocupa 1 slot inteiro). Nunca negativo. */
    public int bagSpaceLeft(Player player) {
        return (int) (freeFifths(player) / SLOT_FIFTHS); // floor — quantos itens inteiros ainda cabem
    }

    /** Quantas UNIDADES de RECURSO ainda cabem na bag (cada uma pesa 0.2 slot). [BAG_WEIGHT] */
    public long resourceSpaceLeft(Player player) {
        return freeFifths(player) / RESOURCE_FIFTHS;
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
            throw new com.medieval.game.config.LocalizedException("error.soulstones_required", "Not enough SoulStones. Required: {0}", SS_EXPAND_COST);
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
        if (item.isConsigned()) {
            throw new IllegalStateException("Item is consigned with the Blue Merchant.");
        }
        Warrior warrior = warriorRepository.findByPlayer(player).orElse(null);
        // Itens V3: requisito de nível — só equipa se itemLevel ≤ nível do guerreiro. [ITENS_V3]
        int level = warrior != null ? warrior.getLevel() : 1;
        if (item.getItemLevel() > level) {
            log.warn("[InventoryService] player={} REJECTED: item {} requires level {} (have {})", player.getId(), itemId, item.getItemLevel(), level);
            throw new com.medieval.game.config.LocalizedException("error.equip_level", "Requires level {0} to equip.", item.getItemLevel());
        }
        // [CLASSES_ARMAS/MERCADOR] Trava por TIPO: Archer só arco; Merchant só machado/marreta;
        // Warrior/Recruit qualquer corpo-a-corpo.
        if (item.getType() == ItemType.WEAPON && warrior != null) {
            com.medieval.game.enums.WarriorClass cls = warrior.getWarriorClass();
            WeaponType wt = WeaponType.fromName(item.getName());
            if (!cls.canEquip(wt)) {
                log.warn("[InventoryService] player={} REJECTED: weapon {} type {} not usable by class {}",
                        player.getId(), itemId, wt, cls);
                String msg = switch (cls) {
                    case ARCHER   -> "Archers can only wield ranged weapons (bows).";
                    case MERCHANT -> "Merchants can only wield axes and maces.";
                    default       -> "This class can only wield melee weapons (swords, axes…).";
                };
                throw new IllegalStateException(msg);
            }
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

    /**
     * Desequipa as armas equipadas que a classe {@code cls} NÃO pode usar (por tipo) — usado na
     * troca de classe (Archer perde melee; Merchant perde tudo que não for machado/marreta). [CLASSES_ARMAS/MERCADOR]
     */
    @Transactional
    public void unequipWeaponsNotUsable(Player player, com.medieval.game.enums.WarriorClass cls) {
        inventoryRepository.findAllByPlayer(player).stream()
                .filter(i -> i.getType() == ItemType.WEAPON && i.isEquipped())
                .filter(i -> !cls.canEquip(WeaponType.fromName(i.getName())))
                .forEach(i -> {
                    i.setEquipped(false);
                    inventoryRepository.save(i);
                    log.info("[InventoryService] player={} auto-unequip weapon {} (not usable by {})", player.getId(), i.getId(), cls);
                });
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
        if (item.isConsigned()) {
            throw new IllegalStateException("Item is consigned with the Blue Merchant.");
        }
        if (item.isPvpLocked() && player.isPvpFlagged()) {
            log.warn("[InventoryService] player={} REJECTED: item {} is PvP-locked (exposed)", player.getId(), itemId);
            throw new IllegalStateException("Item exposto no PvP — não pode vender enquanto você está flagged.");
        }
        // Preço efetivo escala com a durabilidade (piso 30%) — evita "lavar" o desgaste
        // vendendo um item surrado pelo preço cheio em vez de reparar. [AUDITORIA M1]
        long effectivePrice = Math.round(item.getSellPrice() * Math.max(0.30, item.getDurability() / 100.0));
        int sellBonus = abilityService.sellPriceBonusPct(player); // [MERCADOR] Haggler
        if (sellBonus > 0) effectivePrice = Math.round(effectivePrice * (1 + sellBonus / 100.0));
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
            throw new com.medieval.game.config.LocalizedException("error.inventory_full", "Inventory full ({0} slots). Sell items or expand with SoulStones.", max);
        }
        InventoryItem item = new InventoryItem();
        item.setPlayer(player);
        item.setName(name);
        item.setType(type);
        if (type == ItemType.WEAPON) {
            // [CLASSES_ARMAS] Arma se auto-perfila pelo TIPO (inferido do nome): categoria + stats
            // do WeaponType (atk/def/str/dex/luk por nível×raridade). Ignora atk/def/hp passados.
            WeaponType wt = WeaponType.fromName(name);
            item.setWeaponCategory(wt.category);
            int[] s = wt.stats(Math.max(1, itemLevel), rarity);
            item.setAttackBonus(s[0]); item.setDefenseBonus(s[1]); item.setHealthBonus(s[2]);
            item.setStrBonus(s[3]);    item.setDexBonus(s[4]);     item.setLukBonus(s[5]);
        } else {
            item.setAttackBonus(atk);
            item.setDefenseBonus(def);
            item.setHealthBonus(hp);
        }
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
