package com.medieval.game.repository;

import com.medieval.game.enums.ActivityRole;
import com.medieval.game.enums.Zone;
import com.medieval.game.enums.ZoneActivityStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.ZoneActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ZoneActivityRepository extends JpaRepository<ZoneActivity, Long> {
    Optional<ZoneActivity> findByPlayerAndStatus(Player player, ZoneActivityStatus status);

    // Hunters ativos na mesma zona
    List<ZoneActivity> findAllByZoneAndRoleAndStatus(
            Zone zone, ActivityRole role, ZoneActivityStatus status);

    // Gatherers ativos na mesma zona (para o hunter caçar) — legado
    List<ZoneActivity> findAllByZoneAndRoleAndStatusAndPlayerNot(
            Zone zone, ActivityRole role, ZoneActivityStatus status, Player exclude);

    // Qualquer player ativo na zona (pool de alvos de emboscada PvP), exceto o próprio
    List<ZoneActivity> findAllByZoneAndStatusAndPlayerNot(
            Zone zone, ZoneActivityStatus status, Player exclude);

    // Expedições finalizadas pelo jogador (histórico)
    List<ZoneActivity> findAllByPlayerAndStatusInOrderByStartedAtDesc(
            Player player, List<ZoneActivityStatus> statuses);
}
