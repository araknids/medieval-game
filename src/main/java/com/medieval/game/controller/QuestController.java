package com.medieval.game.controller;

import com.medieval.game.model.ActiveQuest;
import com.medieval.game.model.Player;
import com.medieval.game.enums.QuestType;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.QuestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/quests")
@RequiredArgsConstructor
public class QuestController {

    private final QuestService questService;
    private final PlayerService playerService;

    // Lista os tipos de missão disponíveis
    @GetMapping("/types")
    public ResponseEntity<?> getQuestTypes() {
        var types = Arrays.stream(QuestType.values()).map(qt -> Map.of(
                "id", qt.name(),
                "displayName", qt.displayName,
                "durationMinutes", qt.durationMinutes,
                "goldReward", qt.goldReward,
                "expReward", qt.expReward,
                "staminaCost", qt.staminaCost
        )).toList();
        return ResponseEntity.ok(types);
    }

    // Missões ativas do jogador (não coletadas)
    @GetMapping
    public ResponseEntity<List<QuestResponse>> listActive(Authentication auth) {
        Player player = getPlayer(auth);
        List<QuestResponse> list = questService.getActiveQuests(player)
                .stream().map(QuestResponse::from).toList();
        return ResponseEntity.ok(list);
    }

    // Envia guerreiro em missão
    @PostMapping("/start")
    public ResponseEntity<?> startQuest(@Valid @RequestBody StartQuestRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            ActiveQuest quest = questService.sendOnQuest(player, req.questType());
            return ResponseEntity.ok(QuestResponse.from(quest));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Abandona missão — perde tudo, guerreiro liberado imediatamente
    @PostMapping("/{questId}/abandon")
    public ResponseEntity<?> abandonQuest(@PathVariable Long questId, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            questService.abandonQuest(player, questId);
            return ResponseEntity.ok(Map.of("message", "Missão abandonada."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Coleta recompensa de missão concluída
    @PostMapping("/{questId}/collect")
    public ResponseEntity<?> collectReward(@PathVariable Long questId, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            var result = questService.collectReward(player, questId);
            ActiveQuest quest = result.quest();

            var response = new java.util.HashMap<String, Object>();
            response.put("goldEarned", quest.getGoldReward());
            response.put("expEarned",  quest.getExpReward());
            response.put("questId",    quest.getId());

            if (result.droppedItem() != null) {
                var item = result.droppedItem();
                response.put("droppedItem", Map.of(
                    "name",        item.getName(),
                    "typeDisplay", item.getType().displayName,
                    "rarity",      item.getRarity(),
                    "attackBonus", item.getAttackBonus(),
                    "defenseBonus",item.getDefenseBonus(),
                    "healthBonus", item.getHealthBonus()
                ));
            }

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    record StartQuestRequest(@NotNull QuestType questType) {}

    record QuestResponse(Long id, String questType, String status,
                         Long warriorId, String warriorName,
                         long goldReward, long expReward,
                         LocalDateTime completesAt, long secondsRemaining,
                         boolean readyToCollect) {
        static QuestResponse from(ActiveQuest q) {
            long secsLeft = Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), q.getCompletesAt()));
            return new QuestResponse(
                    q.getId(),
                    q.getQuestType().displayName,
                    q.getStatus().name(),
                    q.getWarrior().getId(),
                    q.getWarrior().getName(),
                    q.getGoldReward(),
                    q.getExpReward(),
                    q.getCompletesAt(),
                    secsLeft,
                    q.isReadyToCollect()
            );
        }
    }
}
