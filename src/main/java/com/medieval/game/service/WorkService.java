package com.medieval.game.service;

import com.medieval.game.enums.WorkStatus;
import com.medieval.game.enums.WorkType;
import com.medieval.game.model.Player;
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
            throw new IllegalArgumentException("Horas devem ser entre 1 e 12");
        }

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            if (w.isOnMission()) throw new IllegalStateException("Seu guerreiro já está ocupado");
        });

        if (workRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS).isPresent()) {
            throw new IllegalStateException("Você já está trabalhando");
        }

        // Valida nível mínimo da profissão
        WorkProfession profession = getProfession(player, workType);
        if (profession.getLevel() < workType.minWorkLevel) {
            throw new IllegalStateException(
                "Nível " + workType.displayName + " insuficiente. " +
                "Necessário: " + workType.minWorkLevel + ", seu nível: " + profession.getLevel()
            );
        }

        long goldReward = Math.round(workType.goldPerHour * hours * profession.goldBonus());
        int  xpReward   = workType.xpPerHour * hours;

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(true);
            warriorRepository.save(w);
        });

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
                .orElseThrow(() -> new IllegalArgumentException("Sessão não encontrada"));

        if (!session.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Esta sessão não é sua");
        if (session.getStatus() == WorkStatus.COLLECTED)
            throw new IllegalStateException("Recompensa já coletada");
        if (!session.isReadyToCollect()) {
            long mins = java.time.Duration.between(LocalDateTime.now(), session.getFinishesAt()).toMinutes();
            throw new IllegalStateException("Trabalho em andamento. Faltam ~" + mins + " minutos");
        }

        player.setGold(player.getGold() + session.getGoldReward());
        playerRepository.save(player);

        // Adiciona XP à profissão específica
        WorkProfession profession = getProfession(player, session.getWorkType());
        profession.setExperience(profession.getExperience() + session.getXpReward());
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
                .orElseThrow(() -> new IllegalArgumentException("Sessão não encontrada"));

        if (!session.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Esta sessão não é sua");
        if (session.getStatus() != WorkStatus.IN_PROGRESS)
            throw new IllegalStateException("Trabalho já finalizado");

        long hoursCompleted = Math.min(
            java.time.Duration.between(session.getStartedAt(), LocalDateTime.now()).toHours(),
            session.getHours()
        );

        if (hoursCompleted > 0) {
            long goldEarned = Math.round(session.getGoldReward() * hoursCompleted / (double) session.getHours());
            int  xpEarned   = (int)(session.getXpReward()        * hoursCompleted / (double) session.getHours());

            player.setGold(player.getGold() + goldEarned);
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
