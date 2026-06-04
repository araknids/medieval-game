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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkService {

    private final WorkSessionRepository    workRepository;
    private final WorkProfessionRepository professionRepository;
    private final WarriorRepository        warriorRepository;
    private final PlayerRepository         playerRepository;
    private final TerritoryService         territoryService;
    private final ConcurrentEntityCreator  entityCreator;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // Retorna ou cria o registro de profissão para o jogador
    public WorkProfession getProfession(Player player, WorkType workType) {
        return professionRepository.findByPlayerAndWorkType(player, workType)
                .orElseGet(() -> {
                    try {
                        return entityCreator.createProfession(player, workType); // tx própria (REQUIRES_NEW)
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        // criada concorrentemente por outra requisição → relê a linha existente. [AUDITORIA M15]
                        return professionRepository.findByPlayerAndWorkType(player, workType).orElseThrow();
                    }
                });
    }

    public Optional<WorkSession> getCurrentSession(Player player) {
        return workRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS);
    }

    @Transactional
    public WorkSession startWork(Player player, WorkType workType, int hours) {
        log.info("[WorkService] player={} action=startWork workType={} hours={}", player.getId(), workType, hours);
        if (hours < 1 || hours > 12) {
            log.warn("[WorkService] player={} REJECTED: invalid hours={}", player.getId(), hours);
            throw new IllegalArgumentException("Hours must be between 1 and 12");
        }

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.isOnMission()) {
            log.warn("[WorkService] player={} REJECTED: warrior is already busy", player.getId());
            throw new IllegalStateException("Your warrior is already busy");
        }

        if (workRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS).isPresent()) {
            log.warn("[WorkService] player={} REJECTED: already working", player.getId());
            throw new IllegalStateException("You are already working");
        }

        // Valida nível mínimo com o nível do personagem (guerreiro)
        if (warrior.getLevel() < workType.minWorkLevel) {
            log.warn("[WorkService] player={} REJECTED: warrior level {} too low for {} (required {})", player.getId(), warrior.getLevel(), workType, workType.minWorkLevel);
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
        WorkSession saved = workRepository.save(session);
        log.info("[WorkService] player={} action=startWork OK id={} goldReward={}", player.getId(), saved.getId(), goldReward);
        return saved;
    }

    @Transactional
    public WorkSession collectWork(Player player, Long sessionId) {
        log.info("[WorkService] player={} action=collectWork sessionId={}", player.getId(), sessionId);
        WorkSession session = workRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (!session.getPlayer().getId().equals(player.getId())) {
            log.warn("[WorkService] player={} REJECTED: session {} does not belong to this player", player.getId(), sessionId);
            throw new IllegalStateException("This session does not belong to you");
        }
        if (session.getStatus() == WorkStatus.COLLECTED) {
            log.warn("[WorkService] player={} REJECTED: session {} reward already collected", player.getId(), sessionId);
            throw new IllegalStateException("Reward already collected");
        }
        if (!session.isReadyToCollect()) {
            long mins = java.time.Duration.between(LocalDateTime.now(), session.getFinishesAt()).toMinutes();
            log.warn("[WorkService] player={} REJECTED: session {} still in progress, ~{}min remaining", player.getId(), sessionId, mins);
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
        WorkSession result = workRepository.save(session);
        log.info("[WorkService] player={} action=collectWork OK sessionId={} bronze={}", player.getId(), sessionId, totalBronze);
        return result;
    }

    @Transactional
    public WorkSession cancelWork(Player player, Long sessionId) {
        log.info("[WorkService] player={} action=cancelWork sessionId={}", player.getId(), sessionId);
        WorkSession session = workRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (!session.getPlayer().getId().equals(player.getId())) {
            log.warn("[WorkService] player={} REJECTED: session {} does not belong to this player", player.getId(), sessionId);
            throw new IllegalStateException("This session does not belong to you");
        }
        if (session.getStatus() != WorkStatus.IN_PROGRESS) {
            log.warn("[WorkService] player={} REJECTED: session {} already finished (status={})", player.getId(), sessionId, session.getStatus());
            throw new IllegalStateException("Work already finished");
        }

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
        WorkSession cancelled = workRepository.save(session);
        log.info("[WorkService] player={} action=cancelWork OK sessionId={} hoursCompleted={}", player.getId(), sessionId, hoursCompleted);
        return cancelled;
    }
}
