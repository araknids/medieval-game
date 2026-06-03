package com.medieval.game.controller;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.enums.QuestStatus;
import com.medieval.game.model.*;
import com.medieval.game.service.KingdomService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/world")
@RequiredArgsConstructor
public class KingdomController {

    private final KingdomService kingdomService;
    private final PlayerService  playerService;

    // ── Kingdom overview ──────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> listKingdoms(Authentication auth) {
        Player player = getPlayer(auth);
        List<?> kingdoms = kingdomService.getAllKingdomStatus(player).stream()
                .map(ks -> Map.ofEntries(
                    Map.entry("kingdom",          ks.kingdom().name()),
                    Map.entry("displayName",      ks.kingdom().displayName),
                    Map.entry("icon",             ks.kingdom().icon),
                    Map.entry("lore",             ks.kingdom().lore),
                    Map.entry("controllingGuild", ks.controllingGuild() != null ? ks.controllingGuild() : ""),
                    Map.entry("isMine",           ks.isMine()),
                    Map.entry("xpBonus",          ks.xpBonus()),
                    Map.entry("bronzeBonus",      ks.bronzeBonus()),
                    Map.entry("exclusiveBonus",   ks.exclusiveBonus()),
                    Map.entry("secsUntilBattle",  ks.secsUntilBattle()),
                    Map.entry("defenseStreak",    ks.defenseStreak()),
                    Map.entry("primarySkill",     ks.kingdom().primarySkill != null
                            ? ks.kingdom().primarySkill.name() : "")
                )).toList();
        return ResponseEntity.ok(kingdoms);
    }

    // ── Quest types for a kingdom ─────────────────────────────────────────────
    @GetMapping("/{kingdom}/quests")
    public ResponseEntity<?> getQuestTypes(@PathVariable Kingdom kingdom, Authentication auth) {
        Player player = getPlayer(auth);
        int stamina = player.getCalculatedStamina();

        List<?> quests = kingdomService.getQuestsForKingdom(kingdom).stream()
                .map(qt -> Map.of(
                    "id",              qt.name(),
                    "displayName",     qt.displayName,
                    "durationMinutes", qt.durationMinutes,
                    "bronzeReward",    qt.bronzeReward,
                    "expReward",       qt.expReward,
                    "staminaCost",     qt.staminaCost,
                    "dropChance",      qt.dropChance,
                    "canStart",        stamina >= qt.staminaCost
                )).toList();
        return ResponseEntity.ok(quests);
    }

    // ── Active quests in a kingdom ────────────────────────────────────────────
    @GetMapping("/{kingdom}/quests/active")
    public ResponseEntity<?> getActiveQuests(@PathVariable Kingdom kingdom, Authentication auth) {
        Player player = getPlayer(auth);
        List<?> active = kingdomService.getActiveQuests(player, kingdom).stream()
                .map(this::questToMap).toList();
        return ResponseEntity.ok(active);
    }

    // ── Start a kingdom quest ─────────────────────────────────────────────────
    @PostMapping("/{kingdom}/quests/start")
    public ResponseEntity<?> startQuest(
            @PathVariable Kingdom kingdom,
            @RequestBody StartQuestRequest req,
            Authentication auth) {
        try {
            Player player = getPlayer(auth);
            KingdomActiveQuest quest = kingdomService.startQuest(player, kingdom, req.questType());
            return ResponseEntity.ok(questToMap(quest));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Collect quest reward ──────────────────────────────────────────────────
    @PostMapping("/{kingdom}/quests/{id}/collect")
    public ResponseEntity<?> collectQuest(
            @PathVariable Kingdom kingdom,
            @PathVariable Long id,
            Authentication auth) {
        try {
            Player player = getPlayer(auth);
            KingdomService.CollectResult result = kingdomService.collectQuest(player, id);
            var resp = new java.util.HashMap<String, Object>();
            resp.put("bronzeEarned", result.bronzeEarned());
            resp.put("xpEarned",     result.xpEarned());
            resp.put("questId",      result.quest().getId());
            if (result.droppedItem() != null) {
                InventoryItem d = result.droppedItem();
                resp.put("droppedItem", Map.of(
                    "name",        d.getName(),
                    "type",        d.getType().name(),
                    "rarity",      d.getRarity(),
                    "attackBonus", d.getAttackBonus(),
                    "defenseBonus",d.getDefenseBonus(),
                    "healthBonus", d.getHealthBonus()
                ));
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Instant-start quest (VIP only) ───────────────────────────────────────
    @PostMapping("/{kingdom}/quests/instant-start")
    public ResponseEntity<?> instantStartQuest(
            @PathVariable Kingdom kingdom,
            @RequestBody StartQuestRequest req,
            Authentication auth) {
        try {
            Player player = getPlayer(auth);
            KingdomService.CollectResult result = kingdomService.instantStartQuest(player, kingdom, req.questType());
            var resp = new java.util.HashMap<String, Object>();
            resp.put("bronzeEarned", result.bronzeEarned());
            resp.put("xpEarned",     result.xpEarned());
            resp.put("questId",      result.quest().getId());
            if (result.droppedItem() != null) {
                InventoryItem d = result.droppedItem();
                resp.put("droppedItem", Map.of(
                    "name",        d.getName(),
                    "type",        d.getType().name(),
                    "rarity",      d.getRarity(),
                    "attackBonus", d.getAttackBonus(),
                    "defenseBonus",d.getDefenseBonus(),
                    "healthBonus", d.getHealthBonus()
                ));
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Abandon quest ─────────────────────────────────────────────────────────
    @PostMapping("/{kingdom}/quests/{id}/abandon")
    public ResponseEntity<?> abandonQuest(
            @PathVariable Kingdom kingdom,
            @PathVariable Long id,
            Authentication auth) {
        try {
            kingdomService.abandonQuest(getPlayer(auth), id);
            return ResponseEntity.ok(Map.of("message", "Quest abandoned."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Training (Combat Kingdom) ─────────────────────────────────────────────
    @GetMapping("/COMBAT/training")
    public ResponseEntity<?> getCurrentTraining(Authentication auth) {
        Player player = getPlayer(auth);
        return kingdomService.getCurrentTraining(player)
                .map(s -> ResponseEntity.ok(trainingToMap(s)))
                .orElse(ResponseEntity.ok(Map.of("active", false)));
    }

    @PostMapping("/COMBAT/training/start")
    public ResponseEntity<?> startTraining(
            @RequestBody TrainingRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            TrainingSession session = kingdomService.startTraining(player, req.hours());
            return ResponseEntity.ok(trainingToMap(session));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/COMBAT/training/{id}/cancel")
    public ResponseEntity<?> cancelTraining(
            @PathVariable Long id, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            kingdomService.cancelTraining(player, id);
            return ResponseEntity.ok(Map.of("message", "Training cancelled. Warrior freed."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/COMBAT/training/{id}/collect")
    public ResponseEntity<?> collectTraining(
            @PathVariable Long id, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            TrainingSession session = kingdomService.collectTraining(player, id);
            return ResponseEntity.ok(Map.of(
                "message",   "Training complete! +" + session.getXpReward() + " XP",
                "xpEarned",  session.getXpReward()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private Map<String, Object> questToMap(KingdomActiveQuest q) {
        return Map.of(
            "id",              q.getId(),
            "kingdom",         q.getKingdom().name(),
            "questType",       q.getQuestType().name(),
            "displayName",     q.getQuestType().displayName,
            "status",          q.getStatus().name(),
            "bronzeReward",    q.getBronzeReward(),
            "expReward",       q.getExpReward(),
            "secondsRemaining",q.secondsRemaining(),
            "readyToCollect",  q.isReadyToCollect()
        );
    }

    private Map<String, Object> trainingToMap(TrainingSession s) {
        long secs = Math.max(0, java.time.temporal.ChronoUnit.SECONDS
                .between(java.time.LocalDateTime.now(), s.getFinishesAt()));
        return Map.of(
            "active",          true,
            "id",              s.getId(),
            "hours",           s.getHours(),
            "bronzeCost",      s.getBronzeCost(),
            "xpReward",        s.getXpReward(),
            "secondsRemaining",secs,
            "readyToCollect",  s.isReadyToCollect()
        );
    }

    record StartQuestRequest(KingdomQuestType questType) {}
    record TrainingRequest(int hours) {}
}
