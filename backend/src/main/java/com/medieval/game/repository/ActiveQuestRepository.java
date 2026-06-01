package com.medieval.game.repository;

import com.medieval.game.enums.QuestStatus;
import com.medieval.game.model.ActiveQuest;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActiveQuestRepository extends JpaRepository<ActiveQuest, Long> {

    @EntityGraph(attributePaths = {"warrior"})
    List<ActiveQuest> findAllByPlayerAndStatusNot(Player player, QuestStatus status);

    @EntityGraph(attributePaths = {"warrior"})
    List<ActiveQuest> findAllByPlayerAndStatusNotIn(Player player, List<QuestStatus> statuses);

    List<ActiveQuest> findAllByPlayer(Player player);
}
