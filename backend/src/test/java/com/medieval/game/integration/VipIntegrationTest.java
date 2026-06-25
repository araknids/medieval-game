package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-176 to TC-192 — VIP Status integration tests
@DisplayName("TC-176-192 | VIP Status — Integration")
class VipIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository  playerRepository;
    @Autowired WarriorRepository warriorRepository;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("vip"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void grantStones(int amount) throws Exception {
        mockMvc.perform(post("/api/admin/grant-soulstones")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", amount))));
    }

    private void buyVip() throws Exception {
        grantStones(15);
        mockMvc.perform(post("/api/vip/buy").header("Authorization", bearer(token)));
    }

    private void damageWarrior() {
        playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("vip"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .ifPresent(p -> warriorRepository.findByPlayer(p).ifPresent(w -> {
                    w.setCurrentHpSnapshot(50);
                    w.setHpUpdatedAt(java.time.LocalDateTime.now());
                    warriorRepository.save(w);
                }));
    }

    // ── Compra VIP ────────────────────────────────────────────────────────────

    // TC-176: comprar VIP → isVip=true, vipExpiresAt ~30 dias
    @Test
    @DisplayName("TC-176 | Buy VIP → isVip=true in GET /api/vip/status")
    void tc176_buyVip_statusIsVip() throws Exception {
        buyVip();

        mockMvc.perform(get("/api/vip/status").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isVip").value(true))
                .andExpect(jsonPath("$.vipExpiresAt").isNotEmpty());
    }

    // TC-177: comprar VIP sem SS suficiente → 400
    @Test
    @DisplayName("TC-177 | Buy VIP with insufficient stones → 400")
    void tc177_buyVip_noStones_returns400() throws Exception {
        grantStones(10);
        mockMvc.perform(post("/api/vip/buy").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Not enough SoulStones")));
    }

    // TC-178: renovar VIP empilha dias
    @Test
    @DisplayName("TC-178 | Renew VIP → vipExpiresAt extends by 30 more days")
    void tc178_renewVip_stacksDays() throws Exception {
        buyVip();
        String firstExpiry = objectMapper.readTree(
                mockMvc.perform(get("/api/vip/status").header("Authorization", bearer(token)))
                        .andReturn().getResponse().getContentAsString()
        ).get("vipExpiresAt").asText();

        grantStones(15);
        mockMvc.perform(post("/api/vip/buy").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        String newExpiry = objectMapper.readTree(
                mockMvc.perform(get("/api/vip/status").header("Authorization", bearer(token)))
                        .andReturn().getResponse().getContentAsString()
        ).get("vipExpiresAt").asText();

        // New expiry must be later than first
        org.junit.jupiter.api.Assertions.assertTrue(newExpiry.compareTo(firstExpiry) > 0);
    }

    // TC-179: comprar VIP inclui bag 50 slots
    @Test
    @DisplayName("TC-179 | Buy VIP → bag expanded to 50 slots")
    void tc179_buyVip_bagExpanded() throws Exception {
        buyVip();
        mockMvc.perform(get("/api/inventory/slots").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSlots").value(50));
    }

    // TC-180: GET /api/vip/status → arenaFightsRemaining/arenaFightLimit presentes
    // [QUESTS_INTERATIVAS] instantQuestsRemaining removido (instant-start aposentado)
    @Test
    @DisplayName("TC-180 | GET /api/vip/status → has arena counters")
    void tc180_vipStatus_hasCounters() throws Exception {
        buyVip();
        mockMvc.perform(get("/api/vip/status").header("Authorization", bearer(token)))
                .andExpect(status().isOk())   // [ARENA_JANELA] VIP = 20/6h
                .andExpect(jsonPath("$.arenaFightsRemaining").value(20))
                .andExpect(jsonPath("$.arenaFightLimit").value(20));
    }

    // ── VIP Heal ─────────────────────────────────────────────────────────────

    // TC-181: VIP heal com HP < 100 → HP restaurado, sem custo de bronze
    @Test
    @DisplayName("TC-181 | VIP heal → HP 100%, bronze unchanged")
    void tc181_vipHeal_restoresHp() throws Exception {
        buyVip();
        damageWarrior();

        mockMvc.perform(post("/api/temple/vip-heal").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.hpPercent").value(100));
    }

    // TC-182: VIP heal sem VIP → 400
    @Test
    @DisplayName("TC-182 | VIP heal without VIP → 400")
    void tc182_vipHeal_noVip_returns400() throws Exception {
        mockMvc.perform(post("/api/temple/vip-heal").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("VIP required")));
    }

    // TC-183: VIP heal com HP cheio → 400
    @Test
    @DisplayName("TC-183 | VIP heal with full HP → 400")
    void tc183_vipHeal_fullHp_returns400() throws Exception {
        buyVip();
        // [ONBOARDING v3] novo guerreiro nasce a 80% → cura no Templo p/ ficar full antes do teste
        mockMvc.perform(post("/api/temple/heal").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/temple/vip-heal").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("full HP")));
    }

    // TC-184: GET /api/temple → isVip, vipHealCooldownSecs, vipHealReady presentes
    @Test
    @DisplayName("TC-184 | GET /api/temple → VIP fields present")
    void tc184_temple_hasVipFields() throws Exception {
        buyVip();
        mockMvc.perform(get("/api/temple").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isVip").value(true))
                .andExpect(jsonPath("$.vipHealCooldownSecs").isNumber())
                .andExpect(jsonPath("$.vipHealReady").isBoolean());
    }

    // [QUESTS_INTERATIVAS] TC-185..188 removidos: o instant-start VIP foi aposentado (as dailies viraram
    // interativas — exigem escolha). O perk de VIP virou "1× a mais por daily" (ver QuestInteractiveTest).

    // ── Arena Daily Limit ─────────────────────────────────────────────────────

    // TC-189: GET /api/vip/status free player → arenaFightLimit=10 [ARENA_JANELA] (10/6h)
    @Test
    @DisplayName("TC-189 | Free player → arenaFightLimit = 10")
    void tc189_freePlayer_arenaLimit10() throws Exception {
        mockMvc.perform(get("/api/vip/status").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arenaFightLimit").value(10));
    }

    // TC-190: VIP player → arenaFightLimit = 20 [ARENA_JANELA] (20/6h)
    @Test
    @DisplayName("TC-190 | VIP player → arenaFightLimit = 20")
    void tc190_vipPlayer_arenaLimit20() throws Exception {
        buyVip();
        mockMvc.perform(get("/api/vip/status").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arenaFightLimit").value(20))
                .andExpect(jsonPath("$.arenaFightsRemaining").value(20));
    }

    // ── Segundo Buff (VIP) ────────────────────────────────────────────────────

    // TC-191: VIP pode aplicar 2 buffs diferentes
    @Test
    @DisplayName("TC-191 | VIP can have 2 simultaneous buffs")
    void tc191_vip_twoBuffs() throws Exception {
        buyVip();
        // Apply first buff
        mockMvc.perform(post("/api/temple/buff/STRENGTH").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        // Apply second buff (different type)
        mockMvc.perform(post("/api/temple/buff/DEFENSE").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/temple").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.activeBuff").isNotEmpty())
                .andExpect(jsonPath("$.activeBuff2").isNotEmpty());
    }

    // TC-192: GET /api/warrior → isVip and arena fields present
    @Test
    @DisplayName("TC-192 | GET /api/warrior → isVip, arenaFightsToday, arenaFightLimit present")
    void tc192_warrior_hasVipFields() throws Exception {
        buyVip();
        mockMvc.perform(get("/api/warrior").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isVip").value(true))
                .andExpect(jsonPath("$.arenaFightsToday").isNumber())
                .andExpect(jsonPath("$.arenaFightLimit").value(20));   // [ARENA_JANELA] VIP = 20/6h
    }
}
