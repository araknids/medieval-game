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

    // [WORK_IDLE] Exceção ao sem-timer: com instant-complete=false o Trabalho virou IDLE em tempo real
    // (finishesAt = agora+horas), então NÃO é coletável na hora — coletar logo após o start é rejeitado.
    @Test
    @DisplayName("Trabalho idle: timer real → coleta imediata rejeitada (não pronto)")
    void work_idleNotInstantlyCollectable() throws Exception {
        String resp = mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workType\":\"TAVERN_HELPER\",\"hours\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToCollect").value(false))
                .andReturn().getResponse().getContentAsString();
        long sessionId = objectMapper.readTree(resp).get("id").asLong();

        // ainda trabalhando (timer real rodando) → collect rejeitado
        mockMvc.perform(post("/api/work/" + sessionId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    // [WORK_IDLE] Trabalho NÃO custa estamina (o gate é tempo + a trava de aventura, não estamina).
    @Test
    @DisplayName("Trabalho idle NÃO consome estamina (fica em 100)")
    void work_doesNotConsumeStamina() throws Exception {
        mockMvc.perform(post("/api/work/start")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workType\":\"TAVERN_HELPER\",\"hours\":2}"))
                .andExpect(status().isOk());

        assertThat(player().getCalculatedStamina()).isEqualTo(100);
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
