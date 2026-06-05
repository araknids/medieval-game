package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// TC-049 to TC-052 — Kingdom unit tests
@DisplayName("TC-049-052 | Kingdom — Enums and Quest Filtering")
class KingdomServiceTest {

    // TC-049: Each kingdom has only its own quest types
    @Test
    @DisplayName("TC-049 | FISHING quests do not appear for MINING or COMBAT")
    void tc049_questsAreKingdomSpecific() {
        List<KingdomQuestType> fishingQuests = Arrays.stream(KingdomQuestType.values())
                .filter(q -> q.kingdom == Kingdom.FISHING)
                .toList();

        assertThat(fishingQuests).isNotEmpty();
        assertThat(fishingQuests).allMatch(q -> q.kingdom == Kingdom.FISHING);
        assertThat(fishingQuests).noneMatch(q -> q.kingdom == Kingdom.MINING);
        assertThat(fishingQuests).noneMatch(q -> q.kingdom == Kingdom.COMBAT);
    }

    // TC-050: Cada reino tem exatamente 6 quests (Quests V2 — vitrine de 2 rotaciona entre as 6).
    @Test
    @DisplayName("TC-050 | Cada reino tem exatamente 6 quests")
    void tc050_questKingdomsHave4() {
        for (Kingdom k : Kingdom.values()) {
            long count = Arrays.stream(KingdomQuestType.values())
                    .filter(q -> q.kingdom == k).count();
            assertThat(count).as("Kingdom %s quest count", k).isEqualTo(6);
        }
    }

    // TC-050b: a vitrine mostra 2 das 6 e revezа conforme a janela de rotação (12h, daily). [DAILY_QUESTS]
    @Test
    @DisplayName("TC-050b | rotatingWindow mostra 2 e cobre todas as 6 ao longo das janelas")
    void tc050b_rotatingWindowShows2AndCyclesAll() {
        List<KingdomQuestType> all = Arrays.stream(KingdomQuestType.values())
                .filter(q -> q.kingdom == Kingdom.FISHING).toList();
        assertThat(all).hasSize(6);

        // Cada janela retorna exatamente 2 quests consecutivas.
        for (long rot = 0; rot < 6; rot++) {
            List<KingdomQuestType> window = KingdomService.rotatingWindow(all, rot);
            assertThat(window).as("janela %d", rot).hasSize(2);
            assertThat(all).contains(window.get(0), window.get(1));
        }
        // janela 0 começa na 1ª quest; janela avança 1 por rotação.
        assertThat(KingdomService.rotatingWindow(all, 0)).containsExactly(all.get(0), all.get(1));
        assertThat(KingdomService.rotatingWindow(all, 1)).containsExactly(all.get(1), all.get(2));
        // ao longo de 6 janelas, todas as 6 quests aparecem ao menos uma vez.
        java.util.Set<KingdomQuestType> seen = new java.util.HashSet<>();
        for (long rot = 0; rot < 6; rot++) seen.addAll(KingdomService.rotatingWindow(all, rot));
        assertThat(seen).hasSize(6);
    }

    // TC-051: Kingdom unificado absorve os dados de território (NPC + bônus exclusivo).
    // Os 3 reinos antigos (guild-war) têm NPC + exclusiveBonus > 0; reinos de coleta V2 têm bônus 0. [REINOS_V2]
    @Test
    @DisplayName("TC-051 | Reinos de guerra têm NPC e bônus exclusivo; reinos V2 têm bônus 0")
    void tc051_unifiedKingdomCarriesTerritoryData() {
        for (Kingdom war : new Kingdom[]{ Kingdom.FISHING, Kingdom.MINING, Kingdom.COMBAT }) {
            assertThat(war.npcName).as("%s npcName", war).isNotBlank();
            assertThat(war.exclusiveBonus).as("%s exclusiveBonus", war).isPositive();
        }
        for (Kingdom v2 : new Kingdom[]{ Kingdom.GRUTAS_DE_CRISTAL, Kingdom.MAR_ABENCOADO }) {
            assertThat(v2.exclusiveBonus).as("%s exclusiveBonus", v2).isZero();
        }
    }

    // TC-052: COMBAT kingdom has no primarySkill (null)
    @Test
    @DisplayName("TC-052 | COMBAT kingdom has null primarySkill, FISHING/MINING have one")
    void tc052_combatHasNoPrimarySkill() {
        assertThat(Kingdom.COMBAT.primarySkill).isNull();
        assertThat(Kingdom.FISHING.primarySkill).isNotNull();
        assertThat(Kingdom.MINING.primarySkill).isNotNull();
    }

    // Extra: quest rewards scale with difficulty tier
    @Test
    @DisplayName("TC-extra | Later quests have higher rewards than earlier quests")
    void tcExtra_questsScaleByDifficulty() {
        KingdomQuestType[] fishQuests = Arrays.stream(KingdomQuestType.values())
                .filter(q -> q.kingdom == Kingdom.FISHING)
                .toArray(KingdomQuestType[]::new);

        // Last quest should reward more bronze than first
        assertThat(fishQuests[fishQuests.length - 1].bronzeReward)
                .isGreaterThan(fishQuests[0].bronzeReward);
        // Last quest should cost more stamina
        assertThat(fishQuests[fishQuests.length - 1].staminaCost)
                .isGreaterThan(fishQuests[0].staminaCost);
    }
}
