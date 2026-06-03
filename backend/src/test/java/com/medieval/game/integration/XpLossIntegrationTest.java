package com.medieval.game.integration;

import com.medieval.game.model.Warrior;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.WarriorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-203 to TC-208 — XP Loss + XP curve integration tests
@DisplayName("TC-203-208 | XP Loss + Exponential Curve")
class XpLossIntegrationTest extends BaseIntegrationTest {

    @Autowired WarriorRepository warriorRepository;
    @Autowired WarriorService    warriorService;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("xpl"));
    }

    private Warrior getWarrior() {
        return warriorRepository.findAll().stream()
                .filter(w -> w.getName().startsWith("Guerreiro xpl"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .orElseThrow();
    }

    // TC-203: loseXp reduces XP within level
    @Test
    @DisplayName("TC-203 | loseXp reduces XP within current level")
    void tc203_loseXp_reducesXpInLevel() {
        Warrior w = getWarrior();
        // Give some XP first
        warriorService.addExperience(w, 50);
        w = getWarrior(); // refresh
        long before = w.getExperience();
        int levelBefore = w.getLevel();

        warriorService.loseXp(w, 20);

        Warrior after = getWarrior();
        assertThat(after.getExperience()).isEqualTo(Math.max(0, before - 20));
        assertThat(after.getLevel()).isEqualTo(levelBefore); // no level drop
    }

    // TC-204: loseXp drops level when XP deficit exceeds current level's XP
    @Test
    @DisplayName("TC-204 | loseXp can drop a level when XP goes negative")
    void tc204_loseXp_dropsLevel() {
        Warrior w = getWarrior();
        assertThat(w.getLevel()).isEqualTo(1); // starts at level 1

        // Level up to level 2 first
        warriorService.addExperience(w, 100); // 100 = exact threshold for level 2
        w = getWarrior();
        assertThat(w.getLevel()).isEqualTo(2);
        assertThat(w.getExperience()).isEqualTo(0L); // just leveled up

        // Lose more XP than current level has → drop back to level 1
        warriorService.loseXp(w, 150); // level 1 threshold = 100, so can't cover 150 from 0

        Warrior after = getWarrior();
        assertThat(after.getLevel()).isEqualTo(1);
        assertThat(after.getExperience()).isGreaterThanOrEqualTo(0);
    }

    // TC-205: loseXp never drops below level 1
    @Test
    @DisplayName("TC-205 | loseXp cannot drop below level 1")
    void tc205_loseXp_floor_level1() {
        Warrior w = getWarrior();
        assertThat(w.getLevel()).isEqualTo(1);
        assertThat(w.getExperience()).isEqualTo(0L);

        // Trying to lose XP at level 1 with 0 XP
        warriorService.loseXp(w, 9999);

        Warrior after = getWarrior();
        assertThat(after.getLevel()).isEqualTo(1);
        assertThat(after.getExperience()).isEqualTo(0L);
    }

    // TC-206: XP formula level 1 → round(100 * 1^1.8) = 100
    @Test
    @DisplayName("TC-206 | XP formula at level 1 = 100")
    void tc206_xpFormula_level1() {
        Warrior w = getWarrior();
        assertThat(w.getLevel()).isEqualTo(1);
        assertThat(w.expNeededForNextLevel()).isEqualTo(100L);
    }

    // TC-207: levelUp grants 2 attribute points via API
    @Test
    @DisplayName("TC-207 | GET /api/warrior after level up → availablePoints increased by 2")
    void tc207_levelUp_2pointsPerLevel() throws Exception {
        String r1 = mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        int before = objectMapper.readTree(r1).get("availablePoints").asInt();

        Warrior w = getWarrior();
        warriorService.addExperience(w, 100); // level up from 1→2

        String r2 = mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        int after = objectMapper.readTree(r2).get("availablePoints").asInt();

        assertThat(after).isEqualTo(before + 2); // 2 points, not 5
    }

    // TC-208: GET /api/warrior includes armorClass (evasionChance field = 10 + DEX)
    @Test
    @DisplayName("TC-208 | GET /api/warrior → evasionChance = 10 + dexterity (=AC)")
    void tc208_armorClassInWarriorResponse() throws Exception {
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evasionChance").value(10)); // DEX=0 at start → AC=10
    }
}
