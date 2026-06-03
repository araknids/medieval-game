package com.medieval.game.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Avisos de boot. Não aborta (o instant-complete pode estar ligado de propósito
 * em prod para testes), mas torna a configuração perigosa impossível de passar
 * despercebida nos logs. [AUDITORIA M5]
 */
@Slf4j
@Component
public class StartupChecks {

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfInstantComplete() {
        if (!instantComplete) return;
        boolean prod = activeProfile != null && activeProfile.contains("prod");
        log.warn("==================================================================");
        log.warn("  ⚠  app.dev.instant-complete=TRUE — TODOS os timers estão ZERADOS");
        log.warn("     (quest/arena/trabalho/torre/coleta completam na hora).");
        log.warn("     Perfil ativo: {}", activeProfile);
        if (prod) {
            log.warn("  ⚠  Isto está LIGADO EM PRODUÇÃO. Desligue (false) antes do go-live!");
        }
        log.warn("==================================================================");
    }
}
