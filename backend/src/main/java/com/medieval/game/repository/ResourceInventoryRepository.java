package com.medieval.game.repository;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.model.ResourceInventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResourceInventoryRepository extends JpaRepository<ResourceInventory, Long> {
    Optional<ResourceInventory> findByPlayerAndResourceType(Player player, ResourceType type);
    List<ResourceInventory> findAllByPlayer(Player player);
}
