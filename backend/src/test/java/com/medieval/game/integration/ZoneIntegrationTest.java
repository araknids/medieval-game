package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-092 a TC-095 - Zonas de Expedicao
@DisplayName("TC-092-095 | Zone - Expedicao")
class ZoneIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("zone"));
    }

    // TC-092: GET /api/zones retorna lista de zonas
    @Test
    @DisplayName("TC-092 | GET /api/zones - lista zonas")
    void tc092_getZones_returnsList() throws Exception {
        mockMvc.perform(get("/api/zones")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))));
    }

    // TC-093: POST /api/zones/enter (SAFE, GATHERING/FISHING, 30 min) - expedicao criada
    @Test
    @DisplayName("TC-093 | POST /api/zones/enter SAFE GATHERING 30min - id e zone presentes")
    void tc093_enterZone_returnsActivity() throws Exception {
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.zone").value("SAFE"));
    }

    // [SEM_TIMER] TC-094 removido: não há mais 'busy' cruzado (trabalho não bloqueia zona).
    // Entrar numa zona com expedição pendurada auto-cancela a antiga — coberto por TC-097.

    // TC-095: POST enter + collect (instant-complete) - coleta resultado
    @Test
    @DisplayName("TC-095 | POST /api/zones/enter + collect (instant) - retorna resultado")
    void tc095_enterAndCollect_returnsResult() throws Exception {
        String enterResponse = mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long activityId = objectMapper.readTree(enterResponse).get("id").asLong();

        mockMvc.perform(post("/api/zones/" + activityId + "/collect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    // TC-097: entrar numa zona com expedição pendurada (IN_PROGRESS) auto-cancela a antiga e entra.
    // [SEM_TIMER] sem onMission, basta re-entrar — não precisa mais do antigo /api/warrior/free.
    @Test
    @DisplayName("TC-097 | Zone enter auto-cancela expedição pendurada e re-entra")
    void tc097_zoneEnter_autoCancels_orphanedExpedition() throws Exception {
        // 1. Entra na zona (cria ZoneActivity IN_PROGRESS, não coletada)
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk());

        // 2. Re-entra direto — o ZoneService auto-cancela a pendurada e cria a nova
        mockMvc.perform(post("/api/zones/enter")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zone").value("SAFE"));
    }
}
