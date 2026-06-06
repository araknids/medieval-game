package com.medieval.game.repository;

import com.medieval.game.enums.Achievement;
import com.medieval.game.model.Player;
import com.medieval.game.model.PlayerAchievement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerAchievementRepository extends JpaRepository<PlayerAchievement, Long> {

    List<PlayerAchievement> findByPlayer(Player player);

    boolean existsByPlayerAndAchievement(Player player, Achievement achievement);
}
