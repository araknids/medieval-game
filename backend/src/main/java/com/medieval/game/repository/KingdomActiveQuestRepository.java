package com.medieval.game.repository;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.QuestStatus;
import com.medieval.game.model.KingdomActiveQuest;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KingdomActiveQuestRepository extends JpaRepository<KingdomActiveQuest, Long> {

    @EntityGraph(attributePaths = {"warrior"})
    List<KingdomActiveQuest> findByPlayerAndStatusNotOrderByStartedAtDesc(
            Player player, QuestStatus status);

    @EntityGraph(attributePaths = {"warrior"})
    List<KingdomActiveQuest> findByPlayerAndKingdomAndStatusNot(
            Player player, Kingdom kingdom, QuestStatus status);

    Optional<KingdomActiveQuest> findByPlayerAndStatusIn(
            Player player, List<QuestStatus> statuses);
}
