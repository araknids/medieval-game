package com.medieval.game.repository;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.enums.QuestStatus;
import com.medieval.game.model.KingdomActiveQuest;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KingdomActiveQuestRepository extends JpaRepository<KingdomActiveQuest, Long> {

    @EntityGraph(attributePaths = {"warrior"})
    List<KingdomActiveQuest> findByPlayerAndStatusNotOrderByStartedAtDesc(
            Player player, QuestStatus status);

    @EntityGraph(attributePaths = {"warrior"})
    List<KingdomActiveQuest> findByPlayerAndKingdomAndStatusNot(
            Player player, Kingdom kingdom, QuestStatus status);

    // [DAILY_QUESTS] Quantas vezes o player completou esta quest na janela de 12h (limite 1× / 2× VIP).
    long countByPlayerAndQuestTypeAndStatusAndCompletedWindowId(
            Player player, KingdomQuestType questType, QuestStatus status, long completedWindowId);

    // [QUEST_BADGE] Tipos de quest que o player completou nesta janela (1 linha por conclusão) — usado
    // p/ calcular, em 1 query, quais reinos ainda têm daily disponível (o "!" amarelo no mapa).
    @Query("select q.questType from KingdomActiveQuest q " +
           "where q.player = :player and q.status = :status and q.completedWindowId = :windowId")
    List<KingdomQuestType> collectedQuestTypesInWindow(
            @Param("player") Player player, @Param("status") QuestStatus status, @Param("windowId") long windowId);

    // [SEM_TIMER] Player tem alguma quest IN_PROGRESS? (substitui o antigo guard onMission p/ quest)
    boolean existsByPlayerAndStatus(Player player, QuestStatus status);

    // [DIARIO_QUEST] Player já tem ESTA quest IN_PROGRESS? (guard por questType — libera o to-do de várias
    // quests aceitas ao mesmo tempo, mas ainda barra startar a mesma 2x antes de resolver).
    boolean existsByPlayerAndQuestTypeAndStatus(Player player, KingdomQuestType questType, QuestStatus status);

    // [DIARIO_QUEST] Quests num status (ex.: IN_PROGRESS p/ a aba "Em progresso" = to-do).
    @EntityGraph(attributePaths = {"warrior"})
    List<KingdomActiveQuest> findByPlayerAndStatusOrderByStartedAtDesc(Player player, QuestStatus status);
}
