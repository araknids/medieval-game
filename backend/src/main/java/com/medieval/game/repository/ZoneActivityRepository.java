package com.medieval.game.repository;

import com.medieval.game.enums.ZoneActivityStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.ZoneActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ZoneActivityRepository extends JpaRepository<ZoneActivity, Long> {
    Optional<ZoneActivity> findByPlayerAndStatus(Player player, ZoneActivityStatus status);

    // [PVP_FLAG] As queries de pool de emboscada (por zona/role IN_PROGRESS) foram removidas:
    // o alvo do PvP agora é um player FLAGGED (PlayerRepository.findFlaggedInZone), não uma atividade.

    // [LAUNCH_HARDENING] Histórico LIMITADO no banco (Top 20). Antes carregava TODAS as expedições
    // finalizadas do jogador na memória só p/ mostrar as últimas 10 → como o jogo é instantâneo
    // (1 linha por ação, [SEM_TIMER]), um farmer pesado materializava milhares de entidades por request.
    List<ZoneActivity> findTop20ByPlayerAndStatusInOrderByStartedAtDesc(
            Player player, List<ZoneActivityStatus> statuses);

    // [LAUNCH_HARDENING] Poda de retenção: apaga em massa as expedições FINALIZADAS antigas (sem valor de
    // jogo após o collect). Bulk delete (ignora ciclo de vida JPA) — só status terminais, nunca IN_PROGRESS.
    @Modifying
    @Transactional
    @Query("delete from ZoneActivity z where z.status in :statuses and z.startedAt < :cutoff")
    int deleteByStatusInAndStartedAtBefore(@Param("statuses") List<ZoneActivityStatus> statuses,
                                           @Param("cutoff") LocalDateTime cutoff);
}
