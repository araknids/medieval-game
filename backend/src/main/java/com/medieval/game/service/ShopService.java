package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.ShopPurchase;
import com.medieval.game.repository.ShopPurchaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopPurchaseRepository  purchaseRepository;
    private final PlayerService           playerService;
    private final InventoryService        inventoryService;
    private final MailService             mailService;
    private final ItemLoreGenerator       loreGenerator;

    private static final long ROTATION_SECONDS = 6 * 60 * 60; // 6 horas
    private static final int SHOP_SIZE = 10;

    // ── Nomes do mercador ──
    private static final String[] MERCHANTS = {
        "Gareth the Wandering Merchant",
        "Mira of the Northern Lands",
        "Bjorn the Nordic Trader",
        "Isabella of the Spice Route",
        "Aldric the Traveling Blacksmith",
        "Sienna the Rarity Seller",
        "Thorvald the Warrior Merchant",
        "Lyra the Alchemist Trader",
        "Duncan of the Southern Isles",
        "Freya the Treasure Hunter"
    };

    private static final String[] QUOTES = {
        "Fresh goods from the northern lands!",
        "Rarities from a long journey!",
        "Battle equipment for the brave!",
        "Treasures from distant lands!",
        "Buy now before I move on!",
        "The finest items of this season!",
        "I traveled weeks to bring you this!",
        "Quality guaranteed by the merchant himself!"
    };

    // ── Pool de itens por raridade ──
    // Formato: nome, tipo, atk, def, hp, preço
    private static final Object[][] COMMON_ITEMS = {
        {"Kitchen Knife",        ItemType.WEAPON,   2, 0,  0,   60},
        {"Wooden Club",          ItemType.WEAPON,   3, 0,  0,   80},
        {"Rusty Sword",          ItemType.WEAPON,   2, 0,  0,   70},
        {"Straw Hat",            ItemType.HELMET,   0, 1,  5,   40},
        {"Leather Helm",         ItemType.HELMET,   0, 2,  8,   70},
        {"Leather Vest",         ItemType.ARMOR,    0, 3, 10,   90},
        {"Reinforced Tunic",     ItemType.ARMOR,    0, 2, 15,   80},
        {"Wooden Shield",        ItemType.SHIELD,   0, 3,  0,   75},
        {"Leather Buckler",      ItemType.SHIELD,   0, 2,  5,   60},
        {"Leather Sandals",      ItemType.BOOTS,    0, 1,  5,   45},
        {"Old Boots",            ItemType.BOOTS,    0, 1,  0,   35},
        {"Cloth Gloves",         ItemType.GLOVES,   1, 0,  0,   40},
        {"Leather Gauntlets",    ItemType.GLOVES,   1, 1,  0,   55},
        {"Leather Pants",        ItemType.PANTS,    0, 2,  8,   65},
        {"Old Leggings",         ItemType.PANTS,    0, 2,  0,   50},
        {"Leather Shoulder",     ItemType.SHOULDER, 0, 1,  5,   55},
        {"Simple Pauldron",      ItemType.SHOULDER, 0, 2,  0,   60},
        {"Bone Amulet",          ItemType.NECKLACE, 1, 0,  0,   50},
        {"Simple Necklace",      ItemType.NECKLACE, 0, 1,  5,   55},
        {"Iron Ring",            ItemType.RING,     1, 0,  0,   50},
        {"Simple Ring",          ItemType.RING,     0, 1,  0,   45},
    };

    private static final Object[][] UNCOMMON_ITEMS = {
        {"Iron Sword",           ItemType.WEAPON,   6, 0,  0,  280},
        {"Battle Axe",           ItemType.WEAPON,   7, 0,  0,  320},
        {"Iron Spear",           ItemType.WEAPON,   5, 1,  0,  260},
        {"Iron Helm",            ItemType.HELMET,   0, 4, 15,  250},
        {"Bronze Helmet",        ItemType.HELMET,   0, 5, 10,  280},
        {"Chainmail Armor",      ItemType.ARMOR,    0, 6, 20,  380},
        {"Bronze Breastplate",   ItemType.ARMOR,    0, 5, 25,  350},
        {"Iron Shield",          ItemType.SHIELD,   0, 5, 10,  300},
        {"Battle Buckler",       ItemType.SHIELD,   0, 6,  0,  280},
        {"Iron Boots",           ItemType.BOOTS,    0, 3,  8,  200},
        {"Bronze Greaves",       ItemType.BOOTS,    0, 4,  5,  220},
        {"Iron Gloves",          ItemType.GLOVES,   2, 2,  0,  220},
        {"Bronze Gauntlets",     ItemType.GLOVES,   3, 1,  0,  240},
        {"Chainmail Pants",      ItemType.PANTS,    0, 4, 12,  250},
        {"Bronze Leggings",      ItemType.PANTS,    0, 3, 15,  240},
        {"Iron Shoulder",        ItemType.SHOULDER, 0, 3, 10,  260},
        {"Bronze Pauldron",      ItemType.SHOULDER, 0, 4,  8,  270},
        {"Silver Necklace",      ItemType.NECKLACE, 2, 1,  5,  300},
        {"Bronze Amulet",        ItemType.NECKLACE, 1, 2,  8,  280},
        {"Silver Ring",          ItemType.RING,     2, 0,  0,  260},
        {"Bronze Ring",          ItemType.RING,     1, 1,  5,  240},
    };

    private static final Object[][] RARE_ITEMS = {
        {"Northern Blade",         ItemType.WEAPON,   11, 0,  0,  750},
        {"Tempered Steel Sword",   ItemType.WEAPON,   12, 0,  0,  800},
        {"Runic Axe",              ItemType.WEAPON,   10, 2,  0,  780},
        {"Steel Helm",             ItemType.HELMET,   0,  8, 20,  680},
        {"Battle Crown",           ItemType.HELMET,   0,  7, 25,  700},
        {"Steel Armor",            ItemType.ARMOR,    0, 10, 35,  900},
        {"Warrior's Breastplate",  ItemType.ARMOR,    0,  9, 40,  850},
        {"Steel Shield",           ItemType.SHIELD,   0,  9, 20,  780},
        {"Northern Aegis",         ItemType.SHIELD,   0,  8, 28,  800},
        {"Steel Boots",            ItemType.BOOTS,    0,  6, 15,  600},
        {"Northern Greaves",       ItemType.BOOTS,    0,  7, 12,  620},
        {"Steel Gloves",           ItemType.GLOVES,   4,  3,  0,  650},
        {"Warrior's Gauntlets",    ItemType.GLOVES,   5,  2,  0,  680},
        {"Steel Pants",            ItemType.PANTS,    0,  7, 25,  700},
        {"Northern Leggings",      ItemType.PANTS,    0,  6, 30,  720},
        {"Steel Shoulder",         ItemType.SHOULDER, 0,  6, 20,  660},
        {"Northern Pauldron",      ItemType.SHOULDER, 0,  5, 25,  640},
        {"Warrior's Necklace",     ItemType.NECKLACE, 3,  2, 10,  700},
        {"Runic Amulet",           ItemType.NECKLACE, 4,  0, 12,  720},
        {"Warrior's Ring",         ItemType.RING,     3,  1,  5,  650},
        {"Runic Ring",             ItemType.RING,     4,  0,  0,  680},
    };

    private static final Object[][] EPIC_ITEMS = {
        {"Legendary Sword",      ItemType.WEAPON,   18, 0,  0, 2000},
        {"Blade of Chaos",       ItemType.WEAPON,   20, 0,  0, 2200},
        {"Dragon Axe",           ItemType.WEAPON,   17, 3,  0, 2100},
        {"Dragon Helm",          ItemType.HELMET,   0, 12, 40, 1800},
        {"Crown of the King",    ItemType.HELMET,   2, 10, 50, 1900},
        {"Dragon Armor",         ItemType.ARMOR,    0, 15, 60, 2500},
        {"Legendary Breastplate",ItemType.ARMOR,    0, 14, 70, 2400},
        {"Titan Shield",         ItemType.SHIELD,   0, 13, 40, 2000},
        {"Dragon Aegis",         ItemType.SHIELD,   0, 12, 50, 2100},
        {"Abyssal Boots",        ItemType.BOOTS,    0, 10, 30, 1600},
        {"Gauntlets of Chaos",   ItemType.GLOVES,   8,  5,  0, 1800},
        {"Dragon Pants",         ItemType.PANTS,    0, 10, 50, 1900},
        {"Titan Shoulder",       ItemType.SHOULDER, 0,  9, 40, 1700},
        {"Dragon Necklace",      ItemType.NECKLACE, 6,  4, 20, 2000},
        {"Ring of Chaos",        ItemType.RING,     6,  2,  0, 1800},
    };

    // ── Cálculo da rotação ──
    public long currentRotationId() {
        return System.currentTimeMillis() / 1000 / ROTATION_SECONDS;
    }

    public long secondsUntilNextRotation() {
        long nowSeconds = System.currentTimeMillis() / 1000;
        return ROTATION_SECONDS - (nowSeconds % ROTATION_SECONDS);
    }

    public String merchantName() {
        return MERCHANTS[(int)(currentRotationId() % MERCHANTS.length)];
    }

    public String merchantQuote() {
        return QUOTES[(int)((currentRotationId() + 3) % QUOTES.length)];
    }

    // ── Gera os itens da rotação atual com status de compra por jogador ──
    public List<ShopItem> getItems(Player player) {
        long rotationId = currentRotationId();
        Random rng = new Random(rotationId);
        Set<Integer> bought = purchaseRepository.purchasedSlots(player, rotationId);
        List<ShopItem> items = new ArrayList<>();

        for (int slot = 0; slot < SHOP_SIZE; slot++) {
            int rarity = rollRarity(rng);
            Object[][] pool = poolFor(rarity);
            Object[] template = pool[rng.nextInt(pool.length)];

            long itemId = rotationId * SHOP_SIZE + slot;
            items.add(new ShopItem(
                itemId,
                (String)    template[0],
                (ItemType)  template[1],
                (int)       template[2],
                (int)       template[3],
                (int)       template[4],
                rarity,
                (int)       template[5],
                bought.contains(slot)
            ));
        }
        return items;
    }

    // ── Compra ──
    @Transactional
    public InventoryItem buy(Player player, long shopItemId) {
        log.info("[ShopService] player={} action=buy shopItemId={}", player.getId(), shopItemId);
        long rotationId = shopItemId / SHOP_SIZE;
        int  slot       = (int)(shopItemId % SHOP_SIZE);

        if (rotationId != currentRotationId()) {
            log.warn("[ShopService] player={} REJECTED: shop has rotated since page load", player.getId());
            throw new IllegalStateException("The shop has refreshed since you loaded the page. Reload and try again.");
        }

        Random rng = new Random(rotationId);
        ShopItem item = null;
        for (int i = 0; i <= slot; i++) {
            int rarity = rollRarity(rng);
            Object[][] pool = poolFor(rarity);
            Object[] template = pool[rng.nextInt(pool.length)];
            if (i == slot) {
                item = new ShopItem(shopItemId, (String) template[0], (ItemType) template[1],
                        (int) template[2], (int) template[3], (int) template[4], rarity, (int) template[5], false);
            }
        }

        if (item == null) {
            log.warn("[ShopService] player={} REJECTED: shop item {} not found", player.getId(), shopItemId);
            throw new IllegalStateException("Item not found");
        }

        // Bloqueia compra duplicada
        Set<Integer> bought = purchaseRepository.purchasedSlots(player, rotationId);
        if (bought.contains(slot)) {
            log.warn("[ShopService] player={} REJECTED: item at slot {} already purchased in this rotation", player.getId(), slot);
            throw new IllegalStateException("You already purchased this item in this rotation");
        }

        playerService.spendGold(player, item.price());

        // Registra a compra
        ShopPurchase purchase = new ShopPurchase();
        purchase.setPlayer(player);
        purchase.setRotationId(rotationId);
        purchase.setSlotIndex(slot);
        purchaseRepository.save(purchase);

        String desc   = loreGenerator.generateLore(item.rarity(), item.type(), rng);
        String origin = loreGenerator.originFromShop("Mercador Viajante");
        long   sell   = item.price() / 2;

        // Itens V3: loja mantém os stats curados, mas o item ganha um NÍVEL por faixa de raridade
        // (requisito pra equipar gateia o "lvl1 compra Épico e atropela"). [ITENS_V3]
        int ilvl = switch (item.rarity()) { case 2 -> 5; case 3 -> 15; case 4 -> 30; case 5 -> 50; default -> 1; };
        InventoryItem result;
        if (inventoryService.bagSize(player) < player.getMaxInventorySlots()) {
            result = inventoryService.make(player, item.name(), item.type(),
                    item.atk(), item.def(), item.hp(), item.rarity(), sell, ilvl, desc, origin);
            log.info("[ShopService] player={} action=buy OK shopItemId={} name={} price={}", player.getId(), shopItemId, item.name(), item.price());
        } else {
            mailService.sendItemMail(player, "Comprado na Loja.",
                    item.name(), item.type(), item.atk(), item.def(), item.hp(),
                    item.rarity(), 0, desc, origin);
            log.info("[ShopService] player={} action=buy OK (sent to mail — bag full) shopItemId={} name={}", player.getId(), shopItemId, item.name());
            result = null;
        }
        return result;
    }

    // ── Helpers ──
    private int rollRarity(Random rng) {
        double roll = rng.nextDouble();
        if (roll < 0.03) return 4;       // Épico 3%
        if (roll < 0.15) return 3;       // Raro 12%
        if (roll < 0.40) return 2;       // Incomum 25%
        return 1;                         // Comum 60%
    }

    private Object[][] poolFor(int rarity) {
        return switch (rarity) {
            case 4 -> EPIC_ITEMS;
            case 3 -> RARE_ITEMS;
            case 2 -> UNCOMMON_ITEMS;
            default -> COMMON_ITEMS;
        };
    }

    public record ShopItem(long id, String name, ItemType type,
                           int atk, int def, int hp, int rarity, int price,
                           boolean purchased) {
        public String rarityName() {
            return switch (rarity) {
                case 2 -> "Incomum"; case 3 -> "Raro"; case 4 -> "Épico"; default -> "Comum";
            };
        }
    }
}
