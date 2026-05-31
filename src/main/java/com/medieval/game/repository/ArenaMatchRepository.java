package com.medieval.game.repository;

import com.medieval.game.enums.MatchStatus;
import com.medieval.game.model.ArenaMatch;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ArenaMatchRepository extends JpaRepository<ArenaMatch, Long> {

    Optional<ArenaMatch> findByChallengerAndStatus(Player challenger, MatchStatus status);

    // Top 10 jogadores por rank para o placar
    @Query("SELECT p FROM Player p ORDER BY p.rankPoints DESC")
    List<Player> findTopRanked();
}
