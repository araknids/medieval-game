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

    @Test
    @DisplayName("GET /api/world/FISHING/quests → nomes das quests localizam (en≠pt na mesma janela) [P2]")
    void questNameFollowsAcceptLanguage() throws Exception {
        // O endpoint devolve a rotação (2 quests da janela atual) — em vez de fixar uma quest, comparo
        // os displayNames EN vs PT da MESMA janela: têm que diferir (nomes traduzidos). Robusto à rotação.
        String enBody = mockMvc.perform(get("/api/world/FISHING/quests").header("Authorization", bearer(token))
                        .header("Accept-Language", "en")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String ptBody = mockMvc.perform(get("/api/world/FISHING/quests").header("Authorization", bearer(token))
                        .header("Accept-Language", "pt")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        java.util.List<String> enNames = com.jayway.jsonpath.JsonPath.read(enBody, "$[*].displayName");
        java.util.List<String> ptNames = com.jayway.jsonpath.JsonPath.read(ptBody, "$[*].displayName");
        org.assertj.core.api.Assertions.assertThat(enNames).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(ptNames).isNotEqualTo(enNames); // traduzidos
    }

    @Test
    @DisplayName("GET /api/tower/boss/1 → atmosfera do andar localiza (en vs pt) [P3]")
    void towerAtmosphereFollowsAcceptLanguage() throws Exception {
        mockMvc.perform(get("/api/tower/boss/1").header("Authorization", bearer(token))
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atmosphere", containsString("first hall")));

        mockMvc.perform(get("/api/tower/boss/1").header("Authorization", bearer(token))
                        .header("Accept-Language", "pt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atmosphere", containsString("primeiro salão")));
    }

    @Test
    @DisplayName("GET /api/achievements → descrição localiza (en vs pt) [P4]")
    void achievementDescFollowsAcceptLanguage() throws Exception {
        mockMvc.perform(get("/api/achievements").header("Authorization", bearer(token))
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.achievements[?(@.id=='LEVEL_10')].description", hasItem("Reach level 10.")));

        mockMvc.perform(get("/api/achievements").header("Authorization", bearer(token))
                        .header("Accept-Language", "pt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.achievements[?(@.id=='LEVEL_10')].description", hasItem("Alcance o nível 10.")));
    }

    @Test
    @DisplayName("GET /api/warrior/attributes → nome/efeito localizam (en vs pt) [P5]")
    void attributesFollowAcceptLanguage() throws Exception {
        mockMvc.perform(get("/api/warrior/attributes").header("Authorization", bearer(token))
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='STRENGTH')].displayName", hasItem("Strength (STR)")));

        mockMvc.perform(get("/api/warrior/attributes").header("Authorization", bearer(token))
                        .header("Accept-Language", "pt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='STRENGTH')].displayName", hasItem("Força (STR)")));
    }
}
