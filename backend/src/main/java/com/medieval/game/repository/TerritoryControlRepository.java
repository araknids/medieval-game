package com.medieval.game.repository;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.Guild;
import com.medieval.game.model.TerritoryControl;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TerritoryControlRepository extends JpaRepository<TerritoryControl, Long> {

    @EntityGraph(attributePaths = "controllingGuild")
    Optional<TerritoryControl> findByTerritory(Kingdom territory);

    @EntityGraph(attributePaths = "controllingGuild")
    Optional<TerritoryControl> findByControllingGuild(Guild guild);

    @EntityGraph(attributePaths = "controllingGuild")
    List<TerritoryControl> findAll();
}
