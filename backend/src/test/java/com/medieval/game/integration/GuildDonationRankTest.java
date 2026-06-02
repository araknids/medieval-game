package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-121 to TC-123 — Guild Donation Rank
@DisplayName("TC-121-123 | Guild — Donation Rank")
class GuildDonationRankTest extends BaseIntegrationTest {

    String leaderToken;
    long guildId;

    @BeforeEach
    void setup() throws Exception {
        leaderToken = registerAndGetToken(uniqueUser("drank"));
        String resp = mockMvc.perform(post("/api/guild")
                        .header("Authorization", bearer(leaderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "DonkRank" + System.nanoTime(), "description", ""))))
                .andReturn().getResponse().getContentAsString();
        guildId = objectMapper.readTree(resp).get("id").asLong();
    }

    // TC-121: GET /api/guild returns donationRank array
    @Test
    @DisplayName("TC-121 | GET /api/guild → donationRank present as array")
    void tc121_guildDetail_hasDonationRank() throws Exception {
        mockMvc.perform(get("/api/guild").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.donationRank").isArray());
    }

    // TC-122: After donate → donationRank shows entry with correct amount
    @Test
    @DisplayName("TC-122 | Donate 50 bronze → donationRank[0].donatedBronze = 50")
    void tc122_donate_appearsInRank() throws Exception {
        mockMvc.perform(post("/api/guild/donate")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", 50))));

        mockMvc.perform(get("/api/guild").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.donationRank[0].donatedBronze").value(50))
                .andExpect(jsonPath("$.donationRank[0].isMe").value(true));
    }

    // TC-123: Cumulative donations — two donations sum correctly
    @Test
    @DisplayName("TC-123 | Two donations of 30 → donationRank[0].donatedBronze = 60")
    void tc123_donateTwice_sumsCumulative() throws Exception {
        mockMvc.perform(post("/api/guild/donate")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", 30))));

        mockMvc.perform(post("/api/guild/donate")
                .header("Authorization", bearer(leaderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", 30))));

        mockMvc.perform(get("/api/guild").header("Authorization", bearer(leaderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.donationRank[0].donatedBronze").value(60));
    }
}
