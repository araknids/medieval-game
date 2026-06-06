package com.medieval.game.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resolve as guerras de guilda vencidas (7 dias). Lazy-on-read também resolve ao acessar. [GUERRA_GUILDA] */
@Slf4j
@Component
@RequiredArgsConstructor
public class GuildWarScheduler {

    private final GuildWarService guildWarService;

    // De hora em hora (minuto 30) — a janela de 7 dias não precisa de timing preciso.
    @Scheduled(cron = "0 30 * * * *")
    public void scheduled() { run("scheduled"); }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { run("startup"); }

    private void run(String trigger) {
        try {
            guildWarService.resolveDueWars();
        } catch (Exception e) {
            log.error("[GuildWarScheduler] ({}) failed: {}", trigger, e.getMessage(), e);
        }
    }
}
