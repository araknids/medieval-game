package com.medieval.game.integration;

import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-055 a TC-058 — Guerreiro; TC-229-231 — Atributos d20 (INTELLECT, caps)
@DisplayName("TC-055-058,229-231 | Warrior — Stats e Atributos")
class WarriorIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository  playerRepository;
    @Autowired WarriorRepository warriorRepository;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("wtest"));
    }

    private Warrior currentWarrior() {
        Player p = playerRepository.findAll().stream()
                .filter(pl -> pl.getUsername().startsWith("wtest"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
        return warriorRepository.findByPlayer(p).orElseThrow();
    }

    // ── TC-055: GET /api/warrior retorna todos os campos esperados ──
    @Test
    @DisplayName("TC-055 | GET /api/warrior → campos hp, stamina, bronze, silver, gold presentes")
    void tc055_getWarrior_hasAllFields() throws Exception {
        mockMvc.perform(get("/api/warrior")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hpPercent").value(100))
                .andExpect(jsonPath("$.stamina").isNumber())
                .andExpect(jsonPath("$.bronze").isNumber())
                .andExpect(jsonPath("$.silver").isNumber())
                .andExpect(jsonPath("$.gold").isNumber())
                .andExpect(jsonPath("$.availablePoints").isNumber())
                .andExpect(jsonPath("$.evasionChance").isNumber()); // kept for backwards compat = armorClass
    }

    // ── TC-056: POST /api/warrior/attributes/STRENGTH sem pontos → 400 ──
    @Test
    @DisplayName("TC-056 | POST /api/warrior/attributes/STRENGTH sem pontos → 400")
    void tc056_spendPoint_noPoints_returns400() throws Exception {
        mockMvc.perform(post("/api/warrior/attributes/STRENGTH")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("point")));
    }

    // ── TC-057: GET /api/warrior sem token → 403 (Spring Security default) ──
    @Test
    @DisplayName("TC-057 | GET /api/warrior sem Authorization → 403")
    void tc057_getWarrior_noToken_forbidden() throws Exception {
        mockMvc.perform(get("/api/warrior"))
                .andExpect(status().is(403));
    }

    // [SEM_TIMER] TC-058 removido: o endpoint /api/warrior/free (destravar 'busy') foi aposentado
    // junto com o conceito de onMission — tudo é instantâneo, não há guerreiro pra destravar.

    // ── TC-229: INTELLECT é RESERVADO (S2/Mage) → não investível → 400 [INT_RESERVADO] ──
    @Test
    @DisplayName("TC-229 | POST /api/warrior/attributes/INTELLECT → 400 (reservado, sem efeito)")
    void tc229_intellectIsReserved() throws Exception {
        Warrior w = currentWarrior();
        w.setAvailablePoints(3);
        warriorRepository.save(w);

        mockMvc.perform(post("/api/warrior/attributes/INTELLECT")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("reserved")));
        // não consumiu ponto nem subiu o atributo
        Warrior after = currentWarrior();
        org.junit.jupiter.api.Assertions.assertEquals(0, after.getIntellect());
        org.junit.jupiter.api.Assertions.assertEquals(3, after.getAvailablePoints());
    }

    // ── TC-231: GET /api/warrior expõe armorClass, attackBonus, intellect ──
    @Test
    @DisplayName("TC-231 | GET /api/warrior → armorClass, attackBonus, intellect presentes")
    void tc231_warrior_hasD20Fields() throws Exception {
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.armorClass").value(10))   // DEX 0 → AC 10
                .andExpect(jsonPath("$.attackBonus").value(0))   // STR 0 → +0
                .andExpect(jsonPath("$.intellect").value(0));
    }
}
