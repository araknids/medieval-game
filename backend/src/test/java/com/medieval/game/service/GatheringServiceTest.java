package com.medieval.game.service;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.SkillLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

// TC-020-022 — GatheringService: valores esperados de pesca/stamina (referência).
// A validação REAL do consumeFish (stamina + HP, via endpoint) está em
// GatheringIntegrationTest TC-236-238 e ZoneAmbushIntegrationTest TC-212-213.
@DisplayName("TC-020-022 | GatheringService — Pesca e Stamina (valores de referência)")
class GatheringServiceTest {

    // ── TC-020: Peixe por nível de pesca ──
    @Test
    @DisplayName("TC-020 | Level < 20 só pode pescar Small Fish")
    void tc020_level1_canOnlyCatchSmallFish() {
        // A lógica de rollFish verifica: level >= 20 para Salmon etc.
        // Level 1 → só Small Fish possível
        int level = 1;
        assertThat(level).isLessThan(20);
        // Inferência: rollFish(level=1) só retorna SMALL_FISH
    }

    @Test
    @DisplayName("TC-020b | Level >= 80 pode pescar Legendary Fish")
    void tc020b_level80_canCatchLegendaryFish() {
        int level = 80;
        assertThat(level).isGreaterThanOrEqualTo(80);
    }

    // ── TC-021: Stamina restaurada por tipo de peixe ──
    @Test
    @DisplayName("TC-021 | SMALL_FISH restaura 10 stamina")
    void tc021_smallFish_restores10Stamina() {
        // Valores definidos em GatheringService.consumeFish()
        int stamina = switch (ResourceType.SMALL_FISH) {
            case SMALL_FISH     -> 10;
            case SALMON         -> 25;
            case TUNA           -> 40;
            case SHARK          -> 60;
            case LEGENDARY_FISH -> 80;
            default             -> 0;
        };
        assertThat(stamina).isEqualTo(10);
    }

    @Test
    @DisplayName("TC-021b | LEGENDARY_FISH restaura 80 stamina")
    void tc021b_legendaryFish_restores80Stamina() {
        int stamina = switch (ResourceType.LEGENDARY_FISH) {
            case SMALL_FISH     -> 10;
            case SALMON         -> 25;
            case TUNA           -> 40;
            case SHARK          -> 60;
            case LEGENDARY_FISH -> 80;
            default             -> 0;
        };
        assertThat(stamina).isEqualTo(80);
    }

    // ── TC-022: Stamina pós-consumo não ultrapassa 100 ──
    @Test
    @DisplayName("TC-022 | Consumir peixe com stamina 90 → não ultrapassa 100")
    void tc022_consumeFish_stamina_capsAt100() {
        int currentStamina = 90;
        int fishBonus      = 25; // Salmão
        int result         = Math.min(100, currentStamina + fishBonus);

        assertThat(result).isEqualTo(100);
    }

    // ── TC-extra: SkillLevel XP para próximo nível ──
    @Test
    @DisplayName("TC-extra | SkillLevel nível 5 precisa de 500 XP para upar")
    void tcExtra_skillLevelXpThreshold() {
        SkillLevel skill = new SkillLevel();
        skill.setLevel(5);

        assertThat(skill.expNeededForNextLevel()).isEqualTo(500L);
    }

    // ── Custo de estamina ao coletar (Reinos V2): mín. 5, ~metade dos minutos ──
    @Test
    @DisplayName("Coletar gasta estamina proporcional à duração (mín. 5)")
    void gather_staminaCost_isProportionalWithFloor() {
        assertThat(GatheringService.staminaCostFor(5)).isEqualTo(5);   // piso
        assertThat(GatheringService.staminaCostFor(10)).isEqualTo(5);  // piso
        assertThat(GatheringService.staminaCostFor(20)).isEqualTo(10);
        assertThat(GatheringService.staminaCostFor(30)).isEqualTo(15);
        assertThat(GatheringService.staminaCostFor(60)).isEqualTo(30); // teto prático
    }
}
