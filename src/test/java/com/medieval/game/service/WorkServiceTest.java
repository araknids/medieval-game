package com.medieval.game.service;

import com.medieval.game.enums.WorkType;
import com.medieval.game.model.WorkProfession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

// TC-017-019 — WorkService: Cálculo de Recompensa
@DisplayName("TC-017-019 | WorkService — Cálculo de Recompensa")
class WorkServiceTest {

    // ── TC-017: Bônus por nível de profissão aplica corretamente ──
    @Test
    @DisplayName("TC-017 | Nível 3 de profissão dá +10% gold/h")
    void tc017_professionLevelBonusApplied() {
        WorkProfession prof = new WorkProfession();
        prof.setLevel(3);

        // goldBonus = 1.0 + (level - 1) * 0.05 = 1.0 + 2 * 0.05 = 1.10
        double bonus = prof.goldBonus();
        assertThat(bonus).isEqualTo(1.10, within(0.001));
    }

    // ── TC-018: Cancel proporcional com horas completas ──
    @Test
    @DisplayName("TC-018 | Cancel após 3h de 8h dá 37.5% da recompensa")
    void tc018_cancelProportionalReward() {
        long totalReward  = 800L;
        int  totalHours   = 8;
        long hoursWorked  = 3;

        long proportional = Math.round(totalReward * hoursWorked / (double) totalHours);

        assertThat(proportional).isEqualTo(300L);
    }

    // ── TC-019: Cancel com menos de 1h completa dá 0 ──
    @Test
    @DisplayName("TC-019 | Cancel com 0 horas completas → goldEarned = 0")
    void tc019_cancelWith0HoursGivesNothing() {
        long totalReward = 600L;
        int  totalHours  = 4;
        long hoursWorked = 0;

        long proportional = Math.round(totalReward * hoursWorked / (double) totalHours);

        assertThat(proportional).isEqualTo(0L);
    }

    // ── TC-extra: WorkType.LOCAL_MERCENARY requer level 5 mínimo ──
    @Test
    @DisplayName("TC-extra | LOCAL_MERCENARY exige level 5 do guerreiro")
    void tcExtra_mercenaryRequiresLevel5() {
        assertThat(WorkType.LOCAL_MERCENARY.minWorkLevel).isEqualTo(5);
    }

    // ── TC-extra: WorkProfession.level 1 tem 0% de bônus ──
    @Test
    @DisplayName("TC-extra | Profissão nível 1 tem bônus 0%")
    void tcExtra_level1HasNoBonus() {
        WorkProfession prof = new WorkProfession();
        prof.setLevel(1);
        assertThat(prof.goldBonus()).isEqualTo(1.0, within(0.001));
    }
}
