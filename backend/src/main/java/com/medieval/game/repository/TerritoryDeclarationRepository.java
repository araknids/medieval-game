package com.medieval.game.repository;

import com.medieval.game.enums.Territory;
import com.medieval.game.model.Guild;
import com.medieval.game.model.TerritoryDeclaration;
import com.medieval.game.model.TerritoryDeclaration.DeclarationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TerritoryDeclarationRepository extends JpaRepository<TerritoryDeclaration, Long> {

    List<TerritoryDeclaration> findByTerritoryAndStatusOrderByDeclaredAtAsc(
            Territory territory, DeclarationStatus status);

    Optional<TerritoryDeclaration> findByGuildAndBattleCycleIdAndStatus(
            Guild guild, long cycleId, DeclarationStatus status);

    boolean existsByGuildAndBattleCycleIdAndStatus(
            Guild guild, long cycleId, DeclarationStatus status);
}
