package com.medieval.game.integration;

import com.medieval.game.enums.Meal;
import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.WarriorStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// COZINHA — cozinhar peixe → refeição → buff de combate (slot Bem Alimentado).
@DisplayName("Cozinha | cozinhar, comer e buff de combate")
class CookingIntegrationTest extends BaseIntegrationTest {

    @Autowired GatheringService    gatheringService;
    @Autowired WarriorStatsService statsService;
    @Autowired PlayerRepository    playerRepository;
    @Autowired WarriorRepository   warriorRepository;

    String token;
    Player player;

    @BeforeEach
    void setup() throws Exception {
        String user = uniqueUser("cook");
        token = registerAndGetToken(user);
        player = playerRepository.findByUsername(user).orElseThrow();
    }

    private void cook(String meal) throws Exception {
        mockMvc.perform(post("/api/cooking/cook").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"meal\":\"" + meal + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Cozinhar consome o peixe e adiciona a refeição; comer aplica o buff")
    void cookAndEat_appliesBuff() throws Exception {
        gatheringService.addResource(player, ResourceType.SALMON, 2);

        cook("SALMON_FILLET");
        // peixe consumido
        assertThat(gatheringService.getResources(player).stream()
                .noneMatch(r -> r.getResourceType() == ResourceType.SALMON)).isTrue();
        // refeição em estoque
        mockMvc.perform(get("/api/cooking/meals").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("SALMON_FILLET"))
                .andExpect(jsonPath("$[0].quantity").value(1));

        mockMvc.perform(post("/api/cooking/eat").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"meal\":\"SALMON_FILLET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mealBuff").value("SALMON_FILLET"));

        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
        assertThat(w.hasMealBuff()).isTrue();
        assertThat(w.getMealBuff()).isEqualTo(Meal.SALMON_FILLET);
    }

    @Test
    @DisplayName("O buff de refeição entra no combatStats (ATK/DEF)")
    void mealBuff_appliesInCombat() {
        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
        int[] before = statsService.combatStats(player, w);

        w.setMealBuff(Meal.SALMON_FILLET); // +10 ATK, +5 DEF
        w.setMealBuffExpiresAt(LocalDateTime.now().plusHours(1));
        warriorRepository.save(w);

        Warrior fresh = warriorRepository.findByPlayer(player).orElseThrow();
        int[] after = statsService.combatStats(player, fresh);

        assertThat(after[0] - before[0]).isEqualTo(10); // ATK
        assertThat(after[1] - before[1]).isEqualTo(5);  // DEF
    }

    @Test
    @DisplayName("Refeição some na derrota (clearBuff limpa o slot Bem Alimentado)")
    void mealBuff_clearedOnDefeat() {
        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
        w.setMealBuff(Meal.PHOENIX_ROAST);
        w.setMealBuffExpiresAt(LocalDateTime.now().plusHours(1));
        assertThat(w.hasMealBuff()).isTrue();

        w.clearBuff(); // chamado no defeat de Arena/Torre
        assertThat(w.hasMealBuff()).isFalse();
    }

    @Test
    @DisplayName("Cozinhar sem peixe suficiente → 400")
    void cook_withoutFish_400() throws Exception {
        mockMvc.perform(post("/api/cooking/cook").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"meal\":\"PHOENIX_ROAST\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Comer refeição que não tem → 400")
    void eat_withoutMeal_400() throws Exception {
        mockMvc.perform(post("/api/cooking/eat").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"meal\":\"CORAL_SOUP\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /recipes lista as 10 receitas com canCook")
    void recipes_listed() throws Exception {
        mockMvc.perform(get("/api/cooking/recipes").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(Meal.values().length))
                .andExpect(jsonPath("$[0].effect").isNotEmpty());
    }
}
