package com.medieval.game.integration;

import com.medieval.game.enums.Zone;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.ExpeditionService;
import com.medieval.game.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [PVP_FLAG] Raid PvP da Incursão de zona: flag/lock ao entrar em 🟡/🔴 + saque do flagged. */
class ExpeditionPvpRaidTest extends BaseIntegrationTest {

    @Autowired PlayerRepository  playerRepository;
    @Autowired ExpeditionService expeditionService;
    @Autowired InventoryService  inventoryService;

    @Test
    void raidStealsBronzeShieldsAndClearsVictimFlag() throws Exception {
        String atkUser = uniqueUser("raid_atk"); registerAndGetToken(atkUser);
        String vicUser = uniqueUser("raid_vic"); registerAndGetToken(vicUser);
        Player atk = playerRepository.findByUsername(atkUser).orElseThrow();
        Player vic = playerRepository.findByUsername(vicUser).orElseThrow();

        // vítima exposta (flagged HIGH_RISK, sem escudo) com bronze a perder
        vic.setBronze(1000);
        vic.setPvpFlaggedZone(Zone.HIGH_RISK);
        vic.setPvpFlaggedUntil(LocalDateTime.now().plusHours(1));
        vic.setPvpShieldUntil(null);
        playerRepository.save(vic);
        long vicBefore = vic.totalBronze();
        long atkBefore = atk.totalBronze();

        String loot = expeditionService.raidForTest(atk.getId(), vic.getId(), Zone.HIGH_RISK);
        assertThat(loot).isNotBlank();

        Player vic2 = playerRepository.findById(vic.getId()).orElseThrow();
        Player atk2 = playerRepository.findById(atk.getId()).orElseThrow();
        assertThat(vic2.totalBronze()).isLessThan(vicBefore);   // perdeu 15% do bronze
        assertThat(atk2.totalBronze()).isGreaterThan(atkBefore); // atacante ganhou metade
        assertThat(vic2.isPvpShielded()).isTrue();               // escudo pós-raid
        assertThat(vic2.isPvpFlagged()).isFalse();               // flag limpa
    }

    @Test
    void enteringRedZoneFlagsAndLocksItems() throws Exception {
        String user = uniqueUser("raid_flag");
        String token = registerAndGetToken(user);
        Player p = playerRepository.findByUsername(user).orElseThrow();
        assertThat(p.isPvpFlagged()).isFalse();

        mockMvc.perform(post("/api/expedition/start")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"ZONE\",\"kingdom\":\"FISHING\",\"zone\":\"HIGH_RISK\",\"skillType\":\"FISHING\",\"tier\":3}"))
                .andExpect(status().isOk());

        Player p2 = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(p2.isPvpFlagged()).isTrue();                 // entrou na 🔴 → exposto
        boolean anyLocked = inventoryService.getInventory(p2).stream().anyMatch(i -> i.isPvpLocked());
        assertThat(anyLocked).isTrue();                          // itens iniciais travados (saqueáveis)
    }
}
