package com.medieval.game.repository;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.Player;
import com.medieval.game.model.TerritoryContribution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TerritoryContributionRepository extends JpaRepository<TerritoryContribution, Long> {

    Optional<TerritoryContribution> findByPlayerAndKingdom(Player player, Kingdom kingdom);

    // [LEADERBOARDS] Top contribuintes de um território (incursões DESC), paginado no DB.
    @Query("SELECT tc FROM TerritoryContribution tc WHERE tc.kingdom = :k ORDER BY tc.incursions DESC")
    List<TerritoryContribution> findTopByKingdom(@Param("k") Kingdom kingdom, Pageable pageable);
}
