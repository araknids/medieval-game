package com.medieval.game.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Nível da guild é derivado do gold acumulado (doações) — sem level-up manual. [GUILD_LEVEL_GOLD]
@DisplayName("Guild Level | derivado do gold acumulado (doação)")
class GuildLevelGoldTest extends BaseIntegrationTest {

    @Autowired PlayerRepository playerRepository;

    String token;

    @BeforeEach
    void setup() throws Exception {
        String user = uniqueUser("glg");
        token = registerAndGetToken(user);
        mockMvc.perform(post("/api/guild").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"GLG_" + user + "\",\"description\":\"\"}"))
                .andExpect(status().isOk());
        // dá bronze pro líder doar bastante
        Player p = playerRepository.findAll().stream()
                .filter(x -> x.getUsername().startsWith("glg"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
        p.addBronzeAmount(1_000_000);
        playerRepository.save(p);
    }

    private JsonNode donate(long amount) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/guild/donate")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode guild() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/guild")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Doar abaixo do limiar não sobe o nível")
    void donateBelowThreshold_noLevelUp() throws Exception {
        JsonNode r = donate(9_999);
        assertThat(r.get("level").asInt()).isEqualTo(1);
        assertThat(r.get("leveledUp").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Cruzar o limiar (10k) sobe pra Lv2 e cresce o maxMembers")
    void crossThreshold_levelsUp() throws Exception {
        JsonNode r = donate(10_000);
        assertThat(r.get("level").asInt()).isEqualTo(2);
        assertThat(r.get("leveledUp").asBoolean()).isTrue();

        JsonNode g = guild();
        assertThat(g.get("level").asInt()).isEqualTo(2);
        assertThat(g.get("maxMembers").asInt()).isEqualTo(15);
        assertThat(g.get("lifetimeGold").asLong()).isEqualTo(10_000);
        assertThat(g.get("nextLevelGold").asLong()).isEqualTo(30_000);
        assertThat(g.get("goldToNextLevel").asLong()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("Endpoint de level-up manual não existe mais (404)")
    void manualLevelUp_gone() throws Exception {
        mockMvc.perform(post("/api/guild/levelup").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }
}
