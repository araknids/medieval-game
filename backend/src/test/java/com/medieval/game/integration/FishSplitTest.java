package com.medieval.game.integration;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.SkillType;
import com.medieval.game.model.GatheringSession;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.GatheringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Reinos V2 / Fase 3 — pool de peixe depende do reino: Mar Abençoado = peixe de VIDA;
// Águas Calmas (FISHING) = peixe de ESTAMINA.
@DisplayName("Reinos V2 | Split de peixe por reino (estamina vs vida)")
class FishSplitTest extends BaseIntegrationTest {

    @Autowired GatheringService gatheringService;
    @Autowired PlayerRepository playerRepository;

    private static final Set<ResourceType> HP_FISH = Set.of(
            ResourceType.CORAL_FISH, ResourceType.ANGEL_FISH, ResourceType.SPIRIT_FISH,
            ResourceType.SACRED_FISH, ResourceType.PHOENIX_FISH);
    private static final Set<ResourceType> STAMINA_FISH = Set.of(
            ResourceType.SMALL_FISH, ResourceType.SALMON, ResourceType.TUNA,
            ResourceType.SHARK, ResourceType.LEGENDARY_FISH);

    Player player;

    @BeforeEach
    void setup() throws Exception {
        registerAndGetToken(uniqueUser("fishsplit"));
        player = playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("fishsplit"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    @Test
    @DisplayName("Mar Abençoado rende peixe de VIDA")
    void marAbencoado_yieldsHpFish() {
        // instant-complete (perfil dev) → sessão pronta na hora
        GatheringSession s = gatheringService.startGathering(player, SkillType.FISHING, 30, Kingdom.MAR_ABENCOADO);
        List<GatheringService.ResourceDrop> drops = gatheringService.collectGathering(player, s.getId()).drops();
        assertThat(drops).isNotEmpty();
        assertThat(drops).allMatch(d -> HP_FISH.contains(d.type()));
    }

    @Test
    @DisplayName("Águas Calmas (FISHING) rende peixe de ESTAMINA")
    void aguasCalmas_yieldsStaminaFish() {
        GatheringSession s = gatheringService.startGathering(player, SkillType.FISHING, 30, Kingdom.FISHING);
        List<GatheringService.ResourceDrop> drops = gatheringService.collectGathering(player, s.getId()).drops();
        assertThat(drops).isNotEmpty();
        assertThat(drops).allMatch(d -> STAMINA_FISH.contains(d.type()));
    }
}
