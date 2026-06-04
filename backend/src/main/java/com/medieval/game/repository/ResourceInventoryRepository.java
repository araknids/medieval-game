package com.medieval.game.repository;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.model.ResourceInventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResourceInventoryRepository extends JpaRepository<ResourceInventory, Long> {
    // Inventário V2: chave (player, type, stashed) → linhas separadas p/ bag e stash.
    Optional<ResourceInventory> findByPlayerAndResourceTypeAndStashed(Player player, ResourceType type, boolean stashed);
    List<ResourceInventory> findAllByPlayer(Player player);
    List<ResourceInventory> findAllByPlayerAndStashed(Player player, boolean stashed);
}
