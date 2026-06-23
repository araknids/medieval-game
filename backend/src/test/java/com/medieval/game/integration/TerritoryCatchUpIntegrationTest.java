package com.medieval.game.integration;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.model.TerritoryControl;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.TerritoryControlRepository;
import com.medieval.game.service.TerritoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

// A7 — cron de território idempotente + catch-up de ciclos perdidos. docs/AUDITORIA_CONSELHO.md
@DisplayName("Auditoria A7 | Resolução de território idempotente + catch-up")
class TerritoryCatchUpIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository           playerRepository;
    @Autowired GuildRepository            guildRepository;
    @Autowired TerritoryControlRepository controlRepo;
    @Autowired TerritoryService           territoryService;

    @BeforeEach
    void setup() throws Exception {
        registerAndGetToken(uniqueUser("catchup"));
    }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("catchup"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    private Guild newGuild(long gold) {
        Guild g = new Guild();
        g.setName("CatchUpGuild-" + System.nanoTime());
        g.setLeaderId(player().getId());
        g.setTreasuryBronze(gold);
        return guildRepository.save(g);
    }

    private TerritoryControl setupControl(Kingdom terr, Guild guild, int streak, long lastResolved) {
        territoryService.ensureInitialized();
        TerritoryControl c = controlRepo.findByTerritory(terr).orElseThrow();
        c.setControllingGuild(guild);
        c.setDefenseStreak(streak);
        c.setLastResolvedCycleId(lastResolved);
        return controlRepo.save(c);
    }

    // ── A7a: 2ª chamada para o mesmo ciclo é no-op (não recobra upkeep) ──
    @Test
    @DisplayName("A7a | resolver o mesmo ciclo 2× cobra upkeep só uma vez")
    void a7a_idempotent() {
        Guild g = newGuild(10_000);
        Kingdom terr = Kingdom.COMBAT;
        long current = territoryService.currentCycleId();
        setupControl(terr, g, 0, current - 1); // um ciclo devido

        territoryService.resolveDueCyclesForTerritory(terr, current);
        long goldAfter1 = guildRepository.findById(g.getId()).orElseThrow().getTreasuryBronze();
        assertThat(goldAfter1).isLessThan(10_000);               // cobrou 1×
        assertThat(controlRepo.findByTerritory(terr).orElseThrow().getLastResolvedCycleId())
                .isEqualTo(current);

        territoryService.resolveDueCyclesForTerritory(terr, current); // de novo
        long goldAfter2 = guildRepository.findById(g.getId()).orElseThrow().getTreasuryBronze();
        assertThat(goldAfter2).isEqualTo(goldAfter1);             // NÃO recobrou
    }

    // ── A7b: catch-up reprocessa os ciclos perdidos em downtime ──
    @Test
    @DisplayName("A7b | catch-up resolve os 3 ciclos perdidos de uma vez")
    void a7b_catchUpMissedCycles() {
        Guild g = newGuild(100_000);
        Kingdom terr = Kingdom.MINING;
        long current = territoryService.currentCycleId();
        setupControl(terr, g, 0, current - 3); // 3 ciclos devidos (current-2..current)

        territoryService.resolveDueCyclesForTerritory(terr, current);

        TerritoryControl after = controlRepo.findByTerritory(terr).orElseThrow();
        assertThat(after.getLastResolvedCycleId()).isEqualTo(current);
        assertThat(after.getDefenseStreak()).isEqualTo(3);       // +1 por ciclo sem ataque
        assertThat(guildRepository.findById(g.getId()).orElseThrow().getTreasuryBronze())
                .isLessThan(100_000);                            // 3 upkeeps cobrados
    }

    // ── A7c: primeira execução (lastResolved=0) só marca o ponto, sem reprocessar histórico ──
    @Test
    @DisplayName("A7c | lastResolved=0 não reprocessa histórico (só inicializa)")
    void a7c_firstBootNoReprocess() {
        Guild g = newGuild(10_000);
        Kingdom terr = Kingdom.FISHING;
        long current = territoryService.currentCycleId();
        setupControl(terr, g, 5, 0); // nunca resolvido

        territoryService.resolveDueCyclesForTerritory(terr, current);

        TerritoryControl after = controlRepo.findByTerritory(terr).orElseThrow();
        assertThat(after.getLastResolvedCycleId()).isEqualTo(current); // só inicializou
        assertThat(guildRepository.findById(g.getId()).orElseThrow().getTreasuryBronze())
                .isEqualTo(10_000);                              // upkeep NÃO cobrado
        assertThat(after.getDefenseStreak()).isEqualTo(5);       // inalterado
    }
}
