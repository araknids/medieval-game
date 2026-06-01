package com.medieval.game.repository;

import com.medieval.game.model.Player;
import com.medieval.game.model.ShopPurchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public interface ShopPurchaseRepository extends JpaRepository<ShopPurchase, Long> {
    List<ShopPurchase> findAllByPlayerAndRotationId(Player player, long rotationId);

    default Set<Integer> purchasedSlots(Player player, long rotationId) {
        return findAllByPlayerAndRotationId(player, rotationId)
                .stream().map(ShopPurchase::getSlotIndex).collect(Collectors.toSet());
    }
}
