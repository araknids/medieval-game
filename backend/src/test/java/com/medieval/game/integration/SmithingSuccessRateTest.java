package com.medieval.game.integration;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.SkillType;
import com.medieval.game.model.Player;
import com.medieval.game.model.SkillLevel;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.SkillLevelRepository;
import com.medieval.game.repository.SocketedGemRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.SmithingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Forja: success rate (craft/socket) + gate de joia + coleta escala por nível. [PROFISSAO_SUCCESS]
@DisplayName("Smithing | success rate (craft/socket) + gate + coleta escala")
class SmithingSuccessRateTest extends BaseIntegrationTest {

    @Autowired PlayerRepository        playerRepository;
    @Autowired SkillLevelRepository    skillRepository;
    @Autowired InventoryItemRepository itemRepo;
    @Autowired SocketedGemRepository   gemRepository;
    @Autowired GatheringService        gatheringService;
    @Autowired SmithingService         smithingService;

    String token;

    @BeforeEach
    void setup() throws Exception { token = registerAndGetToken(uniqueUser("srate")); }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("srate"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    private void setSmithing(Player p, int level) {
        SkillLevel s = gatheringService.getOrCreateSkill(p, SkillType.SMITHING);
        s.setLevel(level);
        skillRepository.save(s);
    }

    private void grantBronze(Player p, long amount) { p.addBronzeAmount(amount); playerRepository.save(p); }

    // ── Fórmulas (determinísticas) ──
    @Test
    @DisplayName("craftSuccessPct: 70% no nível da receita, +5%/nível, teto 100")
    void formula_craftSuccessPct() {
        var iron = SmithingService.CRAFT_RECIPES.stream()
                .filter(r -> r.id().equals("iron_sword")).findFirst().orElseThrow();
        int lvl = iron.smithingLevel(); // robusto ao rescale de tiers [BALANCE]
        assertThat(smithingService.craftSuccessPct(lvl, iron)).isEqualTo(70);     // 70% no nível exato
        assertThat(smithingService.craftSuccessPct(lvl + 6, iron)).isEqualTo(100); // +6 níveis → teto
        assertThat(smithingService.craftSuccessPct(100, iron)).isEqualTo(100);
    }

    @Test
    @DisplayName("socketSuccessPct: 50%+nível, −10/slot, piso 5, teto 100")
    void formula_socketSuccessPct() {
        assertThat(smithingService.socketSuccessPct(1, 0)).isEqualTo(51);
        assertThat(smithingService.socketSuccessPct(50, 0)).isEqualTo(100);
        assertThat(smithingService.socketSuccessPct(1, 2)).isEqualTo(31);
        assertThat(smithingService.socketSuccessPct(1, 6)).isEqualTo(5); // piso
    }

    // ── Craft happy path (Lv100 → 100%) ──
    @Test
    @DisplayName("Craft no nível máximo: 100% → item criado, materiais consumidos")
    void craft_highLevel_alwaysSucceeds() {
        Player p = player();
        setSmithing(p, 100);
        grantBronze(p, 100_000);
        gatheringService.addResource(p, ResourceType.IRON_BAR, 3);
        gatheringService.addResource(p, ResourceType.SCRAP, 100);   // [DESMONTAGEM] craft de arma consome Peças
        SmithingService.CraftResult r = smithingService.craftEquipment(player(), "iron_sword");
        assertThat(r.success()).isTrue();
        assertThat(r.successPct()).isEqualTo(100);
        assertThat(gatheringService.resourceQuantity(player(), ResourceType.IRON_BAR)).isZero();
    }

    // ── Falha preserva materiais (verificação determinística por contagem) ──
    @Test
    @DisplayName("Crafts a 70%: falhas NÃO consomem materiais (só sucessos)")
    void craft_failuresPreserveMaterials() {
        Player p = player();
        setSmithing(p, 12); // iron_sword Lv12 → 70% (nível exato da receita) [BALANCE]
        grantBronze(p, 1_000_000);
        gatheringService.addResource(p, ResourceType.IRON_BAR, 30);
        gatheringService.addResource(p, ResourceType.SCRAP, 100);   // [DESMONTAGEM] craft de arma consome Peças (só no sucesso)
        long initial = gatheringService.resourceQuantity(player(), ResourceType.IRON_BAR); // o que coube na bag

        int successes = 0, attempts = 0;
        while (gatheringService.resourceQuantity(player(), ResourceType.IRON_BAR) >= 3 && attempts < 20) {
            attempts++;
            if (smithingService.craftEquipment(player(), "iron_sword").success()) successes++;
        }
        // materiais restantes = inicial − 3×sucessos → falhas preservaram os materiais (não reduziram)
        long barsLeft = gatheringService.resourceQuantity(player(), ResourceType.IRON_BAR);
        assertThat(barsLeft).isEqualTo(initial - 3L * successes);
    }

    // ── Gate: craft abaixo do nível é rejeitado ──
    @Test
    @DisplayName("Craft abaixo do nível da receita → rejeitado")
    void craft_belowLevel_rejected() {
        Player p = player();
        setSmithing(p, 10); // iron_sword exige 12 [BALANCE]
        grantBronze(p, 100_000);
        gatheringService.addResource(p, ResourceType.IRON_BAR, 3);
        assertThatThrownBy(() -> smithingService.craftEquipment(player(), "iron_sword"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Socket happy path (Lv100 → 100%) ──
    @Test
    @DisplayName("Socket no nível máximo: 100% → joia encaixada e consumida")
    void socket_highLevel_succeeds() {
        Player p = player();
        setSmithing(p, 100);
        grantBronze(p, 100_000);
        gatheringService.addResource(p, ResourceType.IRON_BAR, 3);
        gatheringService.addResource(p, ResourceType.SCRAP, 100);   // [DESMONTAGEM] craft de arma + encaixe consomem Peças
        SmithingService.CraftResult c = smithingService.craftEquipment(player(), "iron_sword"); // 1 socket
        assertThat(c.success()).isTrue();
        assertThat(c.item()).isNotNull();
        Long itemId = c.item().getId();

        gatheringService.addResource(player(), ResourceType.RUBY, 1);
        SmithingService.SocketResult r = smithingService.socketGem(player(), itemId, ResourceType.RUBY);
        assertThat(r.success()).isTrue();
        assertThat(gemRepository.findAllByItem(itemRepo.findById(itemId).orElseThrow())).hasSize(1);
        assertThat(gatheringService.resourceQuantity(player(), ResourceType.RUBY)).isZero();
    }

    // ── Gate de criação de joia ──
    @Test
    @DisplayName("Criar joia abaixo do nível do fragmento → rejeitado")
    void gemCraft_belowLevel_rejected() {
        Player p = player();
        setSmithing(p, 10); // RUBY_FRAGMENT exige 20
        gatheringService.addResource(p, ResourceType.RUBY_FRAGMENT, 3);
        assertThatThrownBy(() -> smithingService.craftGem(player(), ResourceType.RUBY_FRAGMENT))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Coleta escala por nível (mineração é determinística) ──
    @Test
    @DisplayName("Mineração rende mais com nível alto (mesma duração)")
    void gathering_quantityScalesWithLevel() {
        long lowQty  = gatheringService.collectGatheringDropsOnly(SkillType.MINING, 1, 60)
                .stream().mapToLong(GatheringService.ResourceDrop::quantity).sum();
        long highQty = gatheringService.collectGatheringDropsOnly(SkillType.MINING, 100, 60)
                .stream().mapToLong(GatheringService.ResourceDrop::quantity).sum();
        assertThat(highQty).isGreaterThan(lowQty); // +level/25 → +4 no Lv100
    }
}
