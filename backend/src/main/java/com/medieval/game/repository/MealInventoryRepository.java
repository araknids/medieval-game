package com.medieval.game.repository;

import com.medieval.game.enums.Meal;
import com.medieval.game.model.MealInventory;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MealInventoryRepository extends JpaRepository<MealInventory, Long> {
    Optional<MealInventory> findByPlayerAndMeal(Player player, Meal meal);
    List<MealInventory> findAllByPlayer(Player player);
}
