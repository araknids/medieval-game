package com.medieval.game.repository;

import com.medieval.game.enums.MatchStatus;
import com.medieval.game.model.ArenaMatch;
import com.medieval.game.model.Player;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ArenaMatchRepository extends JpaRepository<ArenaMatch, Long> {

    Optional<ArenaMatch> findByChallengerAndStatus(Player challenger, MatchStatus status);

    // Top jogadores por rank para o placar — limite aplicado no banco. [AUDITORIA M14]
    @Query("SELECT p FROM Player p ORDER BY p.rankPoints DESC")
    List<Player> findTopRanked(Pageable pageable);
}
