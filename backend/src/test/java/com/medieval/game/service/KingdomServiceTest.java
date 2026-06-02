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

    // TC-050: Each kingdom has exactly 4 quest types
    @Test
    @DisplayName("TC-050 | Each kingdom has exactly 4 quest types")
    void tc050_eachKingdomHas4Quests() {
        for (Kingdom k : Kingdom.values()) {
            long count = Arrays.stream(KingdomQuestType.values())
                    .filter(q -> q.kingdom == k).count();
            assertThat(count).as("Kingdom %s should have 4 quests", k).isEqualTo(4);
        }
    }

    // TC-051: Kingdom.ofTerritory maps correctly
    @Test
    @DisplayName("TC-051 | Kingdom.ofTerritory returns correct kingdom for each territory")
    void tc051_ofTerritory_mapsCorrectly() {
        assertThat(Kingdom.ofTerritory(com.medieval.game.enums.Territory.DESFILADEIRO_DO_OSSO))
                .isEqualTo(Kingdom.FISHING);
        assertThat(Kingdom.ofTerritory(com.medieval.game.enums.Territory.MINAS_DE_FERRO_NEGRO))
                .isEqualTo(Kingdom.MINING);
        assertThat(Kingdom.ofTerritory(com.medieval.game.enums.Territory.FORTALEZA_MALDITA))
                .isEqualTo(Kingdom.COMBAT);
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
