package com.medieval.game.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-068 a TC-070 — Torre Infernal
@DisplayName("TC-068-070 | Tower — Torre Infernal")
class TowerIntegrationTest extends BaseIntegrationTest {

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("tower"));
    }

    // ── TC-068: GET /api/tower/current sem run ativa → active:false ──
    @Test
    @DisplayName("TC-068 | GET /api/tower/current sem run ativa → active:false")
    void tc068_getTowerCurrent_noRun_activeFalse() throws Exception {
        mockMvc.perform(get("/api/tower/current")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    // ── TC-069: POST /api/tower/enter + GET current → active:true com currentFloor ──
    @Test
    @DisplayName("TC-069 | POST /api/tower/enter → GET current → active:true e currentFloor")
    void tc069_enterTower_currentShowsActiveRun() throws Exception {
        mockMvc.perform(post("/api/tower/enter")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentFloor").isNumber());

        mockMvc.perform(get("/api/tower/current")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.currentFloor").isNumber());
    }

    // ── TC-070: POST /api/tower/enter com run já ativa → 400 ──
    // [SEM_TIMER] sem 'busy' cruzado; o guard que sobra é o próprio da torre (uma run por vez).
    @Test
    @DisplayName("TC-070 | POST /api/tower/enter com run já ativa → 400")
    void tc070_enterTower_whenAlreadyInRun_returns400() throws Exception {
        mockMvc.perform(post("/api/tower/enter")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/tower/enter")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── [TORRE_NARRATIVA] A escolha no topo (andar 50): matar/poupar o Rei Arka → título oculto ──
    @org.springframework.beans.factory.annotation.Autowired
    com.medieval.game.repository.PlayerRepository playerRepository;
    @org.springframework.beans.factory.annotation.Autowired
    com.medieval.game.service.AchievementService achievementService;
    @org.springframework.beans.factory.annotation.Autowired
    com.medieval.game.repository.TowerRunRepository towerRunRepository;

    // ── [TORRE_TRAVA] checkpoint acima do alcance (nível+10): enter rejeita SEM criar run; run presa (legado) é abandonada ──
    @Test
    @DisplayName("[TORRE_TRAVA] checkpoint fora do alcance → enter rejeita sem trancar; run presa é abandonada")
    void enter_beyondLevelLead_rejectsAndUnsticks() throws Exception {
        var p = playerRepository.findAll().stream()
                .filter(x -> x.getUsername().startsWith("tower"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
        // recém-criado ~Lv1 → alcance ~andar 11; checkpoint no 30 → startFloor 31 está fora do alcance
        p.setTowerBestFloor(30);
        playerRepository.save(p);

        // enter rejeita (400) e NÃO cria run
        mockMvc.perform(post("/api/tower/enter").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/tower/current").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.active").value(false));

        // simula uma run PRESA (legado: criada antes da trava migrar pro enter) num andar fora do alcance
        var stuck = new com.medieval.game.model.TowerRun();
        stuck.setPlayer(p);
        stuck.setCurrentFloor(31);
        stuck.setHighestFloor(30);
        towerRunRepository.save(stuck);
        // antes do fix isto trancaria pra sempre ("already inside"); agora o enter abandona a run presa e rejeita pela trava de nível
        mockMvc.perform(post("/api/tower/enter").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        // a run presa foi abandonada → segue sem run ativa (destravado)
        mockMvc.perform(get("/api/tower/current").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("Arka: só depois do andar 50; matar concede Regicide (oculto); só uma vez")
    void arkaChoice_grantsHiddenTitleOnce() throws Exception {
        var p = playerRepository.findAll().stream()
                .filter(x -> x.getUsername().startsWith("tower"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();

        // ainda não enfrentou o Rei (towerBestFloor < 50) → rejeita
        mockMvc.perform(post("/api/tower/arka").header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"spare\":false}"))
                .andExpect(status().isBadRequest());

        // chegou ao topo
        p.setTowerBestFloor(com.medieval.game.service.TowerFloors.maxFloor());
        playerRepository.save(p);

        // matar → Regicide
        mockMvc.perform(post("/api/tower/arka").header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"spare\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spared").value(false));
        org.assertj.core.api.Assertions.assertThat(
                achievementService.has(playerRepository.findById(p.getId()).orElseThrow(),
                        com.medieval.game.enums.Achievement.REGICIDE)).isTrue();

        // a escolha é definitiva → segunda vez rejeita
        mockMvc.perform(post("/api/tower/arka").header("Authorization", bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"spare\":true}"))
                .andExpect(status().isBadRequest());
    }
}
