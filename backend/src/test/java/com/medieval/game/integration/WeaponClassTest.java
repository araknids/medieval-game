package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.enums.WeaponCategory;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.ClassChangeService;
import com.medieval.game.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Trava de arma por classe: arco (Archer) vs corpo-a-corpo (Warrior/Recruit). [CLASSES_ARMAS]
@DisplayName("Armas por classe | arco vs espada + trava no equip")
class WeaponClassTest extends BaseIntegrationTest {

    @Autowired InventoryService        inventoryService;
    @Autowired ClassChangeService      classService;
    @Autowired PlayerRepository        playerRepository;
    @Autowired WarriorRepository       warriorRepository;
    @Autowired InventoryItemRepository itemRepo;
    @Autowired com.medieval.game.repository.ResourceInventoryRepository resourceInventoryRepository; // [TRIAL_CUSTO]

    /** Estoque de Monster Core (stashed) p/ passar no gate da Trial. [TRIAL_CUSTO] */
    private void giveCore(Player p, long qty) {
        var r = new com.medieval.game.model.ResourceInventory();
        r.setPlayer(p); r.setResourceType(com.medieval.game.enums.ResourceType.MONSTER_CORE);
        r.setStashed(true); r.setQuantity(qty);
        resourceInventoryRepository.save(r);
    }

    private Player newPlayer(String prefix) {
        String u = uniqueUser(prefix);
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        return playerRepository.save(p);
    }

    private Warrior makeWarrior(Player p, WarriorClass clazz, int level) {
        Warrior w = new Warrior();
        w.setName("W_" + p.getUsername());
        w.setWarriorClass(clazz);
        w.setPlayer(p);
        w.setLevel(level);
        w.setAttack(clazz.baseAttack);
        w.setCurrentHpSnapshot(100);
        w.setHpUpdatedAt(LocalDateTime.now());
        return warriorRepository.save(w);
    }

    private InventoryItem weapon(Player p, String name) {
        return inventoryService.make(p, name, ItemType.WEAPON, 5, 0, 0, 1, 20, 1, "d", "o");
    }

    // ── make() infere a categoria pelo nome ──
    @Test
    @DisplayName("make() infere MELEE p/ espada e RANGED p/ arco (pelo nome)")
    void make_infersCategoryFromName() {
        Player p = newPlayer("wc");
        makeWarrior(p, WarriorClass.RECRUIT, 5);
        assertThat(weapon(p, "Iron Sword").effectiveWeaponCategory()).isEqualTo(WeaponCategory.MELEE);
        assertThat(weapon(p, "Hunting Bow").effectiveWeaponCategory()).isEqualTo(WeaponCategory.RANGED);
        assertThat(weapon(p, "Arco de Ferro Forjado").effectiveWeaponCategory()).isEqualTo(WeaponCategory.RANGED);
    }

    // ── Trava no equip ──
    @Test
    @DisplayName("Guerreiro não equipa arco; arqueiro não equipa espada")
    void equip_rejectsCrossClassWeapon() {
        Player warriorP = newPlayer("wc");
        makeWarrior(warriorP, WarriorClass.WARRIOR, 20);
        InventoryItem bow = weapon(warriorP, "Longbow");
        assertThatThrownBy(() -> inventoryService.equip(warriorP, bow.getId()))
                .isInstanceOf(IllegalStateException.class);

        Player archerP = newPlayer("wc");
        makeWarrior(archerP, WarriorClass.ARCHER, 20);
        InventoryItem sword = weapon(archerP, "Iron Sword");
        assertThatThrownBy(() -> inventoryService.equip(archerP, sword.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Cada classe equipa a arma da sua categoria")
    void equip_acceptsMatchingWeapon() {
        Player warriorP = newPlayer("wc");
        makeWarrior(warriorP, WarriorClass.WARRIOR, 20);
        InventoryItem sword = weapon(warriorP, "Iron Sword");
        inventoryService.equip(warriorP, sword.getId());
        assertThat(itemRepo.findById(sword.getId()).orElseThrow().isEquipped()).isTrue();

        Player archerP = newPlayer("wc");
        makeWarrior(archerP, WarriorClass.ARCHER, 20);
        InventoryItem bow = weapon(archerP, "Hunting Bow");
        inventoryService.equip(archerP, bow.getId());
        assertThat(itemRepo.findById(bow.getId()).orElseThrow().isEquipped()).isTrue();
    }

    // ── Arma legada (categoria null) conta como MELEE ──
    @Test
    @DisplayName("Arma legada (categoria null) é tratada como MELEE")
    void legacyWeapon_treatedAsMelee() {
        Player p = newPlayer("wc");
        makeWarrior(p, WarriorClass.WARRIOR, 20);
        InventoryItem legacy = weapon(p, "Iron Sword");
        legacy.setWeaponCategory(null); // simula item pré-feature
        itemRepo.save(legacy);
        // Warrior (MELEE) equipa um item legado sem problema.
        inventoryService.equip(p, legacy.getId());
        assertThat(itemRepo.findById(legacy.getId()).orElseThrow().isEquipped()).isTrue();
    }

    // ── Troca p/ Archer: desequipa a espada e dá um arco inicial ──
    @Test
    @DisplayName("Virar Archer na Trial desequipa a espada e entrega um arco inicial")
    void classChangeToArcher_swapsToBow() {
        Player p = newPlayer("wc");
        Warrior w = makeWarrior(p, WarriorClass.RECRUIT, 10);
        // Recruit forte → vence a Trial com folga.
        w.setAttack(300); w.setStrength(800); w.setConstitution(200);
        warriorRepository.save(w);
        giveCore(p, 100); // [TRIAL_CUSTO] custo da Trial

        InventoryItem sword = weapon(p, "Iron Sword");
        inventoryService.equip(p, sword.getId());
        assertThat(itemRepo.findById(sword.getId()).orElseThrow().isEquipped()).isTrue();

        var r = classService.attemptTrial(p, WarriorClass.ARCHER);
        assertThat(r.won()).isTrue();

        // A espada melee foi desequipada (ficaria travada) e ganhou um arco.
        assertThat(itemRepo.findById(sword.getId()).orElseThrow().isEquipped()).isFalse();
        boolean hasBow = inventoryService.getInventory(playerRepository.findById(p.getId()).orElseThrow()).stream()
                .anyMatch(i -> i.getType() == ItemType.WEAPON
                        && i.effectiveWeaponCategory() == WeaponCategory.RANGED);
        assertThat(hasBow).isTrue();
    }

    // ── make() aplica o perfil do tipo de arma (stats secundários + categoria pelo nome) ──
    @Test
    @DisplayName("make() aplica o perfil: Axe→LUK, Spear→STR, Short Bow→DEX, Greatsword→ATK puro, sem HP")
    void make_appliesWeaponProfile() {
        Player p = newPlayer("wc");
        makeWarrior(p, WarriorClass.RECRUIT, 30);

        InventoryItem axe = inventoryService.make(p, "Battle Axe", ItemType.WEAPON, 0, 0, 0, 1, 20, 30, "d", "o");
        assertThat(axe.getLukBonus()).isGreaterThan(0);
        assertThat(axe.getStrBonus()).isZero();
        assertThat(axe.getHealthBonus()).isZero();
        assertThat(axe.effectiveWeaponCategory()).isEqualTo(WeaponCategory.MELEE);

        InventoryItem spear = inventoryService.make(p, "Iron Spear", ItemType.WEAPON, 0, 0, 0, 1, 20, 30, "d", "o");
        assertThat(spear.getStrBonus()).isGreaterThan(0);

        InventoryItem sbow = inventoryService.make(p, "Short Bow", ItemType.WEAPON, 0, 0, 0, 1, 20, 30, "d", "o");
        assertThat(sbow.getDexBonus()).isGreaterThan(0);
        assertThat(sbow.effectiveWeaponCategory()).isEqualTo(WeaponCategory.RANGED);

        InventoryItem great = inventoryService.make(p, "Greatsword", ItemType.WEAPON, 0, 0, 0, 1, 20, 30, "d", "o");
        assertThat(great.getAttackBonus()).isGreaterThan(0);
        assertThat(great.getDefenseBonus()).isZero();
        assertThat(great.getStrBonus()).isZero();
    }
}
