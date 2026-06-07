package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// [I18N] Camada 2: o backend serve o CONTEÚDO (lore da Trial) no idioma do header Accept-Language,
// e /api/settings persiste a preferência. Prova a cadeia LocaleResolver → Messages → messages_*.properties.
@DisplayName("I18N | Backend locale-aware (Accept-Language + /api/settings)")
class BackendI18nTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("i18n"));
    }

    @Test
    @DisplayName("GET /api/settings → language=en (default) + supportedLanguages [en,pt]")
    void settingsDefaults() throws Exception {
        mockMvc.perform(get("/api/settings").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.supportedLanguages", hasItems("en", "pt")));
    }

    @Test
    @DisplayName("POST /api/settings/language → pt persiste; idioma inválido → 400")
    void setLanguagePersistsAndValidates() throws Exception {
        mockMvc.perform(post("/api/settings/language").header("Authorization", bearer(token))
                        .contentType("application/json").content("{\"language\":\"pt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("pt"));

        mockMvc.perform(get("/api/settings").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.language").value("pt"));

        mockMvc.perform(post("/api/settings/language").header("Authorization", bearer(token))
                        .contentType("application/json").content("{\"language\":\"fr\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/class → lore da Path Trial muda com Accept-Language (en vs pt)")
    void classLoreFollowsAcceptLanguage() throws Exception {
        // paths[0] = WARRIOR (info() = List.of(WARRIOR, ARCHER, MERCHANT))
        mockMvc.perform(get("/api/class").header("Authorization", bearer(token))
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths[0].intro", containsString("training grounds")));

        mockMvc.perform(get("/api/class").header("Authorization", bearer(token))
                        .header("Accept-Language", "pt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths[0].intro", containsString("campo de provas")));
    }
}
