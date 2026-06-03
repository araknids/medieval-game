package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara a resolução das guerras de território. Fica SEPARADO de TerritoryService
 * para que as chamadas a {@code resolveDueCyclesForTerritory} sejam cross-bean (via
 * proxy) e portanto cada território seja resolvido em sua própria transação. [AUDITORIA A7]
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerritoryScheduler {

    private final TerritoryService territoryService;

    /** Cron de 6h (00,06,12,18 UTC) — resolve o ciclo atual e ciclos perdidos. */
    @Scheduled(cron = "0 0 0,6,12,18 * * *")
    public void scheduled() {
        runCatchUp("scheduled");
    }

    /**
     * No boot, reprocessa ciclos que ficaram sem resolver durante downtime/deploy
     * (o Railway reinicia em cada deploy). Idempotente.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        runCatchUp("startup");
    }

    private void runCatchUp(String trigger) {
        territoryService.ensureInitialized();
        long current = territoryService.currentCycleId();
        log.info("Kingdom war ({}): resolving due cycles up to {}", trigger, current);
        for (Kingdom territory : territoryService.warKingdoms()) {
            try {
                territoryService.resolveDueCyclesForTerritory(territory, current);
            } catch (Exception e) {
                log.error("Error resolving territory {}: {}", territory, e.getMessage(), e);
            }
        }
        log.info("Kingdom war ({}) complete.", trigger);
    }
}
