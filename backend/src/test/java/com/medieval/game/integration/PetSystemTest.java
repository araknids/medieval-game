package com.medieval.game.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.enums.PetType;
import com.medieval.game.model.KingdomActiveQuest;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.KingdomService;
import com.medieval.game.service.PetService;
import com.medieval.game.service.WarriorStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pets: equip + bônus de HP + quest rara da Luna (pity/aparição). [PETS]
@DisplayName("Pets | Luna: equip, +10% HP, quest rara (pity/aparição)")
class PetSystemTest extends BaseIntegrationTest {

    @Autowired PetService         petService;
    @Autowired KingdomService     kingdomService;
    @Autowired PlayerRepository   playerRepository;
    @Autowired WarriorRepository  warriorRepository;
    @Autowired WarriorStatsService statsService;

    String token;

    @BeforeEach
    void setup() throws Exception { token = registerAndGetToken(uniqueUser("pet")); }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("pet"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    // ── PetService: grant auto-equipa + ownership ──
    @Test
    @DisplayName("grant cria o pet e auto-equipa; owns reflete a posse")
    void grant_createsAndAutoEquips() {
        Player p = player();
        assertThat(petService.owns(p, PetType.LUNA)).isFalse();
        petService.grant(p, PetType.LUNA);
        assertThat(petService.owns(p, PetType.LUNA)).isTrue();
        assertThat(petService.list(p).stream().anyMatch(v -> v.type() == PetType.LUNA && v.equipped())).isTrue();
    }

    // ── Bônus de HP no combatStats ──
    @Test
    @DisplayName("Luna equipada dá +10% no HP de combate")
    void luna_gives10pctHp() {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setHealth(1000); w.setConstitution(0);
        warriorRepository.save(w);

        int hpBefore = statsService.combatStats(p, warriorRepository.findByPlayer(p).orElseThrow())[2];
        petService.grant(p, PetType.LUNA); // auto-equipa
        int hpAfter  = statsService.combatStats(player(), warriorRepository.findByPlayer(player()).orElseThrow())[2];

        assertThat(hpBefore).isEqualTo(1000);
        assertThat(hpAfter).isEqualTo(1100); // 1000 × 1.10
    }

    // ── equip / unequip ──
    @Test
    @DisplayName("unequip remove a Luna; equip volta a colocar")
    void equipUnequip() {
        Player p = player();
        petService.grant(p, PetType.LUNA);
        petService.unequip(player());
        assertThat(statsService.combatStats(player(), warriorRepository.findByPlayer(player()).orElseThrow())[2])
                .isEqualTo(warriorRepository.findByPlayer(player()).orElseThrow().getTotalBaseHealth()); // sem bônus
        petService.equip(player(), PetType.LUNA);
        assertThat(petService.list(player()).stream().anyMatch(v -> v.type() == PetType.LUNA && v.equipped())).isTrue();
    }

    // ── Gato (BANDIT_CAT): comprado no mercado VIP, dá +AGI ──
    @Test
    @DisplayName("Comprar o gato debita SoulStone e concede + equipa")
    void buyCat_deductsAndGrants() {
        Player p = player();
        p.setSoulStones(20); playerRepository.save(p);
        petService.buy(player(), PetType.BANDIT_CAT);
        Player after = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(after.getSoulStones()).isEqualTo(20 - PetType.BANDIT_CAT.soulStoneCost);
        assertThat(petService.owns(after, PetType.BANDIT_CAT)).isTrue();
    }

    @Test
    @DisplayName("Sem SoulStone suficiente → rejeita")
    void buyCat_insufficient_rejected() {
        Player p = player();
        p.setSoulStones(1); playerRepository.save(p);
        assertThatThrownBy(() -> petService.buy(player(), PetType.BANDIT_CAT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Luna não é comprável (vem da quest)")
    void buyLuna_notForSale() {
        Player p = player();
        p.setSoulStones(100); playerRepository.save(p);
        assertThatThrownBy(() -> petService.buy(player(), PetType.LUNA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Gato equipado dá +AGI (dex) no combate")
    void cat_givesAgi() {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setDexterity(10); warriorRepository.save(w);

        int dexBefore = statsService.combatStats(p, warriorRepository.findByPlayer(p).orElseThrow())[3];
        petService.grant(p, PetType.BANDIT_CAT); // auto-equipa
        int dexAfter  = statsService.combatStats(player(), warriorRepository.findByPlayer(player()).orElseThrow())[3];
        assertThat(dexAfter - dexBefore).isEqualTo(PetType.BANDIT_CAT.dexBonus); // +6
    }

    // ── isLunaWindow determinístico + Luna fora da rotação ──
    @Test
    @DisplayName("isLunaWindow é determinístico e a Luna fica fora da rotação normal")
    void lunaWindow_andRotation() {
        Player p = player();
        boolean a = kingdomService.isLunaWindow(p);
        boolean b = kingdomService.isLunaWindow(p);
        assertThat(a).isEqualTo(b); // determinístico

        assertThat(kingdomService.allQuestsForKingdom(Kingdom.FISHING))
                .doesNotContain(KingdomQuestType.RESCUE_STRAY_DOG)
                .hasSize(6);
    }

    // ── Quest da Luna: "leave" não mexe na pity ──
    @Test
    @DisplayName("leave não mexe na pity nem concede pet")
    void lunaQuest_leave_noPity() {
        Player p = player();
        KingdomActiveQuest q = kingdomService.startQuest(p, Kingdom.FISHING, KingdomQuestType.RESCUE_STRAY_DOG);
        kingdomService.collectQuest(player(), q.getId(), "leave");
        assertThat(player().getPetPityAttempts()).isZero();
        assertThat(petService.owns(player(), PetType.LUNA)).isFalse();
    }

    // ── help num player limpo: pity vira 1 (ou ganhou a Luna) ──
    @Test
    @DisplayName("help: a pity vira 1 (chance ínfima de já ganhar a Luna)")
    void lunaQuest_help_incrementsPity() throws Exception {
        registerAndGetToken(uniqueUser("pethelp"));
        Player p = playerRepository.findAll().stream()
                .filter(x -> x.getUsername().startsWith("pethelp"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();

        KingdomActiveQuest q = kingdomService.startQuest(p, Kingdom.FISHING, KingdomQuestType.RESCUE_STRAY_DOG);
        kingdomService.collectQuest(p, q.getId(), "help");

        Player after = playerRepository.findById(p.getId()).orElseThrow();
        boolean gotLuna = petService.owns(after, PetType.LUNA);
        // ou ganhou a Luna (raríssimo) ou a pity subiu pra 1
        assertThat(gotLuna || after.getPetPityAttempts() == 1).isTrue();
    }

    // ── Vitrine: a Luna aparece p/ um player em "janela da Luna" e não p/ os demais ──
    @Test
    @DisplayName("A quest da Luna aparece na vitrine numa janela da Luna")
    void lunaShowcase_appearsInLunaWindow() throws Exception {
        String lunaTok = null;
        for (int i = 0; i < 8 && lunaTok == null; i++) {
            String tk = registerAndGetToken(uniqueUser("petshow"));
            Player p = playerRepository.findAll().stream()
                    .filter(x -> x.getUsername().startsWith("petshow"))
                    .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
            if (kingdomService.isLunaWindow(p)) lunaTok = tk;
        }
        assertThat(lunaTok).as("achou um player em janela da Luna").isNotNull();

        JsonNode showcase = objectMapper.readTree(mockMvc.perform(get("/api/world/FISHING/quests")
                        .header("Authorization", bearer(lunaTok)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        boolean hasLuna = false;
        for (JsonNode q : showcase) if ("RESCUE_STRAY_DOG".equals(q.get("id").asText())) hasLuna = true;
        assertThat(hasLuna).isTrue();
    }
}
