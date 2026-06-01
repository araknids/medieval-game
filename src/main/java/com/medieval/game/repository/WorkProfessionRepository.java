package com.medieval.game.repository;

import com.medieval.game.enums.WorkType;
import com.medieval.game.model.Player;
import com.medieval.game.model.WorkProfession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkProfessionRepository extends JpaRepository<WorkProfession, Long> {
    Optional<WorkProfession> findByPlayerAndWorkType(Player player, WorkType workType);
    List<WorkProfession> findAllByPlayer(Player player);
}
