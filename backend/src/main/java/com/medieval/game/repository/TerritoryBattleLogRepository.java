package com.medieval.game.repository;

import com.medieval.game.enums.Territory;
import com.medieval.game.model.TerritoryBattleLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TerritoryBattleLogRepository extends JpaRepository<TerritoryBattleLog, Long> {
    List<TerritoryBattleLog> findTop10ByTerritoryOrderByResolvedAtDesc(Territory territory);
}
