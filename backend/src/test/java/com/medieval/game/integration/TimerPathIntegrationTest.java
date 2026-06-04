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

// SEM_TIMER — com instant-complete=false (estamina REALMENTE cobrada), as atividades ainda são
// INSTANTÂNEAS (sem timer) e a estamina é o gate. Este é o comportamento ALVO de produção:
// chega, gasta estamina, coleta na hora; volta quando a estamina regenera.
@TestPropertySource(properties = "app.dev.instant-complete=false")
@DisplayName("Sem timer | atividade instantânea + estamina cobrada (instant-complete=false)")
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

    // Sem timer: a quest está pronta na hora e pode ser coletada imediatamente (mesmo flag off).
    @Test
    @DisplayName("Quest é coletável na hora (sem timer)")
    void quest_instantlyCollectable() throws Exception {
        String resp = mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToCollect").value(true))
                .andReturn().getResponse().getContentAsString();
        long questId = objectMapper.readTree(resp).get("id").asLong();

        mockMvc.perform(post("/api/quests/" + questId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    // A estamina É consumida ao iniciar a quest (PATROL = 10) — a estamina é o gate.
    @Test
    @DisplayName("Stamina é consumida ao iniciar quest (não fica em 100)")
    void staminaConsumed() throws Exception {
        mockMvc.perform(post("/api/quests/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questType\":\"PATROL\"}"))
                .andExpect(status().isOk());

        assertThat(player().getCalculatedStamina()).isLessThan(100);
    }
}
