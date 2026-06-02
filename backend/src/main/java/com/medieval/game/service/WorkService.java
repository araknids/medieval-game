package com.medieval.game.service;

import com.medieval.game.enums.WorkStatus;
import com.medieval.game.enums.WorkType;
import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.WorkProfession;
import com.medieval.game.model.WorkSession;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.repository.WorkProfessionRepository;
import com.medieval.game.repository.WorkSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkService {

    private final WorkSessionRepository    workRepository;
    private final WorkProfessionRepository professionRepository;
    private final WarriorRepository        warriorRepository;
    private final PlayerRepository         playerRepository;
    private final TerritoryService         territoryService;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // Retorna ou cria o registro de profissão para o jogador
    public WorkProfession getProfession(Player player, WorkType workType) {
        return professionRepository.findByPlayerAndWorkType(player, workType)
                .orElseGet(() -> {
                    WorkProfession p = new WorkProfession();
                    p.setPlayer(player);
                    p.setWorkType(workType);
                    return professionRepository.save(p);
                });
    }

    public Optional<WorkSession> getCurrentSession(Player player) {
        return workRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS);
    }

    @Transactional
    public WorkSession startWork(Player player, WorkType workType, int hours) {
        if (hours < 1 || hours > 12) {
            throw new IllegalArgumentException("Hours must be between 1 and 12");
        }

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.isOnMission()) {
            throw new IllegalStateException("Your warrior is already busy");
        }

        if (workRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS).isPresent()) {
            throw new IllegalStateException("You are already working");
        }

        // Valida nível mínimo com o nível do personagem (guerreiro)
        if (warrior.getLevel() < workType.minWorkLevel) {
            throw new IllegalStateException(
                "Warrior level too low for " + workType.displayName +
                ". Required: level " + workType.minWorkLevel +
                ", your level: " + warrior.getLevel()
            );
        }

        WorkProfession profession = getProfession(player, workType);

        long goldReward = Math.round(workType.goldPerHour * hours * profession.goldBonus());
        int  xpReward   = workType.xpPerHour * hours;

        warrior.setOnMission(true);
        warriorRepository.save(warrior);

        WorkSession session = new WorkSession();
        session.setPlayer(player);
        session.setWorkType(workType);
        session.setHours(hours);
        session.setGoldReward(goldReward);
        session.setXpReward(xpReward);
        session.setStartedAt(LocalDateTime.now());
        session.setFinishesAt(instantComplete
                ? LocalDateTime.now()
                : LocalDateTime.now().plusHours(hours));
        return workRepository.save(session);
    }

    @Transactional
    public WorkSession collectWork(Player player, Long sessionId) {
        WorkSession session = workRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (!session.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This session does not belong to you");
        if (session.getStatus() == WorkStatus.COLLECTED)
            throw new IllegalStateException("Reward already collected");
        if (!session.isReadyToCollect()) {
            long mins = java.time.Duration.between(LocalDateTime.now(), session.getFinishesAt()).toMinutes();
            throw new IllegalStateException("Work in progress. ~" + mins + " minutes remaining");
        }

        // Apply guild + territory passive bonuses
        Guild guild   = playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
        int xpPct     = guild != null ? guild.xpBonus()    : 0;
        int bronzePct = guild != null ? guild.bronzeBonus() : 0;

        TerritoryService.TerritoryBonus terr = territoryService.getBonusForPlayer(player);
        xpPct     += terr.xpBonus();
        bronzePct += terr.bronzeBonus();

        long totalBronze = session.getGoldReward() + Math.round(session.getGoldReward() * bronzePct / 100.0);
        int  bonusXp     = (int) Math.round(session.getXpReward() * xpPct / 100.0);

        player.addBronzeAmount(totalBronze);
        playerRepository.save(player);

        // Add XP (with guild bonus) to the specific profession
        WorkProfession profession = getProfession(player, session.getWorkType());
        profession.setExperience(profession.getExperience() + session.getXpReward() + bonusXp);
        while (profession.getExperience() >= profession.expNeededForNextLevel()) {
            profession.setExperience(profession.getExperience() - profession.expNeededForNextLevel());
            profession.setLevel(profession.getLevel() + 1);
        }
        professionRepository.save(profession);

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        session.setStatus(WorkStatus.COLLECTED);
        return workRepository.save(session);
    }

    @Transactional
    public WorkSession cancelWork(Player player, Long sessionId) {
        WorkSession session = workRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (!session.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This session does not belong to you");
        if (session.getStatus() != WorkStatus.IN_PROGRESS)
            throw new IllegalStateException("Work already finished");

        long hoursCompleted = Math.min(
            java.time.Duration.between(session.getStartedAt(), LocalDateTime.now()).toHours(),
            session.getHours()
        );

        if (hoursCompleted > 0) {
            long goldEarned = Math.round(session.getGoldReward() * hoursCompleted / (double) session.getHours());
            int  xpEarned   = (int)(session.getXpReward()        * hoursCompleted / (double) session.getHours());

            player.addBronzeAmount(goldEarned);
            playerRepository.save(player);

            WorkProfession profession = getProfession(player, session.getWorkType());
            profession.setExperience(profession.getExperience() + xpEarned);
            while (profession.getExperience() >= profession.expNeededForNextLevel()) {
                profession.setExperience(profession.getExperience() - profession.expNeededForNextLevel());
                profession.setLevel(profession.getLevel() + 1);
            }
            professionRepository.save(profession);

            session.setGoldReward(goldEarned);
            session.setXpReward(xpEarned);
        } else {
            session.setGoldReward(0);
            session.setXpReward(0);
        }

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        session.setStatus(WorkStatus.CANCELLED);
        return workRepository.save(session);
    }
}
