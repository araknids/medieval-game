package com.medieval.game.service;

import com.medieval.game.enums.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

// TC-023-024 — SmithingService: Joias e Sockets
@DisplayName("TC-023-024 | SmithingService — Bônus de Joias")
class SmithingServiceTest {

    // ── TC-023: GemBonus.of retorna bônus correto por tipo ──
    @Test
    @DisplayName("TC-023 | RUBY dá +5 ATK, 0 DEF, 0 HP")
    void tc023_ruby_gives5Atk() {
        var bonus = SmithingService.GemBonus.of(ResourceType.RUBY);
        assertThat(bonus.atk()).isEqualTo(5);
        assertThat(bonus.def()).isEqualTo(0);
        assertThat(bonus.hp()).isEqualTo(0);
    }

    @Test
    @DisplayName("TC-023b | SAPPHIRE dá 0 ATK, +5 DEF, 0 HP")
    void tc023b_sapphire_gives5Def() {
        var bonus = SmithingService.GemBonus.of(ResourceType.SAPPHIRE);
        assertThat(bonus.atk()).isEqualTo(0);
        assertThat(bonus.def()).isEqualTo(5);
        assertThat(bonus.hp()).isEqualTo(0);
    }

    @Test
    @DisplayName("TC-023c | EMERALD dá 0 ATK, 0 DEF, +20 HP")
    void tc023c_emerald_gives20Hp() {
        var bonus = SmithingService.GemBonus.of(ResourceType.EMERALD);
        assertThat(bonus.atk()).isEqualTo(0);
        assertThat(bonus.def()).isEqualTo(0);
        assertThat(bonus.hp()).isEqualTo(20);
    }

    @Test
    @DisplayName("TC-023d | DIAMOND dá +3 ATK, +3 DEF, +10 HP")
    void tc023d_diamond_givesAllStats() {
        var bonus = SmithingService.GemBonus.of(ResourceType.DIAMOND);
        assertThat(bonus.atk()).isEqualTo(3);
        assertThat(bonus.def()).isEqualTo(3);
        assertThat(bonus.hp()).isEqualTo(10);
    }

    @Test
    @DisplayName("TC-023e | AMETHYST dá 0 em todos os stats base")
    void tc023e_amethyst_givesNoBaseStats() {
        var bonus = SmithingService.GemBonus.of(ResourceType.AMETHYST);
        assertThat(bonus.atk()).isEqualTo(0);
        assertThat(bonus.def()).isEqualTo(0);
        assertThat(bonus.hp()).isEqualTo(0);
    }

    // ── TC-024: Soma de múltiplas joias é cumulativa ──
    @Test
    @DisplayName("TC-024 | 2x RUBY + 1x EMERALD = +10 ATK, +20 HP")
    void tc024_multipleGemBonusSumsCorrectly() {
        var ruby1   = SmithingService.GemBonus.of(ResourceType.RUBY);
        var ruby2   = SmithingService.GemBonus.of(ResourceType.RUBY);
        var emerald = SmithingService.GemBonus.of(ResourceType.EMERALD);

        int totalAtk = ruby1.atk() + ruby2.atk() + emerald.atk();
        int totalDef = ruby1.def() + ruby2.def() + emerald.def();
        int totalHp  = ruby1.hp()  + ruby2.hp()  + emerald.hp();

        assertThat(totalAtk).isEqualTo(10);
        assertThat(totalDef).isEqualTo(0);
        assertThat(totalHp).isEqualTo(20);
    }
}
