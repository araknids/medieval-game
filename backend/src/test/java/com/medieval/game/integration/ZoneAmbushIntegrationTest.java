package com.medieval.game.integration;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.Zone;
import com.medieval.game.enums.ZoneActivityStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.ZoneActivity;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.repository.ZoneActivityRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-212 to TC-218 — Zone Ambush PvP integration tests
@DisplayName("TC-212-218 | Zone Ambush PvP — Integration")
class ZoneAmbushIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository         playerRepository;
    @Autowired WarriorRepository        warriorRepository;
    @Autowired ZoneActivityRepository   activityRepository;
    @Autowired GatheringService         gatheringService;
    @Autowired MailService              mailService;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("amb"));
    }

    private Player playerOf(String prefix) {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith(prefix))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .orElseThrow();
    }

    private Warrior warriorOf(Player p) {
        return warriorRepository.findByPlayer(p).orElseThrow();
    }

    // ── TC-212: Peixe de VIDA (Fênix) cura HP até o teto de 90% [REINOS_V2] ──
    @Test
    @DisplayName("TC-212 | Consume PHOENIX_FISH a 20% → HP capado em 90%")
    void tc212_phoenixFish_cappedAt90() throws Exception {
        Player player = playerOf("amb");
        Warrior w = warriorOf(player);
        w.setCurrentHpSnapshot(20);
        w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);
        gatheringService.addResource(player, ResourceType.PHOENIX_FISH, 1);

        mockMvc.perform(post("/api/gathering/consume/PHOENIX_FISH")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newHpPercent").value(90)); // 20 + 90, capado em 90
    }

    // ── TC-213: Peixe-Coral (vida) cura +15% HP quando abaixo do teto ──
    @Test
    @DisplayName("TC-213 | Consume CORAL_FISH a 30% → +15% HP")
    void tc213_coralFish_heals15pct() throws Exception {
        Player player = playerOf("amb");
        Warrior w = warriorOf(player);
        w.setCurrentHpSnapshot(30);
        w.setHpUpdatedAt(java.time.LocalDateTime.now());
        warriorRepository.save(w);
        gatheringService.addResource(player, ResourceType.CORAL_FISH, 1);

        mockMvc.perform(post("/api/gathering/consume/CORAL_FISH")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newHpPercent").value(greaterThanOrEqualTo(45)));
    }

    // ── TC-214: GET /api/zones/current expõe campos de emboscada ──
    @Test
    @DisplayName("TC-214 | GET /api/zones/current exposes ambush fields")
    void tc214_currentExposesAmbushFields() throws Exception {
        Player player = playerOf("amb");
        warriorOf(player); // ensure warrior exists
        mockMvc.perform(post("/api/zones/enter")
                .header("Authorization", bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/zones/current").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ambushPending").value(false))
                .andExpect(jsonPath("$.ambushCount").value(0));
    }

    // ── TC-215: Continue endpoint limpa ambushPending ──
    @Test
    @DisplayName("TC-215 | POST /api/zones/{id}/continue clears ambushPending")
    void tc215_continueClearsPending() throws Exception {
        Player player = playerOf("amb");
        warriorOf(player);
        String resp = mockMvc.perform(post("/api/zones/enter")
                .header("Authorization", bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"zone\":\"SAFE\",\"role\":\"GATHERING\",\"skillType\":\"FISHING\",\"durationMinutes\":30}"))
                .andReturn().getResponse().getContentAsString();
        long activityId = objectMapper.readTree(resp).get("id").asLong();

        // Simulate a pending ambush
        ZoneActivity act = activityRepository.findById(activityId).orElseThrow();
        act.setAmbushPending(true);
        act.setAmbushCount(1);
        activityRepository.save(act);

        mockMvc.perform(post("/api/zones/" + activityId + "/continue")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        ZoneActivity after = activityRepository.findById(activityId).orElseThrow();
        assertThat(after.isAmbushPending()).isFalse();
        assertThat(after.getAmbushCount()).isEqualTo(1); // count preserved
    }

    // ── TC-216: sendSystemMail aparece no inbox ──
    @Test
    @DisplayName("TC-216 | System ambush mail appears in target inbox")
    void tc216_systemMailInInbox() throws Exception {
        Player player = playerOf("amb");
        mailService.sendSystemMail(player, "⚔ You were ambushed by Someone and SURVIVED!");

        mockMvc.perform(get("/api/mail/inbox").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.letters[0].from").value("System"))
                .andExpect(jsonPath("$.letters[0].message").value(containsString("ambushed")));
    }

    // ── TC-217: Pool de oponentes — 2 players IN_PROGRESS na mesma zona ──
    @Test
    @DisplayName("TC-217 | Two players IN_PROGRESS → each is in the other's opponent pool")
    void tc217_opponentPool() throws Exception {
        // Player A (this test's token) enters PVP zone
        Player a = playerOf("amb");
        warriorOf(a);
        createInProgressActivity(a, Zone.PVP);

        // Player B
        registerAndGetToken(uniqueUser("amb"));
        Player b = playerOf("amb");
        createInProgressActivity(b, Zone.PVP);

        List<ZoneActivity> poolForA = activityRepository.findAllByZoneAndStatusAndPlayerNot(
                Zone.PVP, ZoneActivityStatus.IN_PROGRESS, a);
        List<ZoneActivity> poolForB = activityRepository.findAllByZoneAndStatusAndPlayerNot(
                Zone.PVP, ZoneActivityStatus.IN_PROGRESS, b);

        assertThat(poolForA).anyMatch(act -> act.getPlayer().getId().equals(b.getId()));
        assertThat(poolForB).anyMatch(act -> act.getPlayer().getId().equals(a.getId()));
        // self-exclusion
        assertThat(poolForA).noneMatch(act -> act.getPlayer().getId().equals(a.getId()));
    }

    // ── TC-218: Pool vazio — único player na zona ──
    @Test
    @DisplayName("TC-218 | Single player in zone → opponent pool is empty")
    void tc218_emptyPool() {
        Player a = playerOf("amb");
        warriorOf(a);
        createInProgressActivity(a, Zone.HIGH_RISK);

        List<ZoneActivity> pool = activityRepository.findAllByZoneAndStatusAndPlayerNot(
                Zone.HIGH_RISK, ZoneActivityStatus.IN_PROGRESS, a);
        assertThat(pool).isEmpty();
    }

    // Helper: create an IN_PROGRESS zone activity directly
    private void createInProgressActivity(Player p, Zone zone) {
        ZoneActivity act = new ZoneActivity();
        act.setPlayer(p);
        act.setZone(zone);
        act.setRole(com.medieval.game.enums.ActivityRole.GATHERING);
        act.setSkillType(com.medieval.game.enums.SkillType.FISHING);
        act.setDurationMinutes(60);
        act.setStartedAt(java.time.LocalDateTime.now());
        act.setEndsAt(java.time.LocalDateTime.now().plusHours(1));
        act.setStatus(ZoneActivityStatus.IN_PROGRESS);
        activityRepository.save(act);
    }
}
