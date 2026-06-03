package com.medieval.game.model;

import com.medieval.game.enums.ItemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

// TC-240 — InventoryItem: durabilidade afeta os bônus efetivos
@DisplayName("TC-240 | InventoryItem — Durabilidade e bônus efetivo")
class InventoryItemDurabilityTest {

    private InventoryItem item(int atk, int def, int hp, int durability) {
        InventoryItem i = new InventoryItem();
        i.setType(ItemType.WEAPON);
        i.setAttackBonus(atk);
        i.setDefenseBonus(def);
        i.setHealthBonus(hp);
        i.setDurability(durability);
        return i;
    }

    @Test
    @DisplayName("TC-240a | Novo item começa com durabilidade 100")
    void tc240a_defaultDurability100() {
        assertThat(new InventoryItem().getDurability()).isEqualTo(100);
    }

    @Test
    @DisplayName("TC-240b | Item com durabilidade > 0 aplica os bônus normalmente")
    void tc240b_intactItem_appliesBonuses() {
        InventoryItem i = item(10, 5, 30, 100);
        assertThat(i.isBroken()).isFalse();
        assertThat(i.getEffectiveAttack()).isEqualTo(10);
        assertThat(i.getEffectiveDefense()).isEqualTo(5);
        assertThat(i.getEffectiveHealth()).isEqualTo(30);
    }

    @Test
    @DisplayName("TC-240c | Item quebrado (durabilidade 0) não dá nenhum bônus")
    void tc240c_brokenItem_givesNoBonus() {
        InventoryItem i = item(10, 5, 30, 0);
        assertThat(i.isBroken()).isTrue();
        assertThat(i.getEffectiveAttack()).isZero();
        assertThat(i.getEffectiveDefense()).isZero();
        assertThat(i.getEffectiveHealth()).isZero();
    }

    @Test
    @DisplayName("TC-240d | Item parcialmente desgastado ainda aplica o bônus total")
    void tc240d_partialDurability_stillFullBonus() {
        InventoryItem i = item(10, 5, 30, 1);
        assertThat(i.isBroken()).isFalse();
        assertThat(i.getEffectiveAttack()).isEqualTo(10);
    }
}
