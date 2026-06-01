package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.repository.InventoryItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class ShopService {

    private final InventoryItemRepository inventoryRepository;
    private final PlayerService playerService;

    private static final long ROTATION_SECONDS = 6 * 60 * 60; // 6 horas
    private static final int SHOP_SIZE = 10;

    // ── Nomes do mercador ──
    private static final String[] MERCHANTS = {
        "Gareth, o Mercador Andarilho",
        "Mira das Terras do Norte",
        "Bjorn, o Comerciante Nórdico",
        "Isabella da Rota das Especiarias",
        "Aldric, o Ferreiro Viajante",
        "Sienna, a Vendedora de Raridades",
        "Thorvald, o Mercador Guerreiro",
        "Lyra, a Alquimista Comerciante",
        "Duncan das Ilhas do Sul",
        "Freya, a Caçadora de Tesouros"
    };

    private static final String[] QUOTES = {
        "Mercadorias frescas das terras do norte!",
        "Raridades de uma longa jornada!",
        "Equipamentos de batalha para valentes!",
        "Tesouros de terras distantes!",
        "Compre agora antes que eu siga viagem!",
        "Os melhores itens desta temporada!",
        "Viajei semanas para trazer isso!",
        "Qualidade garantida pelo próprio mercador!"
    };

    // ── Pool de itens por raridade ──
    // Formato: nome, tipo, atk, def, hp, preço
    private static final Object[][] COMMON_ITEMS = {
        {"Faca de Cozinha",      ItemType.WEAPON,   2, 0,  0,   60},
        {"Clava de Madeira",     ItemType.WEAPON,   3, 0,  0,   80},
        {"Espada Enferrujada",   ItemType.WEAPON,   2, 0,  0,   70},
        {"Chapéu de Palha",      ItemType.HELMET,   0, 1,  5,   40},
        {"Elmo de Couro",        ItemType.HELMET,   0, 2,  8,   70},
        {"Colete de Couro",      ItemType.ARMOR,    0, 3, 10,   90},
        {"Túnica Reforçada",     ItemType.ARMOR,    0, 2, 15,   80},
        {"Escudo de Madeira",    ItemType.SHIELD,   0, 3,  0,   75},
        {"Broquel de Couro",     ItemType.SHIELD,   0, 2,  5,   60},
        {"Sandálias de Couro",   ItemType.BOOTS,    0, 1,  5,   45},
        {"Botas Velhas",         ItemType.BOOTS,    0, 1,  0,   35},
        {"Luvas de Tecido",      ItemType.GLOVES,   1, 0,  0,   40},
        {"Manoplas de Couro",    ItemType.GLOVES,   1, 1,  0,   55},
        {"Calça de Couro",       ItemType.PANTS,    0, 2,  8,   65},
        {"Perneiras Velhas",     ItemType.PANTS,    0, 2,  0,   50},
        {"Ombreira de Couro",    ItemType.SHOULDER, 0, 1,  5,   55},
        {"Espaldeira Simples",   ItemType.SHOULDER, 0, 2,  0,   60},
        {"Amuleto de Osso",      ItemType.NECKLACE, 1, 0,  0,   50},
        {"Colar Simples",        ItemType.NECKLACE, 0, 1,  5,   55},
        {"Anel de Ferro",        ItemType.RING,     1, 0,  0,   50},
        {"Anel Simples",         ItemType.RING,     0, 1,  0,   45},
    };

    private static final Object[][] UNCOMMON_ITEMS = {
        {"Espada de Ferro",        ItemType.WEAPON,   6, 0,  0,  280},
        {"Machado de Batalha",     ItemType.WEAPON,   7, 0,  0,  320},
        {"Lança de Ferro",         ItemType.WEAPON,   5, 1,  0,  260},
        {"Elmo de Ferro",          ItemType.HELMET,   0, 4, 15,  250},
        {"Capacete de Bronze",     ItemType.HELMET,   0, 5, 10,  280},
        {"Armadura de Malha",      ItemType.ARMOR,    0, 6, 20,  380},
        {"Couraça de Bronze",      ItemType.ARMOR,    0, 5, 25,  350},
        {"Escudo de Ferro",        ItemType.SHIELD,   0, 5, 10,  300},
        {"Broquel de Batalha",     ItemType.SHIELD,   0, 6,  0,  280},
        {"Botas de Ferro",         ItemType.BOOTS,    0, 3,  8,  200},
        {"Grevas de Bronze",       ItemType.BOOTS,    0, 4,  5,  220},
        {"Luvas de Ferro",         ItemType.GLOVES,   2, 2,  0,  220},
        {"Manoplas de Bronze",     ItemType.GLOVES,   3, 1,  0,  240},
        {"Calça de Malha",         ItemType.PANTS,    0, 4, 12,  250},
        {"Perneiras de Bronze",    ItemType.PANTS,    0, 3, 15,  240},
        {"Ombreira de Ferro",      ItemType.SHOULDER, 0, 3, 10,  260},
        {"Espaldeira de Bronze",   ItemType.SHOULDER, 0, 4,  8,  270},
        {"Colar de Prata",         ItemType.NECKLACE, 2, 1,  5,  300},
        {"Amuleto de Bronze",      ItemType.NECKLACE, 1, 2,  8,  280},
        {"Anel de Prata",          ItemType.RING,     2, 0,  0,  260},
        {"Anel de Bronze",         ItemType.RING,     1, 1,  5,  240},
    };

    private static final Object[][] RARE_ITEMS = {
        {"Lâmina do Norte",          ItemType.WEAPON,   11, 0,  0,  750},
        {"Espada de Aço Temperado",  ItemType.WEAPON,   12, 0,  0,  800},
        {"Machado Rúnico",           ItemType.WEAPON,   10, 2,  0,  780},
        {"Elmo de Aço",              ItemType.HELMET,   0,  8, 20,  680},
        {"Coroa de Batalha",         ItemType.HELMET,   0,  7, 25,  700},
        {"Armadura de Aço",          ItemType.ARMOR,    0, 10, 35,  900},
        {"Peitoral do Guerreiro",    ItemType.ARMOR,    0,  9, 40,  850},
        {"Escudo de Aço",            ItemType.SHIELD,   0,  9, 20,  780},
        {"Égide do Norte",           ItemType.SHIELD,   0,  8, 28,  800},
        {"Botas de Aço",             ItemType.BOOTS,    0,  6, 15,  600},
        {"Grevas do Norte",          ItemType.BOOTS,    0,  7, 12,  620},
        {"Luvas de Aço",             ItemType.GLOVES,   4,  3,  0,  650},
        {"Manoplas do Guerreiro",    ItemType.GLOVES,   5,  2,  0,  680},
        {"Calça de Aço",             ItemType.PANTS,    0,  7, 25,  700},
        {"Perneiras do Norte",       ItemType.PANTS,    0,  6, 30,  720},
        {"Ombreira de Aço",          ItemType.SHOULDER, 0,  6, 20,  660},
        {"Espaldeira do Norte",      ItemType.SHOULDER, 0,  5, 25,  640},
        {"Colar do Guerreiro",       ItemType.NECKLACE, 3,  2, 10,  700},
        {"Amuleto Rúnico",           ItemType.NECKLACE, 4,  0, 12,  720},
        {"Anel do Guerreiro",        ItemType.RING,     3,  1,  5,  650},
        {"Anel Rúnico",              ItemType.RING,     4,  0,  0,  680},
    };

    private static final Object[][] EPIC_ITEMS = {
        {"Espada Lendária",       ItemType.WEAPON,   18, 0,  0, 2000},
        {"Lâmina do Caos",        ItemType.WEAPON,   20, 0,  0, 2200},
        {"Machado do Dragão",     ItemType.WEAPON,   17, 3,  0, 2100},
        {"Elmo do Dragão",        ItemType.HELMET,   0, 12, 40, 1800},
        {"Coroa do Rei",          ItemType.HELMET,   2, 10, 50, 1900},
        {"Armadura do Dragão",    ItemType.ARMOR,    0, 15, 60, 2500},
        {"Couraça Lendária",      ItemType.ARMOR,    0, 14, 70, 2400},
        {"Escudo do Titã",        ItemType.SHIELD,   0, 13, 40, 2000},
        {"Égide do Dragão",       ItemType.SHIELD,   0, 12, 50, 2100},
        {"Botas do Abismo",       ItemType.BOOTS,    0, 10, 30, 1600},
        {"Manoplas do Caos",      ItemType.GLOVES,   8,  5,  0, 1800},
        {"Calça do Dragão",       ItemType.PANTS,    0, 10, 50, 1900},
        {"Ombreira do Titã",      ItemType.SHOULDER, 0,  9, 40, 1700},
        {"Colar do Dragão",       ItemType.NECKLACE, 6,  4, 20, 2000},
        {"Anel do Caos",          ItemType.RING,     6,  2,  0, 1800},
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

    // ── Gera os itens da rotação atual ──
    public List<ShopItem> getItems() {
        long rotationId = currentRotationId();
        Random rng = new Random(rotationId);
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
                (int)       template[5]
            ));
        }
        return items;
    }

    // ── Compra ──
    @Transactional
    public InventoryItem buy(Player player, long shopItemId) {
        long rotationId = shopItemId / SHOP_SIZE;
        int  slot       = (int)(shopItemId % SHOP_SIZE);

        if (rotationId != currentRotationId()) {
            throw new IllegalStateException("A loja mudou desde que você carregou a página. Atualize e tente novamente.");
        }

        Random rng = new Random(rotationId);
        ShopItem item = null;
        for (int i = 0; i <= slot; i++) {
            int rarity = rollRarity(rng);
            Object[][] pool = poolFor(rarity);
            Object[] template = pool[rng.nextInt(pool.length)];
            if (i == slot) {
                item = new ShopItem(shopItemId, (String) template[0], (ItemType) template[1],
                        (int) template[2], (int) template[3], (int) template[4], rarity, (int) template[5]);
            }
        }

        if (item == null) throw new IllegalStateException("Item não encontrado");

        playerService.spendGold(player, item.price());

        InventoryItem inv = new InventoryItem();
        inv.setPlayer(player);
        inv.setName(item.name());
        inv.setType(item.type());
        inv.setAttackBonus(item.atk());
        inv.setDefenseBonus(item.def());
        inv.setHealthBonus(item.hp());
        inv.setRarity(item.rarity());
        inv.setSellPrice(item.price() / 2);
        return inventoryRepository.save(inv);
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
                           int atk, int def, int hp, int rarity, int price) {
        public String rarityName() {
            return switch (rarity) {
                case 2 -> "Incomum"; case 3 -> "Raro"; case 4 -> "Épico"; default -> "Comum";
            };
        }
    }
}
