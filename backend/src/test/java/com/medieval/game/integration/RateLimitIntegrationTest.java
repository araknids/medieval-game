package com.medieval.game.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [LAUNCH_HARDENING] Prova que o {@link com.medieval.game.config.ApiRateLimitFilter} REALMENTE corta
 * volume (429) e corpo gigante (413) quando LIGADO. O resto da suíte roda com o throttle desligado
 * (dev/pgtest), então este teste o liga com limites minúsculos via @TestPropertySource (contexto próprio).
 */
@TestPropertySource(properties = {
        "app.ratelimit.enabled=true",
        "app.ratelimit.max-requests=5",
        "app.ratelimit.window-ms=10000",
        "app.ratelimit.max-body-bytes=1000"
})
class RateLimitIntegrationTest extends BaseIntegrationTest {

    /** Após 5 chamadas autenticadas na janela, a 6ª leva 429 (chave = player). */
    @Test
    void blocksFloodOnAuthenticatedApi() throws Exception {
        String token = registerAndGetToken(uniqueUser("rl"));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(status().is(429));
    }

    /** Corpo acima de max-body-bytes é rejeitado (413) antes de qualquer parse/rota. */
    @Test
    void rejectsOversizedBody() throws Exception {
        String big = "{\"username\":\"" + "a".repeat(2000) + "\",\"password\":\"x\"}";
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(big))
                .andExpect(status().is(413));
    }
}
