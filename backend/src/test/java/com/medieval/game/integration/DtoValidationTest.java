package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// BL-5 (auditoria M8) — Bean Validation (@Valid) nos DTOs de Smithing/Zone/Mail/Guild.
// Verifica que payloads inválidos são rejeitados com 400 ANTES de tocar a regra de negócio.
@DisplayName("Auditoria BL-5 | Bean Validation nos DTOs (400 em payload inválido)")
class DtoValidationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("dtoval"));
    }

    private void expectBadRequest(String url, Map<String, Object> body) throws Exception {
        mockMvc.perform(post(url)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("Smithing refine com quantity=0 → 400")
    void refine_zeroQuantity_400() throws Exception {
        expectBadRequest("/api/smithing/refine", Map.of("oreType", "COPPER_ORE", "quantity", 0));
    }

    @Test
    @DisplayName("Smithing refine sem oreType → 400")
    void refine_nullOreType_400() throws Exception {
        // mapa que aceita valor null
        Map<String, Object> body = new HashMap<>();
        body.put("oreType", null);
        body.put("quantity", 5);
        expectBadRequest("/api/smithing/refine", body);
    }

    @Test
    @DisplayName("Guild create com nome vazio → 400")
    void createGuild_blankName_400() throws Exception {
        expectBadRequest("/api/guild", Map.of("name", "", "description", "x"));
    }

    @Test
    @DisplayName("Guild create com nome curto (<3) → 400")
    void createGuild_shortName_400() throws Exception {
        expectBadRequest("/api/guild", Map.of("name", "ab", "description", ""));
    }

    @Test
    @DisplayName("Guild donate com amount=0 → 400")
    void donate_zeroAmount_400() throws Exception {
        expectBadRequest("/api/guild/donate", Map.of("amount", 0));
    }

    @Test
    @DisplayName("Mail send com message vazia → 400")
    void mail_blankMessage_400() throws Exception {
        expectBadRequest("/api/mail/send", Map.of(
                "recipientWarriorName", "Guerreiro qualquer", "message", "", "goldAmount", 0));
    }

    @Test
    @DisplayName("Mail send com goldAmount negativo → 400")
    void mail_negativeGold_400() throws Exception {
        expectBadRequest("/api/mail/send", Map.of(
                "recipientWarriorName", "Guerreiro qualquer", "message", "oi", "goldAmount", -5));
    }

    @Test
    @DisplayName("Zone enter com durationMinutes abaixo do mínimo (2 < 5) → 400")
    void zone_shortDuration_400() throws Exception {
        expectBadRequest("/api/zones/enter", Map.of(
                "zone", "SAFE", "role", "GATHERING", "skillType", "FISHING", "durationMinutes", 2));
    }
}
