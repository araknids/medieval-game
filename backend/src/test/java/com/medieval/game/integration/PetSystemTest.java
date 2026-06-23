package com.medieval.game.integration;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.enums.PetType;
import com.medieval.game.enums.QuestStatus;
import com.medieval.game.model.KingdomActiveQuest;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.KingdomActiveQuestRepository;
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

// Pets: equip + bônus de HP + quest rara da Luna (pity/aparição). [PETS]
@DisplayName("Pets | Luna: equip, +10% HP, quest rara (pity/aparição)")
class PetSystemTest extends BaseIntegrationTest {

    @Autowired PetService         petService;
    @Autowired KingdomService     kingdomService;
    @Autowired PlayerRepository   playerRepository;
    @Autowired WarriorRepository  warriorRepository;
    @Autowired WarriorStatsService statsService;
    @Autowired KingdomActiveQuestRepository questRepo;

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

        int hpBefore = statsService.combatStats(p, warriorRepository.findByPlayer(p).orElseThrow()).toArray()[2];
        petService.grant(p, PetType.LUNA); // auto-equipa
        int hpAfter  = statsService.combatStats(player(), warriorRepository.findByPlayer(player()).orElseThrow()).toArray()[2];

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
        assertThat(statsService.combatStats(player(), warriorRepository.findByPlayer(player()).orElseThrow()).toArray()[2])
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
    @DisplayName("Gato equipado dá +AGI no combate")
    void cat_givesAgi() {
        Player p = player();
        Warrior w = warriorRepository.findByPlayer(p).orElseThrow();
        w.setAgility(10); warriorRepository.save(w);

        // [REBALANCE] o bônus do pet (PetType.dexBonus) alimenta a AGI → slot 4 do combatStats.
        int agiBefore = statsService.combatStats(p, warriorRepository.findByPlayer(p).orElseThrow()).toArray()[4];
        petService.grant(p, PetType.BANDIT_CAT); // auto-equipa
        int agiAfter  = statsService.combatStats(player(), warriorRepository.findByPlayer(player()).orElseThrow()).toArray()[4];
        assertThat(agiAfter - agiBefore).isEqualTo(PetType.BANDIT_CAT.dexBonus); // +6
    }

    // ── [LUNA_INTERRUPT] A Luna interrompe missões normais (substituiu a quest avulsa RESCUE_STRAY_DOG) ──

    /** Cria uma quest normal e força o estado LUNA_PENDING (a interrupção aleatória fica OFF nos testes). */
    private KingdomActiveQuest lunaPending(Player p, KingdomQuestType qt, String pendingOptionId) {
        KingdomActiveQuest q = kingdomService.startQuest(p, qt.kingdom, qt);
        q.setStatus(QuestStatus.LUNA_PENDING);
        q.setPendingOptionId(pendingOptionId);
        return questRepo.save(q);
    }

    private Player freshPlayer(String prefix) throws Exception {
        registerAndGetToken(uniqueUser(prefix));
        return playerRepository.findAll().stream()
                .filter(x -> x.getUsername().startsWith(prefix))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    @Test
    @DisplayName("A Luna fica fora da rotação normal de quests (6 por reino)")
    void luna_outOfRotation() {
        assertThat(kingdomService.allQuestsForKingdom(Kingdom.FISHING))
                .doesNotContain(KingdomQuestType.RESCUE_STRAY_DOG)
                .hasSize(6);
    }

    @Test
    @DisplayName("Ajudar o cãozinho: pity vira 1 (chance ínfima de já ganhar a Luna) + quest coletada sem recompensa")
    void lunaInterrupt_help_incrementsPity() throws Exception {
        Player p = freshPlayer("pethelp");
        KingdomActiveQuest q = lunaPending(p, KingdomQuestType.PATROL_COAST, "hail");

        kingdomService.resolveLunaHelp(p, q.getId());

        Player after = playerRepository.findById(p.getId()).orElseThrow();
        assertThat(questRepo.findById(q.getId()).orElseThrow().getStatus()).isEqualTo(QuestStatus.COLLECTED);
        boolean gotLuna = petService.owns(after, PetType.LUNA);
        assertThat(gotLuna || after.getPetPityAttempts() == 1).isTrue(); // ajudar = pity++ (ou Luna raríssima)
    }

    @Test
    @DisplayName("Terminar a missão: resolve a recompensa normal (escolha guardada) e coleta")
    void lunaInterrupt_ignore_resolvesReward() throws Exception {
        Player p = freshPlayer("petign");
        KingdomActiveQuest q = lunaPending(p, KingdomQuestType.PATROL_COAST, "hail"); // "hail" = pacífico → recompensa

        var res = kingdomService.resolveLunaIgnore(p, q.getId());

        assertThat(questRepo.findById(q.getId()).orElseThrow().getStatus()).isEqualTo(QuestStatus.COLLECTED);
        assertThat(res.bronzeEarned() > 0 || res.xpEarned() > 0).isTrue(); // recompensa preservada
        assertThat(res.lunaPending()).isFalse();
    }
}
