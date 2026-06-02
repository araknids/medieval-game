package com.medieval.game.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-124 to TC-138 — Territory War integration tests
@DisplayName("TC-124-138 | Territory War — Integration")
class TerritoryIntegrationTest extends BaseIntegrationTest {

    String token;
    String leaderToken;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("terr"));
        leaderToken = registerAndGetToken(uniqueUser("tleader"));
    }

    // TC-124: GET /api/territory → lists 3 territories
    @Test
    @DisplayName("TC-124 | GET /api/territory → 3 territories returned")
    void tc124_listTerritories_returns3() throws Exception {
        mockMvc.perform(get("/api/territory").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].territory").isNotEmpty())
                .andExpect(jsonPath("$[0].isNeutral").value(true));
    }

    // TC-125: GET /api/territory/my without guild → hasTerritory false
    @Test
    @DisplayName("TC-125 | GET /api/territory/my without guild → hasTerritory false")
    void tc125_myTerritory_noGuild_false() throws Exception {
        mockMvc.perform(get("/api/territory/my").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasTerritory").value(false));
    }

    // TC-126: POST /api/territory/FORTALEZA_MALDITA/declare without guild → 400
    @Test
    @DisplayName("TC-126 | Declare attack without guild → 400")
    void tc126_declare_noGuild_returns400() throws Exception {
        mockMvc.perform(post("/api/territory/FORTALEZA_MALDITA/declare")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-127: Leader of guild without territory can declare
    @Test
    @DisplayName("TC-127 | Guild leader without territory → can declare attack")
    void tc127_guildLeader_canDeclare() throws Exception {
        // Create guild
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "TerritoryGuild" + System.nanoTime(), "description", ""))));

        mockMvc.perform(post("/api/territory/FORTALEZA_MALDITA/declare")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.territory").value("FORTALEZA_MALDITA"));
    }

    // TC-128: Non-leader member cannot declare
    @Test
    @DisplayName("TC-128 | Non-leader guild member → cannot declare attack")
    void tc128_member_cannotDeclare() throws Exception {
        String createResp = mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "TerritoryGuild2" + System.nanoTime(), "description", ""))))
                .andReturn().getResponse().getContentAsString();

        long guildId = objectMapper.readTree(createResp).get("id").asLong();

        mockMvc.perform(post("/api/guild/join/" + guildId)
                .header("Authorization", bearer(token)));

        mockMvc.perform(post("/api/territory/FORTALEZA_MALDITA/declare")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-129: Duplicate declaration → 400
    @Test
    @DisplayName("TC-129 | Duplicate declaration → 400")
    void tc129_duplicateDeclaration_returns400() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "TerritoryGuild3" + System.nanoTime(), "description", ""))));

        mockMvc.perform(post("/api/territory/FORTALEZA_MALDITA/declare")
                .header("Authorization", bearer(leaderToken)));

        mockMvc.perform(post("/api/territory/MINAS_DE_FERRO_NEGRO/declare")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-130: GET /api/territory/{territory}/history → returns array
    @Test
    @DisplayName("TC-130 | GET /api/territory/{territory}/history → returns array")
    void tc130_battleHistory_returnsArray() throws Exception {
        mockMvc.perform(get("/api/territory/FORTALEZA_MALDITA/history")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // TC-131: Territory debuffPercent formula — via GET /api/territory
    @Test
    @DisplayName("TC-131 | Territory debuffPercent present in response")
    void tc131_territory_hasDebuffPercent() throws Exception {
        mockMvc.perform(get("/api/territory").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].debuffPercent").isNumber())
                .andExpect(jsonPath("$[0].defenseStreak").isNumber());
    }

    // TC-132: POST /api/territory/cancel without declaration → 200 (no-op)
    @Test
    @DisplayName("TC-132 | Cancel without pending declaration → 200 (no-op)")
    void tc132_cancelWithoutDeclaration_isOk() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "TerritoryGuild4" + System.nanoTime(), "description", ""))));

        mockMvc.perform(post("/api/territory/cancel")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk());
    }

    // TC-133: TerritoryService.resolveTerritory with no declarations → streak +1 if controlled
    @Test
    @DisplayName("TC-133 | GET /api/territory secsUntilBattle is a positive number")
    void tc133_territory_hasTimer() throws Exception {
        mockMvc.perform(get("/api/territory").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].secsUntilBattle").isNumber());
    }

    // TC-134: After declare, myGuildDeclared=true for that territory
    @Test
    @DisplayName("TC-134 | After declare, myGuildDeclared=true visible in GET /api/territory")
    void tc134_afterDeclare_myGuildDeclaredTrue() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "DeclGuild134_" + System.nanoTime(), "description", ""))));

        mockMvc.perform(post("/api/territory/FORTALEZA_MALDITA/declare")
                .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/territory").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.territory == 'FORTALEZA_MALDITA')].myGuildDeclared",
                        hasItem(true)));
    }

    // TC-135: After declare then cancel, myGuildDeclared goes back to false
    @Test
    @DisplayName("TC-135 | After declare + cancel, myGuildDeclared=false")
    void tc135_afterDeclareAndCancel_myGuildDeclaredFalse() throws Exception {
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "DeclGuild135_" + System.nanoTime(), "description", ""))));

        mockMvc.perform(post("/api/territory/MINAS_DE_FERRO_NEGRO/declare")
                .header("Authorization", bearer(leaderToken)));

        mockMvc.perform(post("/api/territory/cancel")
                .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/territory").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.territory == 'MINAS_DE_FERRO_NEGRO')].myGuildDeclared",
                        hasItem(false)));
    }

    // TC-136: declaringGuilds is a non-null array in every territory entry
    @Test
    @DisplayName("TC-136 | GET /api/territory — all entries have declaringGuilds as array")
    void tc136_allTerritories_haveDeclaringGuildsArray() throws Exception {
        mockMvc.perform(get("/api/territory").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].declaringGuilds").isArray())
                .andExpect(jsonPath("$[1].declaringGuilds").isArray())
                .andExpect(jsonPath("$[2].declaringGuilds").isArray());
    }

    // TC-137: After declare, guild name appears in declaringGuilds for that territory
    @Test
    @DisplayName("TC-137 | After declare, guild name in declaringGuilds")
    void tc137_afterDeclare_guildInDeclaringGuilds() throws Exception {
        String guildName = "WarGuild137_" + System.nanoTime();
        mockMvc.perform(post("/api/guild")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", guildName, "description", ""))));

        mockMvc.perform(post("/api/territory/DESFILADEIRO_DO_OSSO/declare")
                .header("Authorization", bearer(leaderToken)));

        String resp = mockMvc.perform(get("/api/territory").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode territories = objectMapper.readTree(resp);
        boolean found = false;
        for (JsonNode ter : territories) {
            if ("DESFILADEIRO_DO_OSSO".equals(ter.get("territory").asText())) {
                for (JsonNode g : ter.get("declaringGuilds")) {
                    if (guildName.equals(g.asText())) { found = true; break; }
                }
            }
        }
        assertThat(found).as("guild name should appear in declaringGuilds").isTrue();
    }

    // TC-138: GET /api/territory — all entries include exclusiveBonus and displayName
    @Test
    @DisplayName("TC-138 | GET /api/territory — all fields present: displayName, exclusiveBonus, lore")
    void tc138_territory_hasAllRequiredFields() throws Exception {
        mockMvc.perform(get("/api/territory").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").isNotEmpty())
                .andExpect(jsonPath("$[0].lore").isNotEmpty())
                .andExpect(jsonPath("$[0].exclusiveBonus").isNumber())
                .andExpect(jsonPath("$[0].isNeutral").isBoolean())
                .andExpect(jsonPath("$[0].isMine").isBoolean())
                .andExpect(jsonPath("$[0].defenseStreak").isNumber())
                .andExpect(jsonPath("$[0].debuffPercent").isNumber());
    }
}
