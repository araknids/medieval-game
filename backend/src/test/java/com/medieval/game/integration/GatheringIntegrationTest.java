package com.medieval.game.integration;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.GatheringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-084 — skills; TC-236-238 — consumeFish real (stamina + HP).
// [UNIFICAÇÃO_ZONA] A coleta (start/collect) migrou pro /api/zones — os antigos
// TC-081/082/083/085 (gathering session) foram removidos junto com os endpoints.
@DisplayName("TC-084,236-238 | Gathering — skills e consumo")
class GatheringIntegrationTest extends BaseIntegrationTest {

    @Autowired GatheringService  gatheringService;
    @Autowired WarriorRepository warriorRepository;
    @Autowired PlayerRepository  playerRepository;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("gather"));
    }

    private Player gatherPlayer() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("gather"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    // ── TC-084: GET /api/gathering/skills → array com skillType e level ──
    @Test
    @DisplayName("TC-084 | GET /api/gathering/skills → array com skillType e level")
    void tc084_getSkills_returnsArray() throws Exception {
        mockMvc.perform(get("/api/gathering/skills")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].skillType").isNotEmpty())
                .andExpect(jsonPath("$[0].level").isNumber());
    }

    // ── TC-236: consumeFish real (SALMON) restaura stamina e HP ──
    @Test
    @DisplayName("TC-236 | POST /api/gathering/consume/SALMON → newStamina + newHpPercent")
    void tc236_consumeSalmon_restoresStaminaAndHp() throws Exception {
        Player player = gatherPlayer();
        // Reduce stamina and HP so the heal is observable
        player.setCurrentStamina(50);
        player.setStaminaUpdatedAt(java.time.LocalDateTime.now());
        playerRepository.save(player);
        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
        w.setCurrentHpSnapshot(30);
        w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);

        gatheringService.addResource(player, ResourceType.SALMON, 1);

        // Peixe de estamina restaura SÓ estamina; HP fica inalterado. [REINOS_V2]
        mockMvc.perform(post("/api/gathering/consume/SALMON")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStamina").value(58))      // 50 + 8 (Combate V2: restauro reduzido)
                .andExpect(jsonPath("$.newHpPercent").value(30));   // peixe de estamina não cura HP
    }

    // ── TC-237: consumeFish stamina não ultrapassa 100 ──
    @Test
    @DisplayName("TC-237 | consume with stamina 95 → caps at 100")
    void tc237_consumeFish_staminaCapsAt100() throws Exception {
        Player player = gatherPlayer();
        player.setCurrentStamina(95);
        player.setStaminaUpdatedAt(java.time.LocalDateTime.now());
        playerRepository.save(player);
        gatheringService.addResource(player, ResourceType.SALMON, 1); // +8 → would be 103, cap 100

        mockMvc.perform(post("/api/gathering/consume/SALMON")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newStamina").value(100));
    }

    // ── TC-238: consumir não-peixe → 400 ──
    @Test
    @DisplayName("TC-238 | consume a non-fish resource → 400")
    void tc238_consumeNonFish_returns400() throws Exception {
        Player player = gatherPlayer();
        gatheringService.addResource(player, ResourceType.IRON_ORE, 1);

        mockMvc.perform(post("/api/gathering/consume/IRON_ORE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }
}
