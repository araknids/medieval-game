package com.medieval.game.repository;

import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WarriorRepository extends JpaRepository<Warrior, Long> {
    Optional<Warrior> findByPlayer(Player player);

    // Eagerly loads player to avoid LazyInitializationException with open-in-view=false
    @EntityGraph(attributePaths = "player")
    Optional<Warrior> findByName(String name);

    // [AUDITORIA_2 A5] Batch p/ evitar N+1 (roster de guilda, ranking de arena, guerra). player eager
    // p/ poder mapear por player.id fora de @Transactional.
    @EntityGraph(attributePaths = "player")
    List<Warrior> findByPlayerIn(Collection<Player> players);
}
