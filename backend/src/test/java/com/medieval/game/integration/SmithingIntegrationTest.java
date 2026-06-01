package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-086 a TC-091 — Ferraria (Smithing)
@DisplayName("TC-086-091 | Smithing — Forja e Encaixe de Joias")
class SmithingIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("smith"));
    }

    // ── TC-086: GET /api/gathering/resources → lista recursos disponíveis ──
    @Test
    @DisplayName("TC-086 | GET /api/gathering/resources → lista recursos do inventário")
    void tc086_getResources_returnsList() throws Exception {
        mockMvc.perform(get("/api/gathering/resources")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── TC-087: GET /api/gathering/skills → SMITHING presente no array ──
    @Test
    @DisplayName("TC-087 | GET /api/gathering/skills → SMITHING presente")
    void tc087_getSkills_smithingPresent() throws Exception {
        mockMvc.perform(get("/api/gathering/skills")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.skillType=='SMITHING')]").exists());
    }

    // ── TC-088: GET /api/smithing/recipes → retorna objeto com gems, craft, refine ──
    @Test
    @DisplayName("TC-088 | GET /api/smithing/recipes → campos gems, craft e refine presentes")
    void tc088_getRecipes_hasAllSections() throws Exception {
        mockMvc.perform(get("/api/smithing/recipes")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gems").isArray())
                .andExpect(jsonPath("$.craft").isArray())
                .andExpect(jsonPath("$.refine").isArray());
    }

    // ── TC-089: POST /api/smithing/craft sem ingredientes → 400 ──
    @Test
    @DisplayName("TC-089 | POST /api/smithing/craft sem recursos → 400")
    void tc089_craft_withoutResources_returns400() throws Exception {
        mockMvc.perform(post("/api/smithing/craft")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("recipeId", "iron_sword"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── TC-090: POST /api/smithing/socket/{itemId}/{gemType} sem item → 4xx ──
    @Test
    @DisplayName("TC-090 | POST /api/smithing/socket/99999/RUBY → 4xx item não encontrado")
    void tc090_socket_withoutItem_returns4xx() throws Exception {
        mockMvc.perform(post("/api/smithing/socket/99999/RUBY")
                        .header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError());
    }

    // ── TC-091: POST /api/smithing/refine sem minério → 400 ──
    @Test
    @DisplayName("TC-091 | POST /api/smithing/refine sem minério → 400")
    void tc091_refine_withoutOre_returns400() throws Exception {
        mockMvc.perform(post("/api/smithing/refine")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oreType\":\"COPPER_ORE\",\"quantity\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
