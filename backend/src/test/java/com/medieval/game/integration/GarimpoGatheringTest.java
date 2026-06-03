package com.medieval.game.integration;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.SkillType;
import com.medieval.game.service.GatheringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Reinos V2 / Fase 2 — Garimpo: fragmentos de joia vêm do Garimpo; mineração só minério.
@DisplayName("Reinos V2 | Garimpo dropa fragmentos; mineração não dropa mais")
class GarimpoGatheringTest extends BaseIntegrationTest {

    @Autowired GatheringService gatheringService;

    @Test
    @DisplayName("Garimpo nível alto rende fragmentos de joia")
    void garimpo_dropsFragments() {
        // muitas rodadas → estatisticamente cai fragmento
        List<GatheringService.ResourceDrop> drops =
                gatheringService.collectGatheringDropsOnly(SkillType.GARIMPO, 80, 600);
        assertThat(drops).isNotEmpty();
        assertThat(drops).allMatch(d -> d.type().category == ResourceType.ResourceCategory.FRAGMENT);
    }

    @Test
    @DisplayName("Mineração só rende minério (sem fragmentos)")
    void mining_noFragments() {
        for (int i = 0; i < 50; i++) {
            List<GatheringService.ResourceDrop> drops =
                    gatheringService.collectGatheringDropsOnly(SkillType.MINING, 80, 600);
            assertThat(drops).noneMatch(d -> d.type().category == ResourceType.ResourceCategory.FRAGMENT);
            assertThat(drops).anyMatch(d -> d.type().category == ResourceType.ResourceCategory.ORE);
        }
    }
}
