package com.medieval.game.config;

import com.medieval.game.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Dispara o soft wipe no boot QUANDO a flag está ligada. Gated por env var
 * APP_MAINTENANCE_SOFT_WIPE (relaxed binding de app.maintenance.soft-wipe).
 *
 * Fluxo de uso (Railway):
 *   1. Deploy do app já com este código.
 *   2. Defina APP_MAINTENANCE_SOFT_WIPE=true e reinicie/redeploy → reseta 1×.
 *   3. Defina APP_MAINTENANCE_SOFT_WIPE=false (ou remova) e redeploy — senão
 *      reseta toda vez que o app subir.
 *
 * Roda via proxy (cross-bean) para que o @Transactional do MaintenanceService valha.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoftWipeRunner {

    @Value("${app.maintenance.soft-wipe:false}")
    private boolean softWipeEnabled;

    private final MaintenanceService maintenanceService;

    @EventListener(ApplicationReadyEvent.class)
    public void runIfEnabled() {
        if (!softWipeEnabled) return;
        log.warn("==================================================================");
        log.warn("  ⚠  APP_MAINTENANCE_SOFT_WIPE=TRUE — resetando TODOS os jogadores");
        log.warn("==================================================================");
        try {
            int n = maintenanceService.softWipe();
            log.warn("  ✓ Soft wipe concluído: {} jogador(es) resetado(s) p/ fresh start.", n);
        } catch (Exception e) {
            log.error("  ✗ Soft wipe FALHOU: {}", e.getMessage(), e);
        }
        log.warn("  ⚠  AGORA defina APP_MAINTENANCE_SOFT_WIPE=false e redeploy");
        log.warn("     (senão o reset roda de novo a cada boot do app).");
        log.warn("==================================================================");
    }
}
