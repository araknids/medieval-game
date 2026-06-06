package com.medieval.game.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.TerritoryService;
import com.medieval.game.service.TerritoryService.Fighter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Guerra de Território: cap de 15 lutadores + auto-fill por frescor + endpoint de roster. [GUERRA_ROSTER]
@DisplayName("Guild War Roster | cap 15, auto-fill, endpoint")
class GuildWarRosterTest extends BaseIntegrationTest {

    @Autowired GuildRepository   guildRepository;
    @Autowired PlayerRepository  playerRepository;
    @Autowired WarriorRepository warriorRepository;
    @Autowired TerritoryService  territoryService;

    // ── Helpers: monta guild + membros direto no banco (rápido, sem registrar 20 contas) ──

    private Guild newGuild() {
        Guild g = new Guild();
        g.setName("WarGuild_" + uniqueUser("g"));
        g.setLeaderId(-1L); // líder fictício (não usado nos testes de buildFighters)
        return guildRepository.save(g);
    }

    private Player addMember(Guild g, int atk) {
        String uname = uniqueUser("warmember");
        Player p = new Player();
        p.setUsername(uname);
        p.setEmail(uname + "@test.com");
        p.setPasswordHash("x");
        p.setGuild(g);
        p = playerRepository.save(p);

        Warrior w = new Warrior();
        w.setName("W_" + uname);
        w.setWarriorClass(WarriorClass.WARRIOR);
        w.setPlayer(p);
        w.setAttack(atk); w.setDefense(10); w.setHealth(100);
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
        return p;
    }

    /** Cansa o warrior do membro: lutou o ciclo anterior com 5 stacks → entra a -50% no ciclo atual. */
    private void fatigue(Player member, long cycle) {
        Warrior w = warriorRepository.findByPlayer(member).orElseThrow();
        w.setWarLastCycleFought(cycle - 1);
        w.setWarFatigueStacks(5);
        warriorRepository.save(w);
    }

    // ── Cap de 15 ──
    @Test
    @DisplayName("buildFighters escala no máximo 15 (guild com 20 membros)")
    void buildFighters_capsAt15() {
        Guild g = newGuild();
        for (int i = 0; i < 20; i++) addMember(g, 50);
        long cycle = territoryService.currentCycleId();

        List<Fighter> fighters = territoryService.buildFighters(g, 0, cycle);
        assertThat(fighters).hasSize(15);
    }

    // ── Auto-fill prefere os não-cansados ──
    @Test
    @DisplayName("Sem roster, o auto-fill escolhe os 15 frescos e deixa os cansados de fora")
    void autoFill_prefersFresh() {
        Guild g = newGuild();
        long cycle = territoryService.currentCycleId();

        List<Long> fatiguedIds = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) { Player p = addMember(g, 50); fatigue(p, cycle); fatiguedIds.add(p.getId()); }
        List<Long> freshIds = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) freshIds.add(addMember(g, 50).getId());

        List<Long> picked = territoryService.buildFighters(g, 0, cycle)
                .stream().map(f -> f.playerId).collect(Collectors.toList());

        assertThat(picked).hasSize(15);
        assertThat(picked).containsExactlyInAnyOrderElementsOf(freshIds);
        assertThat(picked).doesNotContainAnyElementsOf(fatiguedIds);
    }

    // ── Pick explícito do líder entra mesmo cansado ──
    @Test
    @DisplayName("Pick explícito do roster é escalado mesmo cansado")
    void explicitRoster_includedEvenFatigued() {
        Guild g = newGuild();
        long cycle = territoryService.currentCycleId();

        Player pinned = addMember(g, 50);
        fatigue(pinned, cycle);
        pinned.setInWarRoster(true);
        playerRepository.save(pinned);
        for (int i = 0; i < 20; i++) addMember(g, 50); // 20 frescos não-marcados disputando as vagas

        List<Long> picked = territoryService.buildFighters(g, 0, cycle)
                .stream().map(f -> f.playerId).collect(Collectors.toList());

        assertThat(picked).hasSize(15);
        assertThat(picked).contains(pinned.getId());
    }

    // ── Cansaço debuffa os stats do Fighter ──
    @Test
    @DisplayName("Fighter cansado entra com stats reduzidos (-50%)")
    void fatigued_fighterHasReducedStats() {
        Guild g = newGuild();
        long cycle = territoryService.currentCycleId();
        Player p = addMember(g, 100); // atk base 100
        fatigue(p, cycle);            // -50%

        // combatStats aplica a postura BALANCED (+5%) → 100×1.05=105; cansaço −50% → 52. [POSTURE]
        Fighter f = territoryService.buildFighters(g, 0, cycle).get(0);
        assertThat(f.atk).isEqualTo(52); // (int)(105 × 0.50)
    }

    // ── Endpoint /roster ──
    @Test
    @DisplayName("POST /api/guild/roster: líder marca membro; GET reflete inWarRoster")
    void rosterEndpoint_leaderSetsFlags() throws Exception {
        String leaderTok = registerAndGetToken(uniqueUser("rl"));
        String name = "RG_" + uniqueUser("g");
        mockMvc.perform(post("/api/guild").header("Authorization", bearer(leaderTok))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isOk());

        long guildId = guildRepository.findByName(name).orElseThrow().getId();
        String memberTok = registerAndGetToken(uniqueUser("rm"));
        mockMvc.perform(post("/api/guild/join/" + guildId).header("Authorization", bearer(memberTok)))
                .andExpect(status().isOk());

        // descobre o playerId do membro (na lista de membros do líder)
        JsonNode detail = objectMapper.readTree(mockMvc.perform(get("/api/guild")
                        .header("Authorization", bearer(leaderTok)))
                .andReturn().getResponse().getContentAsString());
        long memberId = -1;
        for (JsonNode m : detail.get("members"))
            if (!m.get("isLeader").asBoolean()) memberId = m.get("playerId").asLong();
        assertThat(memberId).isPositive();

        // líder define o roster com o membro
        mockMvc.perform(post("/api/guild/roster").header("Authorization", bearer(leaderTok))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\":[" + memberId + "]}"))
                .andExpect(status().isOk());

        // GET reflete inWarRoster=true no membro
        JsonNode after = objectMapper.readTree(mockMvc.perform(get("/api/guild")
                        .header("Authorization", bearer(leaderTok)))
                .andReturn().getResponse().getContentAsString());
        boolean memberInRoster = false;
        for (JsonNode m : after.get("members"))
            if (m.get("playerId").asLong() == memberId) memberInRoster = m.get("inWarRoster").asBoolean();
        assertThat(memberInRoster).isTrue();

        // não-líder não pode mexer no roster
        mockMvc.perform(post("/api/guild/roster").header("Authorization", bearer(memberTok))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/guild/roster com >15 ids → 400 (validação)")
    void rosterEndpoint_tooMany_rejected() throws Exception {
        String leaderTok = registerAndGetToken(uniqueUser("rl"));
        String name = "RG_" + uniqueUser("g");
        mockMvc.perform(post("/api/guild").header("Authorization", bearer(leaderTok))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isOk());

        // 16 ids → estoura o @Size(max=15) antes mesmo de checar membership
        String ids = java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(Integer::toString).collect(Collectors.joining(","));
        mockMvc.perform(post("/api/guild/roster").header("Authorization", bearer(leaderTok))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest());
    }
}
