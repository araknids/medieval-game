package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** [INCURSAO] Fluxo da Incursão (Delve) ponta a ponta + guard do item carregado (runPending). */
class ExpeditionIntegrationTest extends BaseIntegrationTest {

    @Autowired WarriorRepository warriorRepository;
    @Autowired PlayerRepository  playerRepository;
    @Autowired InventoryService  inventoryService;

    /** Deixa o guerreiro forte o bastante p/ vencer todo nó de combate da run (loot determinístico no walk). */
    private void godMode(String username) {
        Player p = playerRepository.findByUsername(username).orElseThrow();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setAttack(800);
        w.setHealth(5000);
        w.setDefense(50);
        w.setCurrentHpSnapshot(100);
        warriorRepository.save(w);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(MvcResult r) throws Exception {
        return objectMapper.readValue(r.getResponse().getContentAsString(), Map.class);
    }

    @Test
    void startCreatesRunWithBranchingMap() throws Exception {
        String user = uniqueUser("delve");
        String token = registerAndGetToken(user);

        MvcResult r = mockMvc.perform(post("/api/expedition/start")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"KINGDOM\",\"kingdom\":\"FISHING\",\"tier\":1}"))
                .andExpect(status().isOk()).andReturn();

        Map<String, Object> state = parse(r);
        assertEquals(true, state.get("active"));
        assertEquals("IN_PROGRESS", state.get("status"));
        assertEquals(3, ((Number) state.get("depth")).intValue()); // tier 1 → 3 camadas
        List<?> layers = (List<?>) state.get("map");
        assertEquals(3, layers.size());

        // segunda start é rejeitada (uma run por vez)
        mockMvc.perform(post("/api/expedition/start")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"KINGDOM\",\"kingdom\":\"FISHING\",\"tier\":1}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @SuppressWarnings("unchecked")
    void walkRunToBossThenExtract() throws Exception {
        String user = uniqueUser("delve");
        String token = registerAndGetToken(user);
        godMode(user);

        MvcResult r = mockMvc.perform(post("/api/expedition/start")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"KINGDOM\",\"kingdom\":\"COMBAT\",\"tier\":1}"))
                .andExpect(status().isOk()).andReturn();
        Map<String, Object> state = parse(r);
        long runId = ((Number) state.get("id")).longValue();

        for (int guard = 0; guard < 40; guard++) {
            String statusStr = (String) state.get("status");
            if ("NODE_PENDING".equals(statusStr)) {
                // resolve o evento escolhendo a 1ª opção
                Map<String, Object> dialog = (Map<String, Object>) state.get("dialog");
                List<Map<String, Object>> options = (List<Map<String, Object>>) dialog.get("options");
                String optId = (String) options.get(0).get("id");
                MvcResult nr = mockMvc.perform(post("/api/expedition/" + runId + "/node")
                                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"optionId\":\"" + optId + "\"}"))
                        .andExpect(status().isOk()).andReturn();
                Map<String, Object> resp = parse(nr);
                assertNotEquals(Boolean.TRUE, resp.get("ko"), "god warrior não deveria morrer");
                state = (Map<String, Object>) resp.get("state");
                continue;
            }
            int currentLayer = ((Number) state.get("currentLayer")).intValue();
            int depth = ((Number) state.get("depth")).intValue();
            if (currentLayer >= depth) break; // chefe vencido → extrair

            // escolhe o 1º nó alcançável da camada atual
            List<Map<String, Object>> layers = (List<Map<String, Object>>) state.get("map");
            Map<String, Object> layer = layers.stream()
                    .filter(l -> ((Number) l.get("index")).intValue() == currentLayer).findFirst().orElseThrow();
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) layer.get("nodes");
            String nodeId = (String) nodes.get(0).get("id");

            MvcResult cr = mockMvc.perform(post("/api/expedition/" + runId + "/choose")
                            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nodeId\":\"" + nodeId + "\"}"))
                    .andExpect(status().isOk()).andReturn();
            Map<String, Object> resp = parse(cr);
            assertNotEquals(Boolean.TRUE, resp.get("ko"), "god warrior não deveria morrer");
            state = (Map<String, Object>) resp.get("state");
        }

        // extrai → COMPLETED, sem run ativa
        MvcResult er = mockMvc.perform(post("/api/expedition/" + runId + "/extract")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        assertEquals("COMPLETED", parse(er).get("status"));

        MvcResult cur = mockMvc.perform(get("/api/expedition/current").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        assertEquals(false, parse(cur).get("active"));
    }

    @Test
    void abandonAtSafeStopEndsRunNoKo() throws Exception {
        String user = uniqueUser("delve");
        String token = registerAndGetToken(user);

        MvcResult r = mockMvc.perform(post("/api/expedition/start")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"KINGDOM\",\"kingdom\":\"FISHING\",\"tier\":1}"))
                .andExpect(status().isOk()).andReturn();
        long runId = ((Number) parse(r).get("id")).longValue();

        mockMvc.perform(post("/api/expedition/" + runId + "/abandon")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        MvcResult cur = mockMvc.perform(get("/api/expedition/current").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        assertEquals(false, parse(cur).get("active"));
    }

    @Test
    void runPendingItemIsHiddenFromBagAndCannotBeEquipped() throws Exception {
        String user = uniqueUser("delve");
        registerAndGetToken(user);
        Player p = playerRepository.findByUsername(user).orElseThrow();

        int before = inventoryService.getInventory(p).size();
        InventoryItem carried = inventoryService.makeRunPending(p, "Sword of Steel", ItemType.WEAPON,
                5, 0, 0, 2, 100, 5, "carried", "Delve");
        assertTrue(carried.isRunPending());

        // não aparece na bag
        assertEquals(before, inventoryService.getInventory(p).size(), "item carregado não conta na bag");

        // não pode equipar enquanto carregado
        assertThrows(IllegalStateException.class, () -> inventoryService.equip(p, carried.getId()));

        // ao sacar (clearRunPending) vira item normal e aparece
        inventoryService.clearRunPending(carried);
        assertEquals(before + 1, inventoryService.getInventory(p).size());
    }
}
