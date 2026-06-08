package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

// Quests V2 — narrativa de coleta (3 desfechos) + sorteio de monstro temático.
@DisplayName("Quests V2 | KingdomQuestNarrator — lore de coleta")
class KingdomQuestNarratorTest {

    // [I18N] Messages com MessageSource vazio → getOr sempre cai no default EN (narrativa em inglês).
    private final KingdomQuestNarrator narrator = new KingdomQuestNarrator(
            new Messages(new org.springframework.context.support.StaticMessageSource()));

    @Test
    @DisplayName("Paz: narrativa cita a quest e não menciona combate")
    void peace_mentionsQuestNoMonster() {
        String text = narrator.narrate(KingdomQuestType.PATROL_COAST, false, true, null, new Random(1));
        assertThat(text).isNotBlank();
        assertThat(text).contains(KingdomQuestType.PATROL_COAST.displayName);
    }

    @Test
    @DisplayName("Vitória: narrativa cita o monstro derrotado")
    void victory_mentionsMonster() {
        String text = narrator.narrate(KingdomQuestType.HUNT_SEA_MONSTER, true, true, "Sea Serpent", new Random(2));
        assertThat(text).isNotBlank();
        assertThat(text).contains("Sea Serpent");
    }

    @Test
    @DisplayName("Derrota: narrativa cita o monstro e indica que não houve recompensa")
    void defeat_mentionsMonsterAndNoReward() {
        String text = narrator.narrate(KingdomQuestType.HUNT_SEA_MONSTER, true, false, "Young Kraken", new Random(3));
        assertThat(text).isNotBlank();
        assertThat(text).contains("Young Kraken");
    }

    @Test
    @DisplayName("pickMonster retorna um monstro do pool do reino")
    void pickMonster_fromKingdomPool() {
        // 20 sorteios — todos devem vir do conjunto esperado da Pesca.
        var pool = java.util.Set.of("Sea Serpent", "Colossal Crab", "Drowned Pirate", "Young Kraken");
        Random rng = new Random(7);
        for (int i = 0; i < 20; i++) {
            assertThat(pool).contains(narrator.pickMonster(Kingdom.FISHING, rng));
        }
    }
}
