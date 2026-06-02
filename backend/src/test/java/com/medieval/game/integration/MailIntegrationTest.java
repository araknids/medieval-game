package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-134 to TC-141 — Mail System
@DisplayName("TC-134-141 | Mail System")
class MailIntegrationTest extends BaseIntegrationTest {

    String senderToken;
    String recipientToken;
    String recipientUsername;

    @BeforeEach
    void setup() throws Exception {
        String senderUser = uniqueUser("sender");
        recipientUsername = uniqueUser("recip");
        senderToken    = registerAndGetToken(senderUser);
        recipientToken = registerAndGetToken(recipientUsername);
    }

    // TC-134: Inbox returns empty for new player
    @Test
    @DisplayName("TC-134 | GET /api/mail/inbox → empty for new player")
    void tc134_inbox_empty() throws Exception {
        mockMvc.perform(get("/api/mail/inbox").header("Authorization", bearer(senderToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letters").isArray())
                .andExpect(jsonPath("$.letters", hasSize(0)))
                .andExpect(jsonPath("$.unread").value(0));
    }

    // TC-135: Send letter → created, sender gold reduced
    @Test
    @DisplayName("TC-135 | POST /api/mail/send → letter sent, id returned")
    void tc135_sendLetter_success() throws Exception {
        mockMvc.perform(post("/api/mail/send")
                        .header("Authorization", bearer(senderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "recipientUsername", recipientUsername,
                                "message", "Hello warrior!",
                                "goldAmount", 0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // TC-136: Send to self → 400
    @Test
    @DisplayName("TC-136 | POST /api/mail/send to self → 400")
    void tc136_sendToSelf_returns400() throws Exception {
        String selfUsername = uniqueUser("self");
        String selfToken = registerAndGetToken(selfUsername);
        mockMvc.perform(post("/api/mail/send")
                        .header("Authorization", bearer(selfToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "recipientUsername", selfUsername,
                                "message", "Hi me",
                                "goldAmount", 0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-137: Send to non-existent user → 400
    @Test
    @DisplayName("TC-137 | POST /api/mail/send to unknown user → 400")
    void tc137_sendToUnknown_returns400() throws Exception {
        mockMvc.perform(post("/api/mail/send")
                        .header("Authorization", bearer(senderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "recipientUsername", "nobody_xyz_12345",
                                "message", "Hi!",
                                "goldAmount", 0
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("not found")));
    }

    // TC-138: Insufficient funds → 400
    @Test
    @DisplayName("TC-138 | POST /api/mail/send with gold > balance → 400")
    void tc138_insufficientFunds_returns400() throws Exception {
        // New player has 50 silver but needs 1000 gold fee (not realistic but test with large gold)
        mockMvc.perform(post("/api/mail/send")
                        .header("Authorization", bearer(senderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "recipientUsername", recipientUsername,
                                "message", "Big send",
                                "goldAmount", 999999
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Insufficient")));
    }

    // TC-139: Recipient sees letter in inbox
    @Test
    @DisplayName("TC-139 | Recipient GET /api/mail/inbox → letter present")
    void tc139_recipientSeesLetter() throws Exception {
        // Send a letter
        mockMvc.perform(post("/api/mail/send")
                .header("Authorization", bearer(senderToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "recipientUsername", recipientUsername,
                        "message", "Hello warrior!",
                        "goldAmount", 0
                ))));

        // Recipient checks inbox
        mockMvc.perform(get("/api/mail/inbox").header("Authorization", bearer(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letters", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.unread").value(greaterThan(0)))
                .andExpect(jsonPath("$.letters[0].from").isNotEmpty())
                .andExpect(jsonPath("$.letters[0].message").value("Hello warrior!"));
    }

    // TC-140: Collect bronze from letter (goldAmount is in bronze units)
    @Test
    @DisplayName("TC-140 | POST /api/mail/{id}/collect → bronze transferred to recipient")
    void tc140_collectGold_success() throws Exception {
        // Sender starts with 50 silver = 5000 bronze. Fee=100, attach 500 bronze → total 600.
        String sendResp = mockMvc.perform(post("/api/mail/send")
                        .header("Authorization", bearer(senderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "recipientUsername", recipientUsername,
                                "message", "Bronze for you!",
                                "goldAmount", 500  // 500 bronze attached
                        ))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long mailId = objectMapper.readTree(sendResp).get("id").asLong();

        mockMvc.perform(post("/api/mail/" + mailId + "/collect")
                        .header("Authorization", bearer(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goldAmount").value(500));
    }

    // TC-141: Delete letter removes it from inbox
    @Test
    @DisplayName("TC-141 | DELETE /api/mail/{id} → removed from inbox")
    void tc141_deleteLetter_removedFromInbox() throws Exception {
        String sendResp = mockMvc.perform(post("/api/mail/send")
                        .header("Authorization", bearer(senderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "recipientUsername", recipientUsername,
                                "message", "Delete me",
                                "goldAmount", 0
                        ))))
                .andReturn().getResponse().getContentAsString();

        long mailId = objectMapper.readTree(sendResp).get("id").asLong();

        mockMvc.perform(delete("/api/mail/" + mailId)
                        .header("Authorization", bearer(recipientToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/mail/inbox").header("Authorization", bearer(recipientToken)))
                .andExpect(jsonPath("$.letters", hasSize(0)));
    }
}
