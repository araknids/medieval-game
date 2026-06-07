package com.medieval.game.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// [SERVIDORES] /api/server-info é público (a tela de login precisa antes de autenticar).
@DisplayName("Servers | GET /api/server-info público")
class ServerInfoTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Sem token retorna id/name/env do servidor")
    void serverInfo_isPublic() throws Exception {
        mockMvc.perform(get("/api/server-info")) // sem Authorization
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.env").value("dev")); // perfil de teste = dev (default)
    }
}
