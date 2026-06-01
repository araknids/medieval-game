package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Guild — Sistema de Guildas")
class GuildIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("guild"));
    }

    // ── Sem guilda: GET /api/guild retorna inGuild:false ──
    @Test
    @DisplayName("GET /api/guild sem guilda → inGuild:false")
    void getGuild_noGuild_returnsNotInGuild() throws Exception {
        mockMvc.perform(get("/api/guild").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGuild").value(false));
    }

    // ── Criar guilda → inGuild:true, isLeader:true ──
    @Test
    @DisplayName("POST /api/guild → cria e retorna guilda com isLeader:true")
    void createGuild_success() throws Exception {
        mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Os Imortais", "description", "Guilda de teste"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGuild").value(true))
                .andExpect(jsonPath("$.name").value("Os Imortais"))
                .andExpect(jsonPath("$.isLeader").value(true))
                .andExpect(jsonPath("$.members", hasSize(1)));
    }

    // ── Nome duplicado → 400 ──
    @Test
    @DisplayName("POST /api/guild com nome duplicado → 400")
    void createGuild_duplicateName_returns400() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Duplicada", "description", ""))));

        String token2 = registerAndGetToken(uniqueUser("guild2dup"));
        mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Duplicada", "description", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Criar segunda guilda estando em uma → 400 ──
    @Test
    @DisplayName("POST /api/guild estando em guilda → 400")
    void createGuild_alreadyInGuild_returns400() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "PrimeiraGuild", "description", ""))));

        mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "SegundaGuild", "description", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── GET /api/guild/list → retorna array ──
    @Test
    @DisplayName("GET /api/guild/list → retorna lista de guildas")
    void listGuilds_returnsList() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "GuildList", "description", ""))));

        mockMvc.perform(get("/api/guild/list").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].level").isNumber());
    }

    // ── Outro player entra na guilda ──
    @Test
    @DisplayName("POST /api/guild/join/{id} → segundo player entra na guilda")
    void joinGuild_success() throws Exception {
        String createResp = mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "GuildJoin", "description", ""))))
                .andReturn().getResponse().getContentAsString();

        long guildId = objectMapper.readTree(createResp).get("id").asLong();
        String token2 = registerAndGetToken(uniqueUser("joinee"));

        mockMvc.perform(post("/api/guild/join/" + guildId)
                        .header("Authorization", bearer(token2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGuild").value(true))
                .andExpect(jsonPath("$.members", hasSize(2)));
    }

    // ── Entrar em guilda já estando em outra → 400 ──
    @Test
    @DisplayName("POST /api/guild/join já em guilda → 400")
    void joinGuild_alreadyInGuild_returns400() throws Exception {
        String createResp = mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "GuildJoin2", "description", ""))))
                .andReturn().getResponse().getContentAsString();

        long guildId = objectMapper.readTree(createResp).get("id").asLong();

        mockMvc.perform(post("/api/guild/join/" + guildId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Sair da guilda → inGuild:false ──
    @Test
    @DisplayName("POST /api/guild/leave → sai da guilda")
    void leaveGuild_success() throws Exception {
        String createResp = mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "GuildLeave", "description", ""))))
                .andReturn().getResponse().getContentAsString();

        long guildId = objectMapper.readTree(createResp).get("id").asLong();
        String token2 = registerAndGetToken(uniqueUser("leaver"));

        mockMvc.perform(post("/api/guild/join/" + guildId)
                .header("Authorization", bearer(token2)));

        mockMvc.perform(post("/api/guild/leave").header("Authorization", bearer(token2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGuild").value(false));
    }

    // ── Líder não pode sair com membros → 400 ──
    @Test
    @DisplayName("POST /api/guild/leave líder com membros → 400")
    void leaveGuild_leaderWithMembers_returns400() throws Exception {
        String createResp = mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "GuildLeaveLeader", "description", ""))))
                .andReturn().getResponse().getContentAsString();

        long guildId = objectMapper.readTree(createResp).get("id").asLong();
        String token2 = registerAndGetToken(uniqueUser("member2"));

        mockMvc.perform(post("/api/guild/join/" + guildId)
                .header("Authorization", bearer(token2)));

        mockMvc.perform(post("/api/guild/leave").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Expulsar membro ──
    @Test
    @DisplayName("POST /api/guild/kick/{id} → expulsa membro")
    void kickMember_success() throws Exception {
        String createResp = mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "GuildKick", "description", ""))))
                .andReturn().getResponse().getContentAsString();

        long guildId = objectMapper.readTree(createResp).get("id").asLong();
        String token2 = registerAndGetToken(uniqueUser("kicked"));

        mockMvc.perform(post("/api/guild/join/" + guildId)
                .header("Authorization", bearer(token2)));

        String memberResp = mockMvc.perform(get("/api/guild").header("Authorization", bearer(token2)))
                .andReturn().getResponse().getContentAsString();
        long memberId = objectMapper.readTree(memberResp).get("members").get(0).get("playerId").asLong();
        // encontra o id do membro que não é o líder
        var members = objectMapper.readTree(memberResp).get("members");
        long kickId = -1;
        for (var m : members) { if (!m.get("isLeader").asBoolean()) { kickId = m.get("playerId").asLong(); break; } }

        mockMvc.perform(post("/api/guild/kick/" + kickId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── Doar bronze para guilda ──
    @Test
    @DisplayName("POST /api/guild/donate → gold da guilda aumenta")
    void donate_success() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "GuildDonate", "description", ""))));

        mockMvc.perform(post("/api/guild/donate")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", 50))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guildGold").value(50));
    }

    // ── Dissolver guilda ──
    @Test
    @DisplayName("DELETE /api/guild → dissolve guilda")
    void disbandGuild_success() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "GuildDisband", "description", ""))));

        mockMvc.perform(delete("/api/guild").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inGuild").value(false));

        mockMvc.perform(get("/api/guild").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.inGuild").value(false));
    }

    // ── Não-líder não pode dissolver ──
    @Test
    @DisplayName("DELETE /api/guild por não-líder → 400")
    void disbandGuild_notLeader_returns400() throws Exception {
        String createResp = mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "GuildNotLeader", "description", ""))))
                .andReturn().getResponse().getContentAsString();

        long guildId = objectMapper.readTree(createResp).get("id").asLong();
        String token2 = registerAndGetToken(uniqueUser("notleader"));

        mockMvc.perform(post("/api/guild/join/" + guildId)
                .header("Authorization", bearer(token2)));

        mockMvc.perform(delete("/api/guild").header("Authorization", bearer(token2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
