package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

// [BUFF_NOVATO] Buff de novato — estamina/HP regeneram em 15 min nos 3 primeiros dias da conta
@DisplayName("[BUFF_NOVATO] Buff de novato — janela de regen 15min×3 dias")
class NewbieBuffTest {

    @Test
    @DisplayName("Conta nova (< 3 dias) → buff ativo, regen 15 min")
    void freshAccountHasBuff() {
        Player p = new Player();
        p.setCreatedAt(LocalDateTime.now().minusHours(2));
        assertThat(p.isNewbieBuffActive()).isTrue();
        assertThat(p.regenMinutes()).isEqualTo(15);
        assertThat(p.getNewbieBuffHoursLeft()).isBetween(1L, 72L);
    }

    @Test
    @DisplayName("Conta antiga (> 3 dias) → sem buff, regen 60 min")
    void oldAccountNoBuff() {
        Player p = new Player();
        p.setCreatedAt(LocalDateTime.now().minusDays(4));
        assertThat(p.isNewbieBuffActive()).isFalse();
        assertThat(p.regenMinutes()).isEqualTo(60);
        assertThat(p.getNewbieBuffHoursLeft()).isEqualTo(0);
    }

    @Test
    @DisplayName("Estamina regenera 4× mais rápido no buff (15 vs 60 min)")
    void staminaRegensFasterWithBuff() {
        // 15 min decorridos, começando de 0
        Player buffed = new Player();
        buffed.setCreatedAt(LocalDateTime.now().minusHours(1)); // buff ativo
        buffed.setCurrentStamina(0);
        buffed.setStaminaUpdatedAt(LocalDateTime.now().minusMinutes(15));
        assertThat(buffed.getCalculatedStamina()).isEqualTo(100); // 15min = cheio no buff

        Player normal = new Player();
        normal.setCreatedAt(LocalDateTime.now().minusDays(10)); // sem buff
        normal.setCurrentStamina(0);
        normal.setStaminaUpdatedAt(LocalDateTime.now().minusMinutes(15));
        assertThat(normal.getCalculatedStamina()).isEqualTo(25); // 15/60 = 25% normal
    }

    @Test
    @DisplayName("HP do Warrior usa a janela passada (15 vs 60 min)")
    void warriorHpUsesRegenWindow() {
        Warrior w = new Warrior();
        w.setCurrentHpSnapshot(0);
        w.setHpUpdatedAt(LocalDateTime.now().minusMinutes(15));
        assertThat(w.getCalculatedHpPercent(15)).isEqualTo(100); // buff: cheio em 15min
        assertThat(w.getCalculatedHpPercent(60)).isEqualTo(25);  // normal: 25%
    }
}
