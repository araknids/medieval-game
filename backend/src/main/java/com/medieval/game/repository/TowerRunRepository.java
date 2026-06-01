package com.medieval.game.repository;

import com.medieval.game.enums.TowerStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.TowerRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TowerRunRepository extends JpaRepository<TowerRun, Long> {
    Optional<TowerRun> findByPlayerAndStatus(Player player, TowerStatus status);
}
