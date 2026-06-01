package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-075 a TC-080 — Inventario e Loja
@DisplayName("TC-075-080 | Inventory & Shop")
class InventoryShopIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("inv"));
    }

    // ── TC-075: GET /api/inventory → lista itens do inventário ──
    @Test
    @DisplayName("TC-075 | GET /api/inventory → retorna lista (pode estar vazia)")
    void tc075_getInventory_returnsList() throws Exception {
        mockMvc.perform(get("/api/inventory")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── TC-076: GET /api/shop → retorna objeto com lista de itens ──
    @Test
    @DisplayName("TC-076 | GET /api/shop → $.items presente e merchantName presente")
    void tc076_getShop_returnsObject() throws Exception {
        mockMvc.perform(get("/api/shop")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.merchantName").isNotEmpty())
                .andExpect(jsonPath("$.items[0].name").isNotEmpty())
                .andExpect(jsonPath("$.items[0].price").isNumber());
    }

    // ── TC-077: POST /api/shop/buy/{id} com item invalido → 4xx ──
    @Test
    @DisplayName("TC-077 | POST /api/shop/buy/0 - shopItemId invalido → 4xx")
    void tc077_buyItem_invalidId_returns4xx() throws Exception {
        // ID 0 nunca existe na rotacao do mercador
        mockMvc.perform(post("/api/shop/buy/0")
                        .header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError());
    }

    // ── TC-078: GET /api/inventory → retorna lista de itens ──
    @Test
    @DisplayName("TC-078 | GET /api/inventory → array de itens (pode ser vazio)")
    void tc078_getInventory_returnsArray() throws Exception {
        mockMvc.perform(get("/api/inventory")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ── TC-079: POST /api/inventory/{id}/sell com item inexistente → 4xx ──
    @Test
    @DisplayName("TC-079 | POST /api/inventory/99999/sell sem item → 4xx")
    void tc079_sellItem_notFound_returns4xx() throws Exception {
        mockMvc.perform(post("/api/inventory/99999/sell")
                        .header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError());
    }

    // ── TC-080: POST /api/inventory/{id}/equip com item inexistente → 4xx ──
    @Test
    @DisplayName("TC-080 | POST /api/inventory/99999/equip sem item → 4xx")
    void tc080_equipItem_notFound_returns4xx() throws Exception {
        mockMvc.perform(post("/api/inventory/99999/equip")
                        .header("Authorization", bearer(token)))
                .andExpect(status().is4xxClientError());
    }
}
