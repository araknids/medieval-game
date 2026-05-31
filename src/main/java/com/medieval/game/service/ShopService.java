package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.repository.InventoryItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShopService {

    private final InventoryItemRepository inventoryRepository;
    private final PlayerService playerService;

    public record ShopItem(int id, String name, ItemType type, int atk, int def, int hp, int rarity, int price) {
        public String rarityName() {
            return switch (rarity) {
                case 2 -> "Incomum";
                case 3 -> "Raro";
                default -> "Comum";
            };
        }
    }

    public static final List<ShopItem> ITEMS = List.of(
        new ShopItem( 1, "Espada de Aço",      ItemType.WEAPON,   6, 0,  0, 2, 350),
        new ShopItem( 2, "Elmo de Aço",        ItemType.HELMET,   0, 4, 15, 2, 280),
        new ShopItem( 3, "Armadura de Malha",  ItemType.ARMOR,    0, 6, 25, 2, 400),
        new ShopItem( 4, "Escudo de Ferro",    ItemType.SHIELD,   0, 5,  0, 2, 300),
        new ShopItem( 5, "Calça de Malha",     ItemType.PANTS,    0, 4, 12, 2, 250),
        new ShopItem( 6, "Botas de Ferro",     ItemType.BOOTS,    0, 3,  8, 2, 200),
        new ShopItem( 7, "Luvas de Ferro",     ItemType.GLOVES,   2, 2,  0, 2, 220),
        new ShopItem( 8, "Ombreira de Ferro",  ItemType.SHOULDER, 0, 3, 10, 2, 260),
        new ShopItem( 9, "Colar de Prata",     ItemType.NECKLACE, 1, 1,  5, 2, 320),
        new ShopItem(10, "Anel do Guerreiro",  ItemType.RING,     2, 0,  0, 2, 280)
    );

    public List<ShopItem> getItems() {
        return ITEMS;
    }

    @Transactional
    public InventoryItem buy(Player player, int shopItemId) {
        ShopItem shopItem = ITEMS.stream()
                .filter(i -> i.id() == shopItemId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Item não encontrado na loja"));

        playerService.spendGold(player, shopItem.price());

        InventoryItem item = new InventoryItem();
        item.setPlayer(player);
        item.setName(shopItem.name());
        item.setType(shopItem.type());
        item.setAttackBonus(shopItem.atk());
        item.setDefenseBonus(shopItem.def());
        item.setHealthBonus(shopItem.hp());
        item.setRarity(shopItem.rarity());
        item.setSellPrice(shopItem.price() / 2);
        return inventoryRepository.save(item);
    }
}
