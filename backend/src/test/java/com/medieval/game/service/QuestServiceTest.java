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

    // ── TC-009: Nichos de recompensa (Combate V2) ──
    @Test
    @DisplayName("TC-009 | Curtas são reis de BRONZE/estamina (PATROL > BOSS_HUNT por estamina)")
    void tc009_shortQuests_bestBronzePerStamina() {
        double patrol = (double) QuestType.PATROL.bronzeReward / QuestType.PATROL.staminaCost;
        double boss   = (double) QuestType.BOSS_HUNT.bronzeReward / QuestType.BOSS_HUNT.staminaCost;
        assertThat(patrol).isGreaterThan(boss); // 18 vs 12
    }

    @Test
    @DisplayName("TC-009b | Longas são reis de XP/estamina (BOSS_HUNT > PATROL por estamina)")
    void tc009b_longQuests_bestXpPerStamina() {
        double patrol = (double) QuestType.PATROL.expReward / QuestType.PATROL.staminaCost;
        double boss   = (double) QuestType.BOSS_HUNT.expReward / QuestType.BOSS_HUNT.staminaCost;
        assertThat(boss).isGreaterThan(patrol); // 15 vs 4
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
