package com.medieval.game.integration;

import com.medieval.game.enums.ClassAbility;
import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.enums.WeaponType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.AbilityService;
import com.medieval.game.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Classe Mercador: armas (machado/marreta), bônus de economia (venda/drop/craft/coleta). [MERCADOR]
@DisplayName("Merchant | armas blunt + bônus de economia")
class MerchantClassTest extends BaseIntegrationTest {

    @Autowired AbilityService        abilityService;
    @Autowired InventoryService      inventoryService;
    @Autowired PlayerRepository      playerRepository;
    @Autowired WarriorRepository     warriorRepository;
    @Autowired InventoryItemRepository itemRepo;
    @Autowired com.medieval.game.service.WarriorStatsService statsService;

    private Player reload(Player p) { return playerRepository.findById(p.getId()).orElseThrow(); }

    private Player newPlayer(String prefix, WarriorClass clazz, int abilityPoints) {
        String u = uniqueUser(prefix);
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        p.addBronzeAmount(2000);
        p = playerRepository.save(p);
        Warrior w = new Warrior();
        w.setName("W_" + u); w.setWarriorClass(clazz); w.setPlayer(p);
        w.setLevel(20); w.setAbilityPoints(abilityPoints);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
        return p;
    }

    @Test
    @DisplayName("canEquip por tipo: Mercador só machado/marreta")
    void canEquip_byType() {
        assertThat(WarriorClass.MERCHANT.canEquip(WeaponType.AXE)).isTrue();
        assertThat(WarriorClass.MERCHANT.canEquip(WeaponType.MACE)).isTrue();
        assertThat(WarriorClass.MERCHANT.canEquip(WeaponType.SWORD)).isFalse();
        assertThat(WarriorClass.MERCHANT.canEquip(WeaponType.LONGBOW)).isFalse();
        assertThat(WarriorClass.WARRIOR.canEquip(WeaponType.AXE)).isTrue();    // warrior usa machado
        assertThat(WarriorClass.ARCHER.canEquip(WeaponType.MACE)).isFalse();
        assertThat(WeaponType.fromName("Iron Mace")).isEqualTo(WeaponType.MACE);
        assertThat(WeaponType.fromName("Heavy Maul")).isEqualTo(WeaponType.MACE);
    }

    @Test
    @DisplayName("Mercador equipa marreta/machado, mas não espada")
    void merchant_equipGuards() {
        Player p = newPlayer("mc", WarriorClass.MERCHANT, 0);
        InventoryItem mace = inventoryService.make(p, "Iron Mace", ItemType.WEAPON, 5, 0, 0, 1, 20);
        inventoryService.equip(p, mace.getId());
        assertThat(itemRepo.findById(mace.getId()).orElseThrow().isEquipped()).isTrue();

        InventoryItem sword = inventoryService.make(p, "Iron Sword", ItemType.WEAPON, 5, 0, 0, 1, 20);
        assertThatThrownBy(() -> inventoryService.equip(p, sword.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Getters de economia somam por nível (e 0 p/ não-Mercador)")
    void economyBonuses() {
        Player p = newPlayer("mc", WarriorClass.MERCHANT, 5);
        abilityService.learn(reload(p), ClassAbility.HAGGLER);
        abilityService.learn(reload(p), ClassAbility.HAGGLER);           // +3%/nível → 6%
        abilityService.learn(reload(p), ClassAbility.TREASURE_HUNTER);   // +2%/nível → 2
        assertThat(abilityService.sellPriceBonusPct(reload(p))).isEqualTo(6);
        assertThat(abilityService.dropChanceBonus(reload(p))).isEqualTo(2);

        Player warr = newPlayer("mc", WarriorClass.WARRIOR, 5);
        assertThat(abilityService.sellPriceBonusPct(reload(warr))).isZero();
    }

    @Test
    @DisplayName("Haggler aumenta o bronze recebido na venda")
    void sell_hagglerBonus() {
        Player p = newPlayer("mc", WarriorClass.MERCHANT, 2);
        abilityService.learn(reload(p), ClassAbility.HAGGLER);
        abilityService.learn(reload(p), ClassAbility.HAGGLER); // 6%
        InventoryItem ring = inventoryService.make(p, "Trade Ring", ItemType.RING, 0, 0, 0, 1, 1000);
        long before = reload(p).totalBronze();

        inventoryService.sell(reload(p), ring.getId());

        assertThat(reload(p).totalBronze()).isEqualTo(before + 1060); // 1000 × 1.06
    }

    @Test
    @DisplayName("Master Craftsman: item forjado pelo próprio Mercador ganha +stats; outros não")
    void selfCrafted_statBonus() {
        Player p = newPlayer("mc", WarriorClass.MERCHANT, 5);
        for (int i = 0; i < 4; i++) abilityService.learn(reload(p), ClassAbility.MASTER_CRAFTSMAN); // nível 4 → 10%
        assertThat(abilityService.selfCraftedStatBonusPct(reload(p))).isEqualTo(10);

        // Item forjado por ELE (RING, stats não são sobrescritos por WeaponType): +10% no ATK.
        InventoryItem mine = inventoryService.make(p, "Trade Charm", ItemType.RING, 0, 0, 0, 1, 100);
        mine.setAttackBonus(100); mine.setDefenseBonus(0); mine.setHealthBonus(0);
        mine.setCraftedBy(p.getId()); mine.setEquipped(true);
        itemRepo.save(mine);
        assertThat(statsService.equippedGear(reload(p)).atk()).isEqualTo(110); // 100 × 1.10

        // Mesmo item, mas forjado por OUTRO → sem bônus.
        mine.setCraftedBy(999_999L); itemRepo.save(mine);
        assertThat(statsService.equippedGear(reload(p)).atk()).isEqualTo(100);
    }

    @Test
    @DisplayName("Bônus de self-crafted só vale pro Mercador (Warrior = 0)")
    void selfCrafted_merchantOnly() {
        Player warr = newPlayer("wc", WarriorClass.WARRIOR, 5);
        assertThat(abilityService.selfCraftedStatBonusPct(reload(warr))).isZero();

        InventoryItem ring = inventoryService.make(warr, "Trinket", ItemType.RING, 0, 0, 0, 1, 100);
        ring.setAttackBonus(100); ring.setCraftedBy(warr.getId()); ring.setEquipped(true);
        itemRepo.save(ring);
        assertThat(statsService.equippedGear(reload(warr)).atk()).isEqualTo(100); // sem bônus
    }
}
