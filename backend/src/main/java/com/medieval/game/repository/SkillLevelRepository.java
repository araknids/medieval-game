package com.medieval.game.repository;

import com.medieval.game.enums.SkillType;
import com.medieval.game.model.Player;
import com.medieval.game.model.SkillLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillLevelRepository extends JpaRepository<SkillLevel, Long> {
    Optional<SkillLevel> findByPlayerAndSkillType(Player player, SkillType skillType);
    List<SkillLevel> findAllByPlayer(Player player);
}
