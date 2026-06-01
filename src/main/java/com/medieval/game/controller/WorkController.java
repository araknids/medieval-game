package com.medieval.game.controller;

import com.medieval.game.enums.WorkType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.WorkSession;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.WorkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import java.util.Optional;

@RestController
@RequestMapping("/api/work")
@RequiredArgsConstructor
public class WorkController {

    private final WorkService       workService;
    private final PlayerService     playerService;
    private final WarriorRepository warriorRepository;

    // Lista todos os empregos com status de disponibilidade
    @GetMapping("/jobs")
    public ResponseEntity<List<?>> getJobs(Authentication auth) {
        Player  player  = getPlayer(auth);
        Warrior warrior = warriorRepository.findByPlayer(player).orElse(null);
        int workLevel   = warrior != null ? warrior.getWorkLevel() : 1;
        double bonus    = warrior != null ? warrior.workGoldBonus() : 1.0;
        boolean busy    = warrior != null && warrior.isOnMission();

        var jobs = Arrays.stream(WorkType.values()).map(wt -> Map.of(
                "id",           wt.name(),
                "displayName",  wt.displayName,
                "description",  wt.description,
                "goldPerHour",  wt.goldPerHour,
                "minWorkLevel", wt.minWorkLevel,
                "xpPerHour",    wt.xpPerHour,
                "available",    workLevel >= wt.minWorkLevel && !busy,
                "goldPerHourWithBonus", (long) Math.round(wt.goldPerHour * bonus)
        )).toList();
        return ResponseEntity.ok(jobs);
    }

    // Sessão de trabalho ativa
    @GetMapping("/current")
    public ResponseEntity<?> getCurrent(Authentication auth) {
        Player player = getPlayer(auth);
        Optional<WorkSession> session = workService.getCurrentSession(player);
        if (session.isEmpty()) return ResponseEntity.ok(Map.of("active", false));
        return ResponseEntity.ok(WorkResponse.from(session.get()));
    }

    // Começa a trabalhar
    @PostMapping("/start")
    public ResponseEntity<?> startWork(@Valid @RequestBody StartWorkRequest req, Authentication auth) {
        try {
            Player      player  = getPlayer(auth);
            WorkSession session = workService.startWork(player, req.workType(), req.hours());
            return ResponseEntity.ok(WorkResponse.from(session));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Cancela trabalho (recebe proporcional às horas completas)
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        try {
            Player      player  = getPlayer(auth);
            WorkSession session = workService.cancelWork(player, id);
            return ResponseEntity.ok(Map.of(
                    "goldEarned", session.getGoldReward(),
                    "xpEarned",   session.getXpReward(),
                    "cancelled",  true
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Coleta recompensa
    @PostMapping("/{id}/collect")
    public ResponseEntity<?> collect(@PathVariable Long id, Authentication auth) {
        try {
            Player      player  = getPlayer(auth);
            WorkSession session = workService.collectWork(player, id);
            return ResponseEntity.ok(Map.of(
                    "goldEarned", session.getGoldReward(),
                    "xpEarned",   session.getXpReward(),
                    "jobName",    session.getWorkType().displayName
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    record StartWorkRequest(@NotNull WorkType workType, @Min(1) @Max(12) int hours) {}

    record WorkResponse(Long id, String jobName, String description, int hours,
                        long goldReward, int xpReward, long secondsRemaining,
                        boolean readyToCollect) {
        static WorkResponse from(WorkSession s) {
            long secs = Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), s.getFinishesAt()));
            return new WorkResponse(s.getId(), s.getWorkType().displayName,
                    s.getWorkType().description, s.getHours(),
                    s.getGoldReward(), s.getXpReward(), secs, s.isReadyToCollect());
        }
    }
}
