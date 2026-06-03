package com.medieval.game.service;

import com.medieval.game.model.Player;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Retry transparente do collect de Zona. [AUDITORIA BL-1]
 *
 * A resolução de emboscada escreve em DUAS linhas (atacante + alvo — ambas Player com @Version,
 * e a ZoneActivity do alvo também tem @Version). Sob coleta simultânea na mesma zona, um lado pode
 * levar OptimisticLockingFailureException. Como o collect é atômico (em conflito faz rollback total,
 * sem corrupção — ver C3), refazer é seguro.
 *
 * Este bean fica SEPARADO do ZoneService para que cada tentativa passe pelo proxy e rode em sua
 * própria transação (padrão A7). Além disso, recarrega o Player fresco a cada tentativa — senão a
 * versão velha da entidade detached repetiria o conflito imediatamente.
 *
 * Se esgotar as tentativas, relança (→ 409); o retry automático do cliente cobre como última rede.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZoneCollectCoordinator {

    private static final int  MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS   = 50;

    private final ZoneService   zoneService;
    private final PlayerService playerService;

    public ZoneService.CollectResult collectWithRetry(Long playerId, Long activityId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Player fresh = playerService.findById(playerId); // versão atual a cada tentativa
                return zoneService.collect(fresh, activityId);   // cross-bean → transação nova
            } catch (OptimisticLockingFailureException e) {
                log.warn("[ZoneCollectCoordinator] player={} activity={} conflito de concorrência (tentativa {}/{})",
                        playerId, activityId, attempt, MAX_ATTEMPTS);
                if (attempt == MAX_ATTEMPTS) throw e; // esgotou → 409 (retry do cliente é a rede final)
                sleepQuietly();
            }
        }
        throw new IllegalStateException("unreachable"); // o laço sempre retorna ou relança
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(BACKOFF_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
