package com.medieval.game.repository;

import com.medieval.game.enums.GatheringStatus;
import com.medieval.game.model.GatheringSession;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GatheringSessionRepository extends JpaRepository<GatheringSession, Long> {
    Optional<GatheringSession> findByPlayerAndStatus(Player player, GatheringStatus status);
}
