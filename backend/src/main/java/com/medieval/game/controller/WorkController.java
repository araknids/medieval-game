package com.medieval.game.controller;

import com.medieval.game.enums.WorkType;
import com.medieval.game.model.Player;
import com.medieval.game.model.WorkProfession;
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

    @GetMapping("/jobs")
    public ResponseEntity<List<?>> getJobs(Authentication auth) {
        Player  player  = getPlayer(auth);
        var     warrior = warriorRepository.findByPlayer(player).orElse(null);
        boolean busy    = warrior != null && warrior.isOnMission();
        int warriorLevel = warrior != null ? warrior.getLevel() : 1; // nível do personagem

        var jobs = Arrays.stream(WorkType.values()).map(wt -> {
            WorkProfession prof = workService.getProfession(player, wt);
            int    profLevel    = prof.getLevel();
            long   profXp       = prof.getExperience();
            long   profXpNeeded = prof.expNeededForNextLevel();
            double bonus        = prof.goldBonus();
            int    bonusPct     = (int) Math.round((bonus - 1.0) * 100);
            // Unlock based on warrior level, not profession level
            boolean meetsLevelReq = warriorLevel >= wt.minWorkLevel;
            boolean available     = meetsLevelReq && !busy;

            return Map.ofEntries(
                Map.entry("id",                   wt.name()),
                Map.entry("displayName",          wt.displayName),
                Map.entry("description",          wt.description),
                Map.entry("goldPerHour",          wt.goldPerHour),
                Map.entry("minWorkLevel",         wt.minWorkLevel),
                Map.entry("xpPerHour",            wt.xpPerHour),
                Map.entry("profLevel",            profLevel),
                Map.entry("profXp",               profXp),
                Map.entry("profXpNeeded",         profXpNeeded),
                Map.entry("bonusPct",             bonusPct),
                Map.entry("available",            available),
                Map.entry("meetsLevelReq",        meetsLevelReq),
                Map.entry("goldPerHourWithBonus", (long) Math.round(wt.goldPerHour * bonus))
            );
        }).toList();

        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrent(Authentication auth) {
        Player player = getPlayer(auth);
        Optional<WorkSession> session = workService.getCurrentSession(player);
        if (session.isEmpty()) return ResponseEntity.ok(Map.of("active", false));
        return ResponseEntity.ok(WorkResponse.from(session.get()));
    }

    @PostMapping("/start")
    public ResponseEntity<?> startWork(@Valid @RequestBody StartWorkRequest req, Authentication auth) {
        Player      player  = getPlayer(auth);
        WorkSession session = workService.startWork(player, req.workType(), req.hours());
        return ResponseEntity.ok(WorkResponse.from(session));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id, Authentication auth) {
        Player      player  = getPlayer(auth);
        WorkSession session = workService.cancelWork(player, id);
        return ResponseEntity.ok(Map.of(
                "goldEarned", session.getGoldReward(),
                "xpEarned",   session.getXpReward(),
                "cancelled",  true
        ));
    }

    @PostMapping("/{id}/collect")
    public ResponseEntity<?> collect(@PathVariable Long id, Authentication auth) {
        Player      player  = getPlayer(auth);
        WorkSession session = workService.collectWork(player, id);
        return ResponseEntity.ok(Map.of(
                "goldEarned", session.getGoldReward(),
                "xpEarned",   session.getXpReward(),
                "jobName",    session.getWorkType().displayName
        ));
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
