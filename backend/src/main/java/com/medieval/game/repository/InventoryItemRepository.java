package com.medieval.game.repository;

import com.medieval.game.enums.ItemType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {
    List<InventoryItem> findAllByPlayer(Player player);
    List<InventoryItem> findAllByPlayerAndListedFalse(Player player); // [LEILAO] inventário "anunciável"
    Optional<InventoryItem> findByPlayerAndTypeAndEquippedTrue(Player player, ItemType type);
}
