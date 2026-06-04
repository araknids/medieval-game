package com.medieval.game.repository;

import com.medieval.game.enums.ZoneActivityStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.ZoneActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ZoneActivityRepository extends JpaRepository<ZoneActivity, Long> {
    Optional<ZoneActivity> findByPlayerAndStatus(Player player, ZoneActivityStatus status);

    // [PVP_FLAG] As queries de pool de emboscada (por zona/role IN_PROGRESS) foram removidas:
    // o alvo do PvP agora é um player FLAGGED (PlayerRepository.findFlaggedInZone), não uma atividade.

    // Expedições finalizadas pelo jogador (histórico)
    List<ZoneActivity> findAllByPlayerAndStatusInOrderByStartedAtDesc(
            Player player, List<ZoneActivityStatus> statuses);
}
