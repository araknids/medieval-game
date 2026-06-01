package com.medieval.game.repository;

import com.medieval.game.enums.WorkStatus;
import com.medieval.game.model.Player;
import com.medieval.game.model.WorkSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkSessionRepository extends JpaRepository<WorkSession, Long> {
    Optional<WorkSession> findByPlayerAndStatus(Player player, WorkStatus status);
}
