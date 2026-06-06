package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.ItemAffix;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.ItemAffixRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.WarriorStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// Itens V2 (Fase A) — afixos por raridade + tier Lendário
@DisplayName("Itens V2 | Afixos + Lendário")
class ItemAffixTest extends BaseIntegrationTest {

    @Autowired InventoryService        inventoryService;
    @Autowired ItemAffixRepository     affixRepository;
    @Autowired WarriorStatsService     statsService;
    @Autowired PlayerRepository        playerRepository;
    @Autowired WarriorRepository       warriorRepository;
    @Autowired InventoryItemRepository inventoryRepository;

    Player player;

    @BeforeEach
    void setup() throws Exception {
        String user = uniqueUser("affix");
        registerAndGetToken(user);
        player = playerRepository.findAll().stream()
                .filter(p -> p.getUsername().equals(user))
                .findFirst().orElseThrow();
        // Limpa os itens iniciais (Comuns, sem afixo) para abrir espaço na bag.
        inventoryRepository.deleteAll(inventoryRepository.findAllByPlayer(player));
    }

    @Test
    @DisplayName("Comum (raridade 1) não rola afixo")
    void common_noAffixes() {
        InventoryItem it = inventoryService.make(player, "Common Helm", ItemType.HELMET, 1, 1, 1, 1, 10);
        assertThat(affixRepository.findAllByItem(it)).isEmpty();
    }

    @Test
    @DisplayName("Contagem de afixos = raridade - 1 (2→1 … 5→4)")
    void affixCount_byRarity() {
        for (int rarity = 2; rarity <= 5; rarity++) {
            InventoryItem it = inventoryService.make(player, "Item r" + rarity, ItemType.RING, 1, 1, 1, rarity, 10);
            assertThat(affixRepository.findAllByItem(it))
                    .as("raridade %d", rarity)
                    .hasSize(rarity - 1);
        }
    }

    @Test
    @DisplayName("Lendário (raridade 5): 4 afixos e sockets no máximo (3)")
    void legendary_maxSocketsAndAffixes() {
        InventoryItem it = inventoryService.make(player, "Legendary Blade", ItemType.WEAPON, 10, 0, 0, 5, 2500);
        assertThat(it.getSockets()).isEqualTo(3);
        assertThat(affixRepository.findAllByItem(it)).hasSize(4);
    }

    @Test
    @DisplayName("Afixos do item equipado entram no combatStats (planos + atributos)")
    void affixes_affectCombatStats() {
        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
        int[] before = statsService.combatStats(player, w);

        // ARMOR (não-arma) p/ honrar os stats base passados — armas auto-perfilam pelo tipo. [CLASSES_ARMAS]
        InventoryItem it = inventoryService.make(player, "Legendary Plate", ItemType.ARMOR, 10, 5, 20, 5, 2500);
        inventoryService.equip(player, it.getId());

        int aAtk = 0, aDef = 0, aHp = 0, aStr = 0, aDex = 0, aLuk = 0;
        for (ItemAffix af : affixRepository.findAllByItem(it)) {
            int m = af.getMagnitude();
            switch (af.getAffix().stat) {
                case ATK -> aAtk += m;
                case DEF -> aDef += m;
                case HP  -> aHp  += m;
                case STR -> aStr += m;
                case DEX -> aDex += m;
                case LUK -> aLuk += m;
            }
        }

        int[] after = statsService.combatStats(player, w);
        assertThat(after[0]).as("ATK").isEqualTo(before[0] + 10 + aAtk + aStr); // base + afixo ATK + afixo STR(+1/pt)
        assertThat(after[1]).as("DEF").isEqualTo(before[1] + 5  + aDef);
        assertThat(after[2]).as("HP").isEqualTo(before[2] + 20 + aHp);
        assertThat(after[3]).as("DEX/AC").isEqualTo(before[3] + aDex);
        assertThat(after[5]).as("LUK").isEqualTo(before[5] + aLuk);
    }

    @Test
    @DisplayName("Vender item afixado limpa os afixos sem violar FK")
    void sellAffixedItem_cleansAffixes() {
        InventoryItem it = inventoryService.make(player, "Epic Ring", ItemType.RING, 1, 1, 1, 4, 400);
        Long id = it.getId();
        assertThat(affixRepository.findAllByItem(it)).isNotEmpty();

        inventoryService.sell(player, id); // não pode estourar FK

        assertThat(inventoryRepository.findById(id)).isEmpty();
        assertThat(affixRepository.findAll())
                .noneMatch(a -> a.getItem().getId().equals(id));
    }
}
