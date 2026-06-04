package com.medieval.game.integration;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.CombatPveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Reinos V2 / Fase 4 — Covil das Feras: caçada PvE repetível com drop de gold/materiais.
@DisplayName("Reinos V2 | Covil das Feras — caçada PvE")
class CovilRaidTest extends BaseIntegrationTest {

    @Autowired CombatPveService  combatPveService;
    @Autowired WarriorRepository warriorRepository;
    @Autowired PlayerRepository  playerRepository;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("covil"));
    }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("covil"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    @Test
    @DisplayName("Caçada vencida rende gold/XP escalando com level + Núcleo de Fera")
    void raid_win_givesGoldXpAndMaterial() {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setLevel(50);
        w.setAttack(500);   // esmaga o mob → vitória garantida
        w.setStrength(60);  // Combate V2: STR dá precisão (strBonus +3) — sem acerto não mata em 40 rounds
        w.setHealth(5000);
        w.setCurrentHpSnapshot(100);
        w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);

        CombatPveService.RaidResult r = combatPveService.raid(p);

        assertThat(r.won()).isTrue();
        assertThat(r.goldEarned()).isEqualTo(500);  // level 50 × 10
        assertThat(r.xpEarned()).isEqualTo(600);    // level 50 × 12
        assertThat(r.materials()).anyMatch(m -> m.type() == ResourceType.MONSTER_CORE);
        assertThat(r.log()).isNotEmpty();
    }

    @Test
    @DisplayName("Caçada só é permitida na Fortaleza Maldita (outro reino → 400)")
    void raid_onlyInCovil() throws Exception {
        mockMvc.perform(post("/api/world/FISHING/raid")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/world/COMBAT/raid → 200 com resultado")
    void raid_endpoint_ok() throws Exception {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setLevel(30); w.setAttack(400); w.setHealth(4000);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);

        mockMvc.perform(post("/api/world/COMBAT/raid")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.won").exists())
                .andExpect(jsonPath("$.beast").isNotEmpty());
    }
}
