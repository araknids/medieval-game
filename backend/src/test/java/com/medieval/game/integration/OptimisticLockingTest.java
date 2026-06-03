package com.medieval.game.integration;

import com.medieval.game.enums.ActivityRole;
import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.Zone;
import com.medieval.game.enums.ZoneActivityStatus;
import com.medieval.game.model.Mail;
import com.medieval.game.model.Player;
import com.medieval.game.model.ZoneActivity;
import com.medieval.game.repository.MailRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.ZoneActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

// C3 — prova que o optimistic locking (@Version) impede escrita concorrente perdida
// (a 2ª transação que parte de uma versão obsoleta falha). docs/AUDITORIA_CONSELHO.md
//
// Cada chamada de repositório aqui roda em sua própria transação (o método de teste
// NÃO é @Transactional), então as duas cópias carregadas ficam "destacadas" com a
// mesma versão — exatamente o cenário de duplo-clique no collect.
@DisplayName("Auditoria C3 | Optimistic locking impede double-collect/double-spend concorrente")
class OptimisticLockingTest extends BaseIntegrationTest {

    @Autowired PlayerRepository       playerRepository;
    @Autowired ZoneActivityRepository activityRepository;
    @Autowired MailRepository         mailRepository;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("lock"));
    }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("lock"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a)
                .orElseThrow();
    }

    // ── Player: double-spend / contadores diários concorrentes ──
    @Test
    @DisplayName("C3 | 2ª escrita concorrente no Player (versão obsoleta) falha")
    void c3_player_concurrentWriteFails() {
        Long id = player().getId();

        Player p1 = playerRepository.findById(id).orElseThrow();
        Player p2 = playerRepository.findById(id).orElseThrow();

        p1.setRankPoints(p1.getRankPoints() + 1);
        playerRepository.saveAndFlush(p1); // versão 0 → 1

        p2.setRankPoints(p2.getRankPoints() + 5); // ainda na versão 0
        assertThatThrownBy(() -> playerRepository.saveAndFlush(p2))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ── ZoneActivity: double-collect / emboscada concorrente ──
    @Test
    @DisplayName("C3 | 2ª coleta concorrente da mesma expedição falha")
    void c3_zoneActivity_concurrentCollectFails() {
        Player p = player();
        ZoneActivity act = new ZoneActivity();
        act.setPlayer(p);
        act.setZone(Zone.SAFE);
        act.setRole(ActivityRole.GATHERING);
        act.setSkillType(SkillType.FISHING);
        act.setDurationMinutes(60);
        act.setStartedAt(LocalDateTime.now());
        act.setEndsAt(LocalDateTime.now().plusHours(1));
        act.setStatus(ZoneActivityStatus.IN_PROGRESS);
        Long id = activityRepository.save(act).getId();

        ZoneActivity c1 = activityRepository.findById(id).orElseThrow();
        ZoneActivity c2 = activityRepository.findById(id).orElseThrow();

        c1.setStatus(ZoneActivityStatus.COMPLETED);
        activityRepository.saveAndFlush(c1);

        c2.setStatus(ZoneActivityStatus.COMPLETED);
        assertThatThrownBy(() -> activityRepository.saveAndFlush(c2))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    // ── Mail: coletar gold/item da mesma carta duas vezes ──
    @Test
    @DisplayName("C3 | 2ª coleta concorrente da mesma carta falha")
    void c3_mail_concurrentClaimFails() {
        Player p = player();
        Mail m = new Mail();
        m.setSenderPlayerId(0L);
        m.setSenderWarriorName("Sistema");
        m.setRecipientPlayerId(p.getId());
        m.setMessage("Recompensa de teste");
        m.setGoldAmount(500);
        Long id = mailRepository.save(m).getId();

        Mail c1 = mailRepository.findById(id).orElseThrow();
        Mail c2 = mailRepository.findById(id).orElseThrow();

        c1.setCollectedAt(LocalDateTime.now());
        mailRepository.saveAndFlush(c1);

        c2.setCollectedAt(LocalDateTime.now());
        assertThatThrownBy(() -> mailRepository.saveAndFlush(c2))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
