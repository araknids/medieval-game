package com.medieval.game.repository;

import com.medieval.game.enums.ExpeditionStatus;
import com.medieval.game.model.ExpeditionRun;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpeditionRunRepository extends JpaRepository<ExpeditionRun, Long> {

    boolean existsByPlayerAndStatusIn(Player player, List<ExpeditionStatus> statuses);

    @EntityGraph(attributePaths = {"player", "warrior"})
    Optional<ExpeditionRun> findFirstByPlayerAndStatusInOrderByStartedAtDesc(
            Player player, List<ExpeditionStatus> statuses);
}
