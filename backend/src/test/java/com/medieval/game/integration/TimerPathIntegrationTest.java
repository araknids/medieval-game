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

    // Sem timer: o trabalho é coletável na hora (collect logo após o start funciona).
    @Test
    @DisplayName("Trabalho é coletável na hora (sem timer)")
    void work_instantlyCollectable() throws Exception {
        String resp = mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workType\":\"TAVERN_HELPER\",\"hours\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long sessionId = objectMapper.readTree(resp).get("id").asLong();

        // sem espera: coleta imediata já funciona (gate é estamina, não tempo)
        mockMvc.perform(post("/api/work/" + sessionId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goldEarned").isNumber());
    }

    // A estamina É consumida ao iniciar o trabalho (horas×5) — a estamina é o gate.
    @Test
    @DisplayName("Stamina é consumida ao iniciar trabalho (não fica em 100)")
    void staminaConsumed() throws Exception {
        mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workType\":\"TAVERN_HELPER\",\"hours\":2}"))
                .andExpect(status().isOk());

        assertThat(player().getCalculatedStamina()).isLessThan(100);
    }

    // Sem timer: o farm de zona é instantâneo, então a estamina é o gate (antes era o timer).
    // Garante que entrar numa zona consome estamina (fecha o exploit de farm grátis). [SEM_TIMER]
    @Test
    @DisplayName("Entrar numa zona consome estamina (gate = estamina, sem timer)")
    void zoneEnterConsumesStamina() throws Exception {
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":240}"))
                .andExpect(status().isOk());

        assertThat(player().getCalculatedStamina()).isLessThan(100);
    }
}
