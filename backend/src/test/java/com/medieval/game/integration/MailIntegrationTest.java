package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Mail;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-134 to TC-141 — Mail System; TC-232-235 — Item overflow (bag-full → mail → claim)
@DisplayName("TC-134-141,232-235 | Mail System")
class MailIntegrationTest extends BaseIntegrationTest {

    @Autowired MailService      mailService;
    @Autowired InventoryService inventoryService;
    @Autowired PlayerRepository playerRepository;

    String senderToken;
    String recipientToken;
    String recipientWarriorName; // warrior names are public; usernames are private

    @BeforeEach
    void setup() throws Exception {
        String senderUser    = uniqueUser("sender");
        String recipientUser = uniqueUser("recip");
        recipientWarriorName = "Guerreiro " + recipientUser; // set by registerAndGetToken
        senderToken    = registerAndGetToken(senderUser);
        recipientToken = registerAndGetToken(recipientUser);
    }

    private Player recipientPlayer() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("recip"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
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
                                "recipientWarriorName", recipientWarriorName,
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
                                "recipientWarriorName", "Guerreiro " + selfUsername,
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
                                "recipientWarriorName", "UnknownWarrior_xyz_12345",
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
                                "recipientWarriorName", recipientWarriorName,
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
                        "recipientWarriorName", recipientWarriorName,
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
                                "recipientWarriorName", recipientWarriorName,
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
                                "recipientWarriorName", recipientWarriorName,
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

    // ── TC-232: sendItemMail cria carta com item anexado (hasItem) ──
    @Test
    @DisplayName("TC-232 | Item mail appears in inbox with hasItem=true")
    void tc232_itemMail_hasItem() throws Exception {
        Player recip = recipientPlayer();
        mailService.sendItemMail(recip, "Bag cheia teste.",
                "Anel de Teste", ItemType.RING, 5, 0, 0, 2, 0, "lore", "origin");

        mockMvc.perform(get("/api/mail/inbox").header("Authorization", bearer(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letters[0].hasItem").value(true))
                .andExpect(jsonPath("$.letters[0].itemName").value("Anel de Teste"));
    }

    // ── TC-233: claim-item adiciona o item à bag ──
    @Test
    @DisplayName("TC-233 | claim-item adds item to bag and marks collected")
    void tc233_claimItem_addsToBag() throws Exception {
        Player recip = recipientPlayer();
        Mail mail = mailService.sendItemMail(recip, "Drop.",
                "Espada Reivindicada", ItemType.WEAPON, 8, 0, 0, 3, 0, "lore", "origin");
        int bagBefore = inventoryService.bagSize(recip);

        mockMvc.perform(post("/api/mail/" + mail.getId() + "/claim-item")
                        .header("Authorization", bearer(recipientToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemName").value("Espada Reivindicada"));

        assertBagGrew(recip, bagBefore);
    }

    // ── TC-234: claim-item duas vezes → segunda falha (já coletado) ──
    @Test
    @DisplayName("TC-234 | claim-item twice → second returns 400")
    void tc234_claimItem_twice_fails() throws Exception {
        Player recip = recipientPlayer();
        Mail mail = mailService.sendItemMail(recip, "Drop.",
                "Elmo Único", ItemType.HELMET, 0, 4, 0, 2, 0, "lore", "origin");

        mockMvc.perform(post("/api/mail/" + mail.getId() + "/claim-item")
                        .header("Authorization", bearer(recipientToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/mail/" + mail.getId() + "/claim-item")
                        .header("Authorization", bearer(recipientToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("collected")));
    }

    // ── TC-235: claim-item com bag cheia → 400 ──
    @Test
    @DisplayName("TC-235 | claim-item with full bag → 400")
    void tc235_claimItem_bagFull_fails() throws Exception {
        Player recip = recipientPlayer();
        Mail mail = mailService.sendItemMail(recip, "Drop.",
                "Anel Extra", ItemType.RING, 1, 0, 0, 1, 0, "lore", "origin");

        // Fill bag to max (10 slots for non-VIP)
        int max = recip.getMaxInventorySlots();
        int current = inventoryService.bagSize(recip);
        for (int i = current; i < max; i++) {
            inventoryService.make(recip, "Filler" + i, ItemType.RING, 0, 0, 0, 1, 5);
        }

        mockMvc.perform(post("/api/mail/" + mail.getId() + "/claim-item")
                        .header("Authorization", bearer(recipientToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("full")));
    }

    private void assertBagGrew(Player p, int before) {
        org.assertj.core.api.Assertions.assertThat(inventoryService.bagSize(p)).isGreaterThan(before);
    }
}
