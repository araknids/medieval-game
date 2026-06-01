package com.medieval.game.repository;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.SocketedGem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SocketedGemRepository extends JpaRepository<SocketedGem, Long> {
    List<SocketedGem> findAllByItem(InventoryItem item);
    List<SocketedGem> findAllByItemIn(List<InventoryItem> items);
    void deleteAllByItem(InventoryItem item);
}
