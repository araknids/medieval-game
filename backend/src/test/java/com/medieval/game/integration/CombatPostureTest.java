package com.medieval.game.integration;

import com.medieval.game.enums.CombatPosture;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.WarriorStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Postura de combate: tradeoff ATK/DEF aplicado no combatStats + endpoint. [POSTURE]
@DisplayName("Combat Posture | tradeoff ATK/DEF (combatStats) + endpoint")
class CombatPostureTest extends BaseIntegrationTest {

    @Autowired PlayerRepository    playerRepository;
    @Autowired WarriorRepository   warriorRepository;
    @Autowired WarriorStatsService statsService;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("posture"));
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        // base limpa (sem gear/buff) e redonda → mults da postura dão valores exatos
        w.setAttack(100); w.setDefense(100); w.setStrength(0);
        w.setHealth(500); w.setDexterity(10); w.setLuck(5);
        warriorRepository.save(w);
    }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("posture"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    private int[] statsWith(CombatPosture posture) {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setCombatPosture(posture);
        warriorRepository.save(w);
        return statsService.combatStats(p, warriorRepository.findByPlayer(p).orElseThrow()).toArray();
    }

    @Test
    @DisplayName("BALANCED (default) → +5% ATK e +5% DEF")
    void balanced_smallBoost() {
        int[] s = statsWith(CombatPosture.BALANCED);
        assertThat(s[0]).isEqualTo(105); // 100 × 1.05
        assertThat(s[1]).isEqualTo(105);
    }

    @Test
    @DisplayName("OFFENSIVE → +20% ATK / −15% DEF")
    void offensive_tradeoff() {
        int[] s = statsWith(CombatPosture.OFFENSIVE);
        assertThat(s[0]).isEqualTo(120); // 100 × 1.20
        assertThat(s[1]).isEqualTo(85);  // 100 × 0.85
    }

    @Test
    @DisplayName("DEFENSIVE → −15% ATK / +20% DEF")
    void defensive_tradeoff() {
        int[] s = statsWith(CombatPosture.DEFENSIVE);
        assertThat(s[0]).isEqualTo(85);
        assertThat(s[1]).isEqualTo(120);
    }

    @Test
    @DisplayName("Postura só mexe em ATK/DEF (HP/dex/strBonus/luk intactos)")
    void onlyAtkDef() {
        int[] off = statsWith(CombatPosture.OFFENSIVE);
        int[] def = statsWith(CombatPosture.DEFENSIVE);
        assertThat(off[2]).isEqualTo(def[2]); // HP
        assertThat(off[3]).isEqualTo(def[3]); // dex/AC
        assertThat(off[4]).isEqualTo(def[4]); // strBonus
        assertThat(off[5]).isEqualTo(def[5]); // luk
    }

    @Test
    @DisplayName("POST /api/warrior/posture troca e persiste; GET reflete")
    void endpoint_setsPosture() throws Exception {
        mockMvc.perform(post("/api/warrior/posture/OFFENSIVE").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combatPosture").value("OFFENSIVE"));
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.combatPosture").value("OFFENSIVE"));
    }
}
