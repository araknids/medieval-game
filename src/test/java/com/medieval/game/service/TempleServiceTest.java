package com.medieval.game.service;

import com.medieval.game.enums.BuffType;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.WarriorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;

// TC-027-029 — TempleService: Cura, Buff e Proteção
@ExtendWith(MockitoExtension.class)
@DisplayName("TC-027-029 | TempleService — Cura e Buffs")
class TempleServiceTest {

    @Mock WarriorRepository       warriorRepository;
    @Mock InventoryItemRepository inventoryRepository;
    @Mock PlayerService           playerService;
    @InjectMocks TempleService templeService;

    // ── TC-027: Cura grátis para guerreiros nível ≤ 10 ──
    @Test
    @DisplayName("TC-027 | healCost = 0 para guerreiro nível 10")
    void tc027_healFreeForLevel10() {
        Warrior warrior = new Warrior();
        warrior.setLevel(10);

        long cost = templeService.healCost(warrior);
        assertThat(cost).isEqualTo(0L);
    }

    @Test
    @DisplayName("TC-027b | healCost = 0 para guerreiro nível 1")
    void tc027b_healFreeForLevel1() {
        Warrior warrior = new Warrior();
        warrior.setLevel(1);

        assertThat(templeService.healCost(warrior)).isEqualTo(0L);
    }

    // ── TC-028: Cura custa 100 bronze para nível > 10 ──
    @Test
    @DisplayName("TC-028 | healCost = 100 bronze para guerreiro nível 11")
    void tc028_healCosts100BronzeAfterLevel10() {
        Warrior warrior = new Warrior();
        warrior.setLevel(11);

        assertThat(templeService.healCost(warrior)).isEqualTo(100L);
    }

    @Test
    @DisplayName("TC-028b | healCost = 100 bronze para guerreiro nível 50")
    void tc028b_healCosts100BronzeForLevel50() {
        Warrior warrior = new Warrior();
        warrior.setLevel(50);

        assertThat(templeService.healCost(warrior)).isEqualTo(100L);
    }

    // ── TC-029: Buffs têm custo e efeito corretos ──
    @Test
    @DisplayName("TC-029 | STRENGTH custa 30 bronze e dá +5 ATK")
    void tc029_strengthBuff_correctCostAndEffect() {
        assertThat(BuffType.STRENGTH.bronzeCost).isEqualTo(30L);
        assertThat(BuffType.STRENGTH.atkBonus).isEqualTo(5);
        assertThat(BuffType.STRENGTH.defBonus).isEqualTo(0);
        assertThat(BuffType.STRENGTH.hpBonus).isEqualTo(0);
    }

    @Test
    @DisplayName("TC-029b | LUCK custa 50 bronze (mais caro que demais)")
    void tc029b_luckBuff_costs50Bronze() {
        assertThat(BuffType.LUCK.bronzeCost).isEqualTo(50L);
    }

    @Test
    @DisplayName("TC-029c | VITALITY dá +20 HP máximo")
    void tc029c_vitalityBuff_gives20Hp() {
        assertThat(BuffType.VITALITY.hpBonus).isEqualTo(20);
    }
}
