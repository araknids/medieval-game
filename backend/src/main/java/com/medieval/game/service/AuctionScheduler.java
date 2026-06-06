package com.medieval.game.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expira (devolve ao vendedor) as listagens do leilão vencidas. Lazy-on-read também resolve. [LEILAO] */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuctionScheduler {

    private final AuctionService auctionService;

    @Scheduled(cron = "0 0 * * * *") // de hora em hora (a janela de 2 dias não precisa de timing preciso)
    public void scheduled() { run("scheduled"); }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { run("startup"); }

    private void run(String trigger) {
        try {
            auctionService.expireDueAuctions();
        } catch (Exception e) {
            log.error("[AuctionScheduler] ({}) failed: {}", trigger, e.getMessage(), e);
        }
    }
}
