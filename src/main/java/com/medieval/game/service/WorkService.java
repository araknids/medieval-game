package com.medieval.game.service;

import com.medieval.game.enums.WorkStatus;
import com.medieval.game.enums.WorkType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.WorkSession;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
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

    private final WorkSessionRepository workRepository;
    private final WarriorRepository     warriorRepository;
    private final PlayerRepository      playerRepository;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    public Optional<WorkSession> getCurrentSession(Player player) {
        return workRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS);
    }

    @Transactional
    public WorkSession startWork(Player player, WorkType workType, int hours) {
        if (hours < 1 || hours > 12) {
            throw new IllegalArgumentException("Horas devem ser entre 1 e 12");
        }

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Guerreiro não encontrado"));

        if (warrior.isOnMission()) {
            throw new IllegalStateException("Seu guerreiro já está ocupado");
        }
        if (warrior.getWorkLevel() < workType.minWorkLevel) {
            throw new IllegalStateException(
                "Nível de trabalho insuficiente. Necessário: " + workType.minWorkLevel
                + ", seu nível: " + warrior.getWorkLevel()
            );
        }

        // Gold com bônus do nível de trabalho
        long goldReward = Math.round(workType.goldPerHour * hours * warrior.workGoldBonus());
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
                .orElseThrow(() -> new IllegalArgumentException("Sessão de trabalho não encontrada"));

        if (!session.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("Esta sessão não é sua");
        }
        if (session.getStatus() == WorkStatus.COLLECTED) {
            throw new IllegalStateException("Recompensa já coletada");
        }
        if (!session.isReadyToCollect()) {
            long minutesLeft = java.time.Duration.between(
                    LocalDateTime.now(), session.getFinishesAt()).toMinutes();
            throw new IllegalStateException("Trabalho ainda em andamento. Faltam ~" + minutesLeft + " minutos");
        }

        // Paga o gold
        player.setGold(player.getGold() + session.getGoldReward());
        playerRepository.save(player);

        // Adiciona XP de trabalho ao guerreiro
        warriorRepository.findByPlayer(player).ifPresent(warrior -> {
            warrior.setWorkExperience(warrior.getWorkExperience() + session.getXpReward());
            // Level up de trabalho
            while (warrior.getWorkExperience() >= warrior.workExpNeededForNextLevel()) {
                warrior.setWorkExperience(warrior.getWorkExperience() - warrior.workExpNeededForNextLevel());
                warrior.setWorkLevel(warrior.getWorkLevel() + 1);
            }
            warrior.setOnMission(false);
            warriorRepository.save(warrior);
        });

        session.setStatus(WorkStatus.COLLECTED);
        return workRepository.save(session);
    }
}
