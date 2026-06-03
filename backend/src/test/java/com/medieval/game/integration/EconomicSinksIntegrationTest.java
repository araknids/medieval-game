package com.medieval.game.integration;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.Territory;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.SmithingService;
import com.medieval.game.service.TempleService;
import com.medieval.game.service.TerritoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// TC-239 a TC-252 — Sinks econômicos: durabilidade, reparo, reforja, cura escalável, manutenção de território
@DisplayName("TC-239-252 | Economic Sinks — Durability, Repair, Reforge, Heal, Upkeep")
class EconomicSinksIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository            playerRepository;
    @Autowired WarriorRepository           warriorRepository;
    @Autowired InventoryItemRepository     inventoryRepository;
    @Autowired GuildRepository             guildRepository;
    @Autowired TerritoryControlRepository  controlRepo;
    @Autowired InventoryService            inventoryService;
    @Autowired SmithingService             smithingService;
    @Autowired TempleService               templeService;
    @Autowired TerritoryService            territoryService;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("sink"));
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

    private InventoryItem equippedItem(Player p, int rarity, int atk, int def, int hp, int durability) {
        InventoryItem i = inventoryService.make(p, "Test Blade", ItemType.WEAPON, atk, def, hp, rarity, 100);
        i.setEquipped(true);
        i.setDurability(durability);
        return inventoryRepository.save(i);
    }

    // ── TC-239: combate reduz durabilidade em 1-10 ──
    @Test
    @DisplayName("TC-239 | wearEquippedItems reduz durabilidade entre 1 e 10")
    void tc239_battleReducesDurability() {
        Player p = playerOf("sink");
        InventoryItem item = equippedItem(p, 2, 5, 0, 0, 100);

        inventoryService.wearEquippedItems(p);

        InventoryItem after = inventoryRepository.findById(item.getId()).orElseThrow();
        assertThat(after.getDurability()).isBetween(90, 99);
    }

    // ── TC-241: durabilidade nunca fica negativa ──
    @Test
    @DisplayName("TC-241 | Desgaste repetido não deixa a durabilidade negativa")
    void tc241_durabilityNeverNegative() {
        Player p = playerOf("sink");
        InventoryItem item = equippedItem(p, 2, 5, 0, 0, 3);

        for (int i = 0; i < 20; i++) inventoryService.wearEquippedItems(p);

        InventoryItem after = inventoryRepository.findById(item.getId()).orElseThrow();
        assertThat(after.getDurability()).isEqualTo(0);
    }

    // ── TC-242: reparo restaura durabilidade para 100 ──
    @Test
    @DisplayName("TC-242 | Repair restaura durabilidade para 100")
    void tc242_repairRestoresTo100() {
        Player p = playerOf("sink");
        p.addBronzeAmount(10_000);
        playerRepository.save(p);
        InventoryItem item = equippedItem(p, 2, 5, 0, 0, 40);

        smithingService.repairItem(p, item.getId());

        InventoryItem after = inventoryRepository.findById(item.getId()).orElseThrow();
        assertThat(after.getDurability()).isEqualTo(100);
    }

    // ── TC-243: custo de reparo = pontosPerdidos × raridade × 5 ──
    @Test
    @DisplayName("TC-243 | Custo de reparo = lostPoints × rarity × 5")
    void tc243_repairCostFormula() {
        Player p = playerOf("sink");
        p.addBronzeAmount(50_000);
        playerRepository.save(p);
        // rarity 4, durabilidade 50 → 50 pontos perdidos → 50×4×5 = 1000
        InventoryItem item = equippedItem(p, 4, 10, 0, 0, 50);

        assertThat(smithingService.repairCost(item)).isEqualTo(1000);

        long before = playerRepository.findById(p.getId()).orElseThrow().totalBronze();
        smithingService.repairItem(p, item.getId());
        long after = playerRepository.findById(p.getId()).orElseThrow().totalBronze();

        assertThat(before - after).isEqualTo(1000);
    }

    // ── TC-244: reparo sem bronze suficiente → 400 ──
    @Test
    @DisplayName("TC-244 | Repair sem bronze suficiente → 400")
    void tc244_repairInsufficientBronze() throws Exception {
        Player p = playerOf("sink");
        p.setBronze(0); p.setSilver(0); p.setGold(0); // zera o saldo
        playerRepository.save(p);
        InventoryItem item = equippedItem(p, 4, 10, 0, 0, 50); // custo 1000

        mockMvc.perform(post("/api/smithing/repair/" + item.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── TC-245: custo de reforja = raridade² × 200 ──
    @Test
    @DisplayName("TC-245 | Custo de reforja = rarity² × 200")
    void tc245_reforgeCostFormula() {
        Player p = playerOf("sink");
        p.addBronzeAmount(50_000);
        playerRepository.save(p);
        InventoryItem item = equippedItem(p, 4, 10, 0, 0, 100); // épico → 4²×200 = 3200

        assertThat(smithingService.reforgeCost(item)).isEqualTo(3200);

        long before = playerRepository.findById(p.getId()).orElseThrow().totalBronze();
        smithingService.reforgeItem(p, item.getId());
        long after = playerRepository.findById(p.getId()).orElseThrow().totalBronze();

        assertThat(before - after).isEqualTo(3200);
    }

    // ── TC-246: reforja re-rola stats mantendo a raridade ──
    @Test
    @DisplayName("TC-246 | Reforge mantém raridade e gera stats dentro do range")
    void tc246_reforgeKeepsRarity() {
        Player p = playerOf("sink");
        p.addBronzeAmount(50_000);
        playerRepository.save(p);
        InventoryItem item = equippedItem(p, 3, 9, 9, 36, 100); // raro

        smithingService.reforgeItem(p, item.getId());

        InventoryItem after = inventoryRepository.findById(item.getId()).orElseThrow();
        assertThat(after.getRarity()).isEqualTo(3);
        assertThat(after.getAttackBonus()).isBetween(0, 3 * 3);   // maxAtk = rarity*3
        assertThat(after.getDefenseBonus()).isBetween(0, 3 * 3);  // maxDef = rarity*3
        assertThat(after.getHealthBonus()).isBetween(0, 3 * 12);  // maxHp  = rarity*12
        // pelo menos um stat > 0 (garantia da geração)
        assertThat(after.getAttackBonus() + after.getDefenseBonus() + after.getHealthBonus()).isPositive();
    }

    // ── TC-247: reforja sem bronze suficiente → 400 ──
    @Test
    @DisplayName("TC-247 | Reforge sem bronze suficiente → 400")
    void tc247_reforgeInsufficientBronze() throws Exception {
        Player p = playerOf("sink");
        p.setBronze(0); p.setSilver(0); p.setGold(0);
        playerRepository.save(p);
        InventoryItem item = equippedItem(p, 4, 10, 0, 0, 100); // custo 3200

        mockMvc.perform(post("/api/smithing/reforge/" + item.getId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── TC-248: cura custa nível × 10 para nível > 10 ──
    @Test
    @DisplayName("TC-248 | Heal custa nível × 10 para nível > 10")
    void tc248_healCostScalesWithLevel() {
        Player p = playerOf("sink");
        p.addBronzeAmount(50_000);
        playerRepository.save(p);
        Warrior w = warriorOf(p);
        w.setLevel(50);
        w.setCurrentHpSnapshot(50);
        w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);

        assertThat(templeService.healCost(w)).isEqualTo(500);

        long before = playerRepository.findById(p.getId()).orElseThrow().totalBronze();
        templeService.heal(playerRepository.findById(p.getId()).orElseThrow());
        long after = playerRepository.findById(p.getId()).orElseThrow().totalBronze();

        assertThat(before - after).isEqualTo(500);
    }

    // ── TC-249: cura grátis para nível ≤ 10 ──
    @Test
    @DisplayName("TC-249 | Heal grátis para nível ≤ 10")
    void tc249_healFreeLowLevel() {
        Player p = playerOf("sink");
        Warrior w = warriorOf(p);
        w.setLevel(5);
        w.setCurrentHpSnapshot(50);
        w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);

        assertThat(templeService.healCost(w)).isZero();

        long before = playerRepository.findById(p.getId()).orElseThrow().totalBronze();
        templeService.heal(playerRepository.findById(p.getId()).orElseThrow());
        long after = playerRepository.findById(p.getId()).orElseThrow().totalBronze();

        assertThat(before).isEqualTo(after); // saldo inalterado
    }

    // ── TC-250 / TC-251: manutenção = 500 × (1 + streak×0.1), tesouro cobre → mantém ──
    @Test
    @DisplayName("TC-250/251 | Upkeep 650 (streak 3) debitado, território mantido")
    void tc250_251_upkeepPaidKeepsTerritory() {
        Player p = playerOf("sink");
        Guild guild = newGuild(p, "UpkeepGuild-" + p.getId(), 5000);

        Territory terr = Territory.FORTALEZA_MALDITA;
        TerritoryControl control = setupControl(terr, guild, 3);

        assertThat(territoryService.upkeepCost(control)).isEqualTo(650);

        territoryService.resolveTerritory(terr, territoryService.currentCycleId());

        Guild after = guildRepository.findById(guild.getId()).orElseThrow();
        TerritoryControl ctrlAfter = controlRepo.findByTerritory(terr).orElseThrow();
        assertThat(after.getGold()).isEqualTo(5000 - 650);
        assertThat(ctrlAfter.getControllingGuild()).isNotNull();
        assertThat(ctrlAfter.getControllingGuild().getId()).isEqualTo(guild.getId());
    }

    // ── TC-252: tesouro insuficiente → território volta a neutro, streak zera ──
    @Test
    @DisplayName("TC-252 | Tesouro insuficiente → território neutro, streak resetado")
    void tc252_upkeepUnpaidGoesNeutral() {
        Player p = playerOf("sink");
        Guild guild = newGuild(p, "BrokeGuild-" + p.getId(), 100); // < 650

        Territory terr = Territory.DESFILADEIRO_DO_OSSO;
        setupControl(terr, guild, 3);

        territoryService.resolveTerritory(terr, territoryService.currentCycleId());

        TerritoryControl ctrlAfter = controlRepo.findByTerritory(terr).orElseThrow();
        assertThat(ctrlAfter.getControllingGuild()).isNull(); // neutro
        assertThat(ctrlAfter.getDefenseStreak()).isZero();
        // tesouro intacto (não pôde pagar)
        assertThat(guildRepository.findById(guild.getId()).orElseThrow().getGold()).isEqualTo(100);
    }

    private Guild newGuild(Player leader, String name, long gold) {
        Guild g = new Guild();
        g.setName(name);
        g.setLeaderId(leader.getId());
        g.setGold(gold);
        return guildRepository.save(g);
    }

    private TerritoryControl setupControl(Territory terr, Guild guild, int streak) {
        territoryService.ensureInitialized();
        TerritoryControl control = controlRepo.findByTerritory(terr).orElseThrow();
        control.setControllingGuild(guild);
        control.setDefenseStreak(streak);
        control.setDominantSince(LocalDateTime.now());
        return controlRepo.save(control);
    }
}
