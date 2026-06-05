package com.medieval.game.service;

import com.medieval.game.model.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// BL-1 — retry transparente do collect de Zona sob conflito de concorrência (OptimisticLock).
@ExtendWith(MockitoExtension.class)
@DisplayName("Auditoria BL-1 | ZoneCollectCoordinator — retry sob conflito")
class ZoneCollectCoordinatorTest {

    @Mock ZoneService   zoneService;
    @Mock PlayerService playerService;
    @InjectMocks ZoneCollectCoordinator coordinator;

    private final ZoneService.CollectResult ok =
            new ZoneService.CollectResult(null, java.util.List.of(), false, true, null, null);

    @Test
    @DisplayName("Conflito na 1ª tentativa, sucesso na 2ª → retorna e recarrega o player a cada vez")
    void retriesOnceThenSucceeds() {
        when(playerService.findById(7L)).thenReturn(new Player());
        when(zoneService.collect(any(), eq(99L)))
                .thenThrow(new OptimisticLockingFailureException("conflito"))
                .thenReturn(ok);

        ZoneService.CollectResult result = coordinator.collectWithRetry(7L, 99L);

        assertThat(result).isSameAs(ok);
        verify(zoneService, times(2)).collect(any(), eq(99L));
        verify(playerService, times(2)).findById(7L); // player fresco a cada tentativa
    }

    @Test
    @DisplayName("Sucesso na 1ª tentativa → não repete")
    void noRetryWhenFirstSucceeds() {
        when(playerService.findById(7L)).thenReturn(new Player());
        when(zoneService.collect(any(), eq(99L))).thenReturn(ok);

        coordinator.collectWithRetry(7L, 99L);

        verify(zoneService, times(1)).collect(any(), eq(99L));
    }

    @Test
    @DisplayName("Conflito persistente (3×) → relança OptimisticLockingFailureException (→ 409)")
    void rethrowsAfterMaxAttempts() {
        when(playerService.findById(7L)).thenReturn(new Player());
        when(zoneService.collect(any(), eq(99L)))
                .thenThrow(new OptimisticLockingFailureException("conflito"));

        assertThatThrownBy(() -> coordinator.collectWithRetry(7L, 99L))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verify(zoneService, times(3)).collect(any(), eq(99L)); // MAX_ATTEMPTS
    }
}
