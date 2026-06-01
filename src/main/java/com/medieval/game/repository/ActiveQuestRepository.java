package com.medieval.game.repository;

import com.medieval.game.model.ActiveQuest;
import com.medieval.game.model.Player;
import com.medieval.game.enums.QuestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActiveQuestRepository extends JpaRepository<ActiveQuest, Long> {
    List<ActiveQuest> findAllByPlayerAndStatusNot(Player player, QuestStatus status);
    List<ActiveQuest> findAllByPlayerAndStatusNotIn(Player player, java.util.List<QuestStatus> statuses);
    List<ActiveQuest> findAllByPlayer(Player player);
}
