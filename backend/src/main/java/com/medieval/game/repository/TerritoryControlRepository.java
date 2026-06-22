package com.medieval.game.repository;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.Guild;
import com.medieval.game.model.TerritoryControl;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TerritoryControlRepository extends JpaRepository<TerritoryControl, Long> {

    @EntityGraph(attributePaths = "controllingGuild")
    Optional<TerritoryControl> findByTerritory(Kingdom territory);

    // [VARREDURA] Claim ATÔMICO do avanço de ciclo (CAS last→current): só a instância que ganha
    // (rowcount==1) resolve os ciclos — evita 2 instâncias / deploy rolling aplicarem upkeep+reward 2×.
    // clearAutomatically=true: limpa o contexto p/ resolveTerritory re-ler o control fresco (= current).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TerritoryControl c SET c.lastResolvedCycleId = :to WHERE c.territory = :territory AND c.lastResolvedCycleId = :from")
    int claimCycle(@Param("territory") Kingdom territory, @Param("from") long from, @Param("to") long to);

    @EntityGraph(attributePaths = "controllingGuild")
    Optional<TerritoryControl> findByControllingGuild(Guild guild);

    @EntityGraph(attributePaths = "controllingGuild")
    List<TerritoryControl> findAll();
}
