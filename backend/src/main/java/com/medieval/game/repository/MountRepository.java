package com.medieval.game.repository;

import com.medieval.game.enums.MountType;
import com.medieval.game.model.Mount;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MountRepository extends JpaRepository<Mount, Long> {

    List<Mount> findByPlayer(Player player);

    boolean existsByPlayerAndMountType(Player player, MountType mountType);

    Optional<Mount> findByPlayerAndEquippedTrue(Player player);
}
