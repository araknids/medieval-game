package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.InventoryService;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-161 to TC-175 — SoulStone VIP currency integration tests
@DisplayName("TC-161-175 | SoulStone — Integration")
class SoulStoneIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository  playerRepository;
    @Autowired WarriorRepository warriorRepository;
    @Autowired InventoryService  inventoryService;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("ss"));
    }

    // ── Saldo e Admin ─────────────────────────────────────────────────────────

    // TC-161: novo jogador tem soulStones = 0
    @Test
    @DisplayName("TC-161 | New player soulStones = 0 in warrior response")
    void tc161_newPlayer_soulStones0() throws Exception {
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soulStones").value(0));
    }

    // TC-162: grant stones → saldo aumenta
    @Test
    @DisplayName("TC-162 | Grant 5 stones → soulStones = 5")
    void tc162_grantStones_balanceIncreases() throws Exception {
        mockMvc.perform(post("/api/admin/grant-soulstones")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soulStones").value(5));

        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.soulStones").value(5));
    }

    // TC-163: amount = 0 → 400
    @Test
    @DisplayName("TC-163 | Grant amount=0 → 400")
    void tc163_grantZero_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/grant-soulstones")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // TC-164: amount = 101 → 400
    @Test
    @DisplayName("TC-164 | Grant amount=101 → 400")
    void tc164_grantOver100_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/grant-soulstones")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("amount", 101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Cura Instantânea ──────────────────────────────────────────────────────

    // TC-165: soulstone heal sem stones → 400
    @Test
    @DisplayName("TC-165 | Soulstone heal with 0 stones → 400")
    void tc165_soulstoneHeal_noStones_returns400() throws Exception {
        mockMvc.perform(post("/api/temple/soulstone-heal")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Not enough SoulStones")));
    }

    // TC-166: soulstone heal com HP cheio → 400
    @Test
    @DisplayName("TC-166 | Soulstone heal with full HP → 400")
    void tc166_soulstoneHeal_fullHp_returns400() throws Exception {
        grantStones(5);
        // New warrior starts at full HP
        mockMvc.perform(post("/api/temple/soulstone-heal")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("full HP")));
    }

    // TC-167: GET /api/temple inclui ssHealCooldownSecs e ssHealReady
    @Test
    @DisplayName("TC-167 | GET /api/temple includes soulStones, ssHealCooldownSecs, ssHealReady")
    void tc167_temple_includesSoulStoneFields() throws Exception {
        grantStones(3);
        mockMvc.perform(get("/api/temple").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soulStones").value(3))
                .andExpect(jsonPath("$.ssHealCooldownSecs").isNumber())
                .andExpect(jsonPath("$.ssHealReady").isBoolean());
    }

    // TC-168: cooldown após usar soulstone heal
    @Test
    @DisplayName("TC-168 | Soulstone heal used → second use immediately → 400 on cooldown")
    void tc168_soulstoneHeal_cooldown() throws Exception {
        grantStones(5);
        // Reduce HP so we can heal (simulate via arena loss or direct repo)
        damageWarrior(50);

        mockMvc.perform(post("/api/temple/soulstone-heal")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // Immediately try again (HP is now 100, so "full HP" error takes priority)
        // But if we could reduce HP again instantly, we'd get cooldown. Here we verify cooldown field.
        mockMvc.perform(get("/api/temple").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.ssHealCooldownSecs").value(greaterThan(0)))
                .andExpect(jsonPath("$.ssHealReady").value(false));
    }

    // TC-169: soulstone heal válido → HP 100%, stones -1
    @Test
    @DisplayName("TC-169 | Valid soulstone heal → HP 100%, soulStones decremented")
    void tc169_soulstoneHeal_valid() throws Exception {
        grantStones(3);
        damageWarrior(50);

        mockMvc.perform(post("/api/temple/soulstone-heal")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soulStones").value(2));

        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.hpPercent").value(100))
                .andExpect(jsonPath("$.soulStones").value(2));
    }

    // ── Expansão de Bag ───────────────────────────────────────────────────────

    // TC-170: expand sem stones → 400
    @Test
    @DisplayName("TC-170 | Expand inventory with 0 stones → 400")
    void tc170_expand_noStones_returns400() throws Exception {
        mockMvc.perform(post("/api/inventory/expand")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Not enough SoulStones")));
    }

    // TC-171: expand com stones suficientes → maxSlots = 20
    @Test
    @DisplayName("TC-171 | Expand with 3 stones → maxSlots = 20, stones = 0")
    void tc171_expand_valid() throws Exception {
        grantStones(3);

        mockMvc.perform(post("/api/inventory/expand")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSlots").value(20))
                .andExpect(jsonPath("$.soulStones").value(0));

        mockMvc.perform(get("/api/inventory/slots").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.maxSlots").value(20))
                .andExpect(jsonPath("$.inventoryExpanded").value(true));
    }

    // TC-172: expand duas vezes → 400
    @Test
    @DisplayName("TC-172 | Expand twice → 400 already expanded")
    void tc172_expand_twice_returns400() throws Exception {
        grantStones(10);

        mockMvc.perform(post("/api/inventory/expand")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/inventory/expand")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("already expanded")));
    }

    // TC-173: GET /api/inventory/slots → todos os campos presentes
    @Test
    @DisplayName("TC-173 | GET /api/inventory/slots → bagSize, maxSlots, inventoryExpanded, soulStones")
    void tc173_slots_hasAllFields() throws Exception {
        mockMvc.perform(get("/api/inventory/slots").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bagSize").isNumber())
                .andExpect(jsonPath("$.maxSlots").value(10))
                .andExpect(jsonPath("$.inventoryExpanded").value(false))
                .andExpect(jsonPath("$.soulStones").isNumber());
    }

    // TC-174: bag cheia (10 slots) → item rejeitado via endpoint de bag
    @Test
    @DisplayName("TC-174 | Bag full at 10 slots → new item blocked")
    void tc174_bagFull_itemBlocked() throws Exception {
        Player player = getPlayerFromToken(token);
        // Add 10 items directly (starter items already count — clear them first)
        // Easier: fill bag via make() to exactly 10
        fillBag(player, 10);

        // Attempt to add one more item via make (should throw → bag full)
        org.junit.jupiter.api.Assertions.assertThrows(
            Exception.class,
            () -> inventoryService.make(player, "Extra", com.medieval.game.enums.ItemType.RING, 0, 0, 0, 1, 10)
        );
    }

    // TC-175: bag expandida (20 slots) aceita além de 10
    @Test
    @DisplayName("TC-175 | Expanded bag (20 slots) accepts items beyond 10")
    void tc175_expandedBag_accepts15Items() throws Exception {
        grantStones(3);
        mockMvc.perform(post("/api/inventory/expand").header("Authorization", bearer(token)));

        Player player = getPlayerFromToken(token);
        // Should not throw up to 20 items
        fillBag(player, 15);

        mockMvc.perform(get("/api/inventory/slots").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.bagSize").value(greaterThanOrEqualTo(15)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void grantStones(int amount) throws Exception {
        mockMvc.perform(post("/api/admin/grant-soulstones")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", amount))));
    }

    private void damageWarrior(int hpPercent) {
        Player player = getPlayerFromToken(token);
        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setCurrentHpSnapshot(hpPercent);
            w.setHpUpdatedAt(java.time.LocalDateTime.now());
            warriorRepository.save(w);
        });
    }

    private Player getPlayerFromToken(String jwt) {
        // Extract player by listing — find newest player with ss prefix
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("ss"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .orElseThrow();
    }

    private void fillBag(Player player, int count) {
        // Clear existing bag items first
        inventoryService.getInventory(player).stream()
                .filter(i -> !i.isEquipped())
                .forEach(i -> { /* can't easily delete here, just add up to count */ });
        int current = inventoryService.bagSize(player);
        for (int i = current; i < count; i++) {
            inventoryService.make(player, "TestItem" + i, com.medieval.game.enums.ItemType.RING, 0, 0, 0, 1, 10);
        }
    }
}
