package com.medieval.game.service;

import com.medieval.game.enums.ZoneActivityStatus;
import com.medieval.game.repository.MailRepository;
import com.medieval.game.repository.ZoneActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * [LAUNCH_HARDENING] Poda de retenção das tabelas que SÓ crescem.
 *
 * O jogo é instantâneo ([SEM_TIMER]): cada ação grava uma linha em {@code zone_activities} que nunca era
 * apagada — num servidor público isso vira o ralo de disco/índice silencioso no Postgres mais barato. A
 * inbox de {@code mail} também acumula (recompensas, overflow de bag, cartas de raid). Aqui apagamos em
 * massa o que já não tem valor de jogo:
 *   • zone_activities FINALIZADAS (COMPLETED/DEFEATED/CANCELLED) mais velhas que N dias — o collect já
 *     aplicou as recompensas; a linha era só histórico.
 *   • mail EXPIRADA (passou o expiresAt) — o item/recurso já está perdido por design (isExpired bloqueia
 *     o claim), então a linha é lixo.
 * Roda 1×/dia (fora de pico) + no boot. Tudo gateado por {@code app.retention.enabled} (true por padrão).
 * Single-instance é o modelo do projeto (1 app por servidor [SERVIDORES]), então scheduler em memória é ok.
 * Espelha o padrão do {@link AuctionScheduler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionScheduler {

    private final ZoneActivityRepository zoneActivityRepository;
    private final MailRepository         mailRepository;

    @Value("${app.retention.enabled:true}")
    private boolean enabled;
    @Value("${app.retention.zone-history-days:7}")
    private long zoneHistoryDays;
    @Value("${app.retention.mail-expired-grace-days:2}")
    private long mailExpiredGraceDays;

    private static final List<ZoneActivityStatus> FINISHED = List.of(
            ZoneActivityStatus.COMPLETED, ZoneActivityStatus.DEFEATED, ZoneActivityStatus.CANCELLED);

    @Scheduled(cron = "0 15 4 * * *") // 04:15 todo dia (fora de pico)
    public void scheduled() { run("scheduled"); }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() { run("startup"); }

    private void run(String trigger) {
        if (!enabled) return;
        try {
            int zones = zoneActivityRepository.deleteByStatusInAndStartedAtBefore(
                    FINISHED, LocalDateTime.now().minusDays(zoneHistoryDays));
            int mails = mailRepository.deleteByExpiresAtBefore(
                    LocalDateTime.now().minusDays(mailExpiredGraceDays));
            if (zones > 0 || mails > 0)
                log.info("[RetentionScheduler] ({}) podou zone_activities={} mail_expirada={}", trigger, zones, mails);
        } catch (Exception e) {
            log.error("[RetentionScheduler] ({}) falhou: {}", trigger, e.getMessage(), e);
        }
    }
}
