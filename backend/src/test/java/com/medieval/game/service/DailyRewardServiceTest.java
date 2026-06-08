package com.medieval.game.service;

import com.medieval.game.config.LocalizedException;
import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// [DAILY] DailyRewardService — streak do ciclo de 7 dias + entrega (bag/mail)
@ExtendWith(MockitoExtension.class)
@DisplayName("[DAILY] DailyRewardService — streak + entrega")
class DailyRewardServiceTest {

    @Mock PlayerRepository playerRepository;
    @Mock GatheringService gatheringService;
    @Mock MailService      mailService;
    @InjectMocks DailyRewardService service;

    private Player player(LocalDate lastClaim, int streak) {
        Player p = new Player();
        p.setId(1L);
        p.setLastDailyClaimDate(lastClaim);
        p.setDailyStreak(streak);
        when(playerRepository.findById(1L)).thenReturn(java.util.Optional.of(p));
        // qualquer entrega cabe na bag por padrão (sem mail) — sobrescrito quando o teste quer bag cheia
        lenient().when(gatheringService.addResource(eq(p), any(ResourceType.class), anyLong()))
                 .thenAnswer(inv -> inv.getArgument(2, Long.class));
        return p;
    }

    @Test
    @DisplayName("1ª coleta (nunca coletou) → streak 1, dia 1")
    void firstClaim() {
        Player p = player(null, 0);
        Map<String, Object> r = service.claim(p);
        assertThat(p.getDailyStreak()).isEqualTo(1);
        assertThat(p.getLastDailyClaimDate()).isEqualTo(LocalDate.now());
        assertThat(r.get("claimDay")).isEqualTo(1);
    }

    @Test
    @DisplayName("Dia consecutivo (coletou ontem) → streak +1")
    void consecutiveDayIncrementsStreak() {
        Player p = player(LocalDate.now().minusDays(1), 3);
        service.claim(p);
        assertThat(p.getDailyStreak()).isEqualTo(4);
    }

    @Test
    @DisplayName("Faltou um dia (gap) → streak volta a 1")
    void gapResetsStreak() {
        Player p = player(LocalDate.now().minusDays(3), 5);
        service.claim(p);
        assertThat(p.getDailyStreak()).isEqualTo(1);
    }

    @Test
    @DisplayName("Streak 7 cicla → dia 1 de novo (streak 8 = dia 1)")
    void cycleWrapsAfterSeven() {
        Player p = player(LocalDate.now().minusDays(1), 7); // próximo = 8 → (8-1)%7+1 = 1
        Map<String, Object> r = service.claim(p);
        assertThat(p.getDailyStreak()).isEqualTo(8);
        assertThat(r.get("claimDay")).isEqualTo(1);
    }

    @Test
    @DisplayName("Coletar de novo no mesmo dia → erro")
    void doubleClaimSameDayRejected() {
        Player p = player(LocalDate.now(), 2);
        assertThatThrownBy(() -> service.claim(p)).isInstanceOf(LocalizedException.class);
        verify(playerRepository, never()).save(any());
    }

    @Test
    @DisplayName("Bag cheia → o que não couber vai por mail de recurso")
    void bagFullSendsRemainderByMail() {
        Player p = player(null, 0); // dia 1 = 2× Small Fish
        when(gatheringService.addResource(eq(p), any(ResourceType.class), anyLong())).thenReturn(0L); // nada coube
        Map<String, Object> r = service.claim(p);
        assertThat(r.get("mailed")).isEqualTo(2);
        verify(mailService).sendResourceMail(eq(p), anyString(), any(ResourceType.class), eq(2));
    }

    @Test
    @DisplayName("canClaim: true se nunca coletou ou último < hoje; false se já hoje")
    void canClaimLogic() {
        Player never = new Player();
        assertThat(service.canClaim(never)).isTrue();
        Player today = new Player(); today.setLastDailyClaimDate(LocalDate.now());
        assertThat(service.canClaim(today)).isFalse();
        Player yesterday = new Player(); yesterday.setLastDailyClaimDate(LocalDate.now().minusDays(1));
        assertThat(service.canClaim(yesterday)).isTrue();
    }
}
