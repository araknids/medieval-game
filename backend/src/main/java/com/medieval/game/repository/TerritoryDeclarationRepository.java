package com.medieval.game.repository;

import com.medieval.game.enums.Territory;
import com.medieval.game.model.Guild;
import com.medieval.game.model.TerritoryDeclaration;
import com.medieval.game.model.TerritoryDeclaration.DeclarationStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TerritoryDeclarationRepository extends JpaRepository<TerritoryDeclaration, Long> {

    // EntityGraph eagerly loads guild to avoid LazyInitializationException with open-in-view=false
    @EntityGraph(attributePaths = "guild")
    List<TerritoryDeclaration> findByTerritoryAndStatusOrderByDeclaredAtAsc(
            Territory territory, DeclarationStatus status);

    @EntityGraph(attributePaths = "guild")
    Optional<TerritoryDeclaration> findByGuildAndBattleCycleIdAndStatus(
            Guild guild, long cycleId, DeclarationStatus status);

    boolean existsByGuildAndBattleCycleIdAndStatus(
            Guild guild, long cycleId, DeclarationStatus status);
}
