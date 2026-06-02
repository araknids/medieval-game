package com.medieval.game.repository;

import com.medieval.game.enums.TrainingStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.TrainingSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrainingSessionRepository extends JpaRepository<TrainingSession, Long> {

    Optional<TrainingSession> findByPlayerAndStatus(Player player, TrainingStatus status);

    boolean existsByPlayerAndStatus(Player player, TrainingStatus status);
}
