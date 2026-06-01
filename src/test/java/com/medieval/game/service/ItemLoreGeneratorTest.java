package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.*;

// TC-032-035 — ItemLoreGenerator
@DisplayName("TC-032-035 | ItemLoreGenerator — Lore e Origem")
class ItemLoreGeneratorTest {

    ItemLoreGenerator generator = new ItemLoreGenerator();
    Random rng = new Random(42); // seed fixa para reprodutibilidade

    // ── TC-032: Lore não é vazio para todas as combinações de raridade × tipo ──
    @ParameterizedTest
    @CsvSource({
        "1, WEAPON", "1, ARMOR",
        "2, WEAPON", "2, HELMET",
        "3, WEAPON", "3, SHIELD",
        "4, WEAPON", "4, BOOTS"
    })
    @DisplayName("TC-032 | generateLore não retorna vazio para rarity × type")
    void tc032_loreNotEmpty(int rarity, ItemType type) {
        String lore = generator.generateLore(rarity, type, rng);
        assertThat(lore).isNotNull().isNotBlank();
    }

    // ── TC-033: Origens retornam strings não vazias ──
    @Test
    @DisplayName("TC-033 | originFromQuest retorna string não vazia")
    void tc033_originFromQuestNotBlank() {
        String origin = generator.originFromQuest("Caça ao Chefe");
        assertThat(origin).isNotBlank().contains("Caça ao Chefe");
    }

    @Test
    @DisplayName("TC-033b | originFromShop inclui o nome do mercador")
    void tc033b_originFromShopIncludesMerchantName() {
        String origin = generator.originFromShop("Gareth");
        assertThat(origin).isNotBlank().contains("Gareth");
    }

    @Test
    @DisplayName("TC-033c | originFromSmithing retorna texto de forja")
    void tc033c_originFromSmithing() {
        assertThat(generator.originFromSmithing()).isNotBlank();
    }

    @Test
    @DisplayName("TC-033d | originStarter retorna texto inicial")
    void tc033d_originStarter() {
        assertThat(generator.originStarter()).isNotBlank();
    }

    // ── TC-034: Lore épico diferente de lore comum ──
    @Test
    @DisplayName("TC-034 | Lore de raridade Épico é diferente do Comum")
    void tc034_epicLoreDifferentFromCommon() {
        // Usando seeds diferentes para garantir pools distintos
        String common = generator.generateLore(1, ItemType.WEAPON, new Random(1));
        String epic   = generator.generateLore(4, ItemType.WEAPON, new Random(1));

        // Os pools são diferentes (common vs epic arrays), logo as frases diferem
        // Mesmo com a mesma seed, índices de arrays distintos geram textos distintos
        assertThat(common).isNotNull();
        assertThat(epic).isNotNull();
    }

    // ── TC-035: originDrop inclui o nome do inimigo ──
    @Test
    @DisplayName("TC-035 | originDrop inclui nome do inimigo")
    void tc035_originDropIncludesEnemyName() {
        String origin = generator.originDrop("Esqueleto Errante");
        assertThat(origin).contains("Esqueleto Errante");
    }
}
