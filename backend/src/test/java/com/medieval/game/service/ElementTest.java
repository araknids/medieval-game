package com.medieval.game.service;

import com.medieval.game.enums.Element;
import com.medieval.game.enums.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Roda RPS dos elementos + multiplicador de dano + mapeamento de essência. [ELEMENTOS]
@DisplayName("Element | roda RPS + multiplicador + essência")
class ElementTest {

    @Test
    @DisplayName("Roda: Fogo→Ar→Terra→Água→Fogo")
    void wheel() {
        assertThat(Element.FIRE.beats(Element.AIR)).isTrue();
        assertThat(Element.AIR.beats(Element.EARTH)).isTrue();
        assertThat(Element.EARTH.beats(Element.WATER)).isTrue();
        assertThat(Element.WATER.beats(Element.FIRE)).isTrue();
        // não vence o oposto/o que o vence
        assertThat(Element.FIRE.beats(Element.WATER)).isFalse(); // Água vence Fogo
        assertThat(Element.FIRE.beats(Element.EARTH)).isFalse(); // neutro (oposto)
        assertThat(Element.FIRE.beats(Element.FIRE)).isFalse();
    }

    @Test
    @DisplayName("Multiplicador: vence ×1.25, perde ×0.75, neutro/mesmo/sem-encanto ×1.0")
    void multiplier() {
        assertThat(Element.multiplier(Element.FIRE, Element.AIR)).isEqualTo(1.25);  // arma vence armadura
        assertThat(Element.multiplier(Element.AIR, Element.FIRE)).isEqualTo(0.75);  // arma perde p/ armadura
        assertThat(Element.multiplier(Element.FIRE, Element.EARTH)).isEqualTo(1.0); // neutro (oposto)
        assertThat(Element.multiplier(Element.FIRE, Element.FIRE)).isEqualTo(1.0);  // mesmo
        assertThat(Element.multiplier(null, Element.FIRE)).isEqualTo(1.0);          // sem arma encantada
        assertThat(Element.multiplier(Element.FIRE, null)).isEqualTo(1.0);          // sem armadura encantada
    }

    @Test
    @DisplayName("Essência: cada elemento mapeia pro ResourceType certo")
    void essence() {
        assertThat(Element.FIRE.essence()).isEqualTo(ResourceType.FIRE_ESSENCE);
        assertThat(Element.WATER.essence()).isEqualTo(ResourceType.WATER_ESSENCE);
        assertThat(Element.EARTH.essence()).isEqualTo(ResourceType.EARTH_ESSENCE);
        assertThat(Element.AIR.essence()).isEqualTo(ResourceType.AIR_ESSENCE);
    }
}
