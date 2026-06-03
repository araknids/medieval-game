package com.medieval.game.integration;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// M9 — exercita o caminho com TIMER REAL (instant-complete=false), que o resto da
// suíte (perfil dev, instant-complete=true) não cobre. docs/AUDITORIA_CONSELHO.md
@TestPropertySource(properties = "app.dev.instant-complete=false")
@DisplayName("Auditoria M9 | Caminho com timer real (instant-complete=false)")
class TimerPathIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository playerRepository;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("timer"));
    }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("timer"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    // Quest iniciada NÃO está pronta na hora; coletar imediatamente é rejeitado.
    @Test
    @DisplayName("M9 | Quest com timer não pode ser coletada na hora")
    void m9_questNotInstantlyCollectable() throws Exception {
        String resp = mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToCollect").value(false))
                .andExpect(jsonPath("$.secondsRemaining").value(org.hamcrest.Matchers.greaterThan(0)))
                .andReturn().getResponse().getContentAsString();
        long questId = objectMapper.readTree(resp).get("id").asLong();

        // Coleta imediata → rejeitada (ainda em progresso)
        mockMvc.perform(post("/api/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // Sem instant-complete, a stamina É consumida ao iniciar a quest (PATROL = 10).
    @Test
    @DisplayName("M9 | Stamina é consumida ao iniciar quest (não fica em 100)")
    void m9_staminaConsumed() throws Exception {
        mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL\"}"))
                .andExpect(status().isOk());

        // PATROL custa 10 de stamina → < 100 (com instant-complete ficaria 100).
        assertThat(player().getCalculatedStamina()).isLessThan(100);
    }
}
