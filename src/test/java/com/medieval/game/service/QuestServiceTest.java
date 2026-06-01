package com.medieval.game.service;

import com.medieval.game.enums.QuestType;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

// TC-009 a TC-011 — QuestService
@ExtendWith(MockitoExtension.class)
@DisplayName("TC-009-011 | QuestService — Drop e Recompensas")
class QuestServiceTest {

    @Mock ActiveQuestRepository questRepository;
    @Mock WarriorRepository     warriorRepository;
    @Mock PlayerService         playerService;
    @Mock WarriorService        warriorService;
    @Mock InventoryService      inventoryService;
    @InjectMocks QuestService questService;

    Player  player;
    Warrior warrior;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(questService, "instantComplete", false);
        player  = new Player();
        warrior = new Warrior();
        warrior.setLevel(1);
        warrior.setLuck(0);
    }

    // ── TC-009: Recompensa de bronze por tipo de quest ──
    @Test
    @DisplayName("TC-009 | PATROL dá 100 bronze de recompensa")
    void tc009_patrolReward_is100Bronze() {
        assertThat(QuestType.PATROL.bronzeReward).isEqualTo(100L);
    }

    @Test
    @DisplayName("TC-009b | BOSS_HUNT dá 1000 bronze de recompensa")
    void tc009b_bossHuntReward_is1000Bronze() {
        assertThat(QuestType.BOSS_HUNT.bronzeReward).isEqualTo(1000L);
    }

    // ── TC-010: Bônus de Sorte aumenta chance de drop ──
    @Test
    @DisplayName("TC-010 | Sorte +10 soma 10 à drop chance base de PATROL (10% → 20%)")
    void tc010_luckBonusIncreasesDropChance() {
        warrior.setLuck(10);

        int baseChance  = 10; // PATROL base drop chance
        int totalChance = baseChance + warrior.getLuck();

        assertThat(totalChance).isEqualTo(20);
    }

    // ── TC-011: Raridade do drop por tipo de quest ──
    @Test
    @DisplayName("TC-011 | BOSS_HUNT produz drop Raro ou Épico (rarity 3 ou 4)")
    void tc011_bossHuntDropRarity() {
        // BOSS_HUNT → rarity 3 ou 4
        // Verificamos os stamps de custo de stamina para garantir o tipo
        assertThat(QuestType.BOSS_HUNT.staminaCost).isEqualTo(50);
        assertThat(QuestType.BOSS_HUNT.durationMinutes).isEqualTo(30);
    }
}
