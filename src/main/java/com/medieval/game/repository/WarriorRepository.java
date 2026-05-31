package com.medieval.game.repository;

import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WarriorRepository extends JpaRepository<Warrior, Long> {
    Optional<Warrior> findByPlayer(Player player);
}
