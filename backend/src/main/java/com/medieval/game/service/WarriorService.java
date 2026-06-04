package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarriorService {

    private final WarriorRepository              warriorRepository;
    private final ActiveQuestRepository          questRepository;
    private final WorkSessionRepository          workRepository;
    private final GatheringSessionRepository     gatheringRepository;
    private final ArenaMatchRepository           arenaRepository;
    private final TowerRunRepository             towerRepository;
    private final TrainingSessionRepository      trainingRepository;
    private final KingdomActiveQuestRepository   kingdomQuestRepository;
    private final ZoneActivityRepository         zoneActivityRepository;

    @Transactional
    public Warrior create(Player player, String name, WarriorClass warriorClass) {
        log.info("[WarriorService] player={} action=create name={} class={}", player.getId(), name, warriorClass);
        Warrior warrior = new Warrior();
        warrior.setPlayer(player);
        warrior.setName(name);
        warrior.setWarriorClass(warriorClass);
        warrior.setAttack(warriorClass.baseAttack);
        warrior.setDefense(warriorClass.baseDefense);
        warrior.setHealth(warriorClass.baseHealth);
        Warrior saved = warriorRepository.save(warrior);
        log.info("[WarriorService] player={} action=create OK warriorId={}", player.getId(), saved.getId());
        return saved;
    }

    public Warrior getWarrior(Player player) {
        return warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found for this player"));
    }

    @Transactional
    public void addExperience(Warrior warrior, long exp) {
        int levelBefore = warrior.getLevel();
        warrior.setExperience(warrior.getExperience() + exp);

        while (warrior.getExperience() >= warrior.expNeededForNextLevel()) {
            warrior.setExperience(warrior.getExperience() - warrior.expNeededForNextLevel());
            warrior.levelUp();
        }

        if (warrior.getLevel() > levelBefore) {
            log.info("[WarriorService] action=levelUp warriorId={} newLevel={}", warrior.getId(), warrior.getLevel());
        }

        warriorRepository.save(warrior);
    }

    /**
     * Tibia-style XP loss on PvP death: loses 10% of XP required for current level.
     * Can drop levels (minimum: level 1). XP within the dropped level is preserved.
     */
    @Transactional
    public void loseXp(Warrior warrior, long xpLost) {
        log.info("[WarriorService] warriorId={} action=loseXp amount={}", warrior.getId(), xpLost);
        long currentXp = warrior.getExperience();
        long remaining = currentXp - xpLost;

        while (remaining < 0 && warrior.getLevel() > 1) {
            warrior.setLevel(warrior.getLevel() - 1);
            long threshold = warrior.expNeededForNextLevel(); // XP needed for the new (lower) level
            remaining += threshold; // carry over: was deficit, now partway through lower level
        }

        warrior.setExperience(Math.max(0, remaining));
        warriorRepository.save(warrior);
        log.info("[WarriorService] warriorId={} action=loseXp OK newLevel={} newXp={}",
                warrior.getId(), warrior.getLevel(), warrior.getExperience());
    }

    /** Libera o guerreiro e cancela todas as sessões ativas (emergência de suporte) */
    @Transactional
    public boolean freeIfStuck(Player player) {
        log.info("[WarriorService] player={} action=freeIfStuck", player.getId());
        Warrior warrior = getWarrior(player);
        if (!warrior.isOnMission()) return false;

        // Cancela quest ativa
        questRepository.findAllByPlayerAndStatusNot(player, QuestStatus.COLLECTED)
                .forEach(q -> {
                    if (q.getStatus() == QuestStatus.IN_PROGRESS) {
                        q.setStatus(QuestStatus.ABANDONED);
                        questRepository.save(q);
                        log.info("[WarriorService] player={} action=freeIfStuck cancelled quest id={}", player.getId(), q.getId());
                    }
                });

        // Cancela sessão de trabalho
        workRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS)
                .ifPresent(w -> {
                    w.setStatus(WorkStatus.CANCELLED);
                    workRepository.save(w);
                    log.info("[WarriorService] player={} action=freeIfStuck cancelled workSession id={}", player.getId(), w.getId());
                });

        // Cancela sessão de coleta (pesca/mineração)
        gatheringRepository.findByPlayerAndStatus(player, GatheringStatus.IN_PROGRESS)
                .ifPresent(g -> {
                    g.setStatus(GatheringStatus.CANCELLED);
                    gatheringRepository.save(g);
                    log.info("[WarriorService] player={} action=freeIfStuck cancelled gatheringSession id={}", player.getId(), g.getId());
                });

        // Cancela batalha na arena
        arenaRepository.findByChallengerAndStatus(player, MatchStatus.FIGHTING)
                .ifPresent(a -> {
                    a.setStatus(MatchStatus.COLLECTED);
                    arenaRepository.save(a);
                    log.info("[WarriorService] player={} action=freeIfStuck cancelled arenaMatch id={}", player.getId(), a.getId());
                });

        // Cancel tower run
        towerRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS)
                .ifPresent(t -> {
                    t.setStatus(TowerStatus.EXITED);
                    towerRepository.save(t);
                    log.info("[WarriorService] player={} action=freeIfStuck cancelled towerRun id={}", player.getId(), t.getId());
                });

        // Cancel orphaned zone activity (expedition/zone session)
        zoneActivityRepository.findByPlayerAndStatus(player, com.medieval.game.enums.ZoneActivityStatus.IN_PROGRESS)
                .ifPresent(z -> {
                    z.setStatus(com.medieval.game.enums.ZoneActivityStatus.CANCELLED);
                    zoneActivityRepository.save(z);
                    log.info("[WarriorService] player={} action=freeIfStuck cancelled zoneActivity id={}", player.getId(), z.getId());
                });

        // Cancel active training session (Combat Kingdom)
        trainingRepository.findByPlayerAndStatus(player, com.medieval.game.enums.TrainingStatus.IN_PROGRESS)
                .ifPresent(t -> {
                    t.setStatus(com.medieval.game.enums.TrainingStatus.CANCELLED);
                    trainingRepository.save(t);
                    log.info("[WarriorService] player={} action=freeIfStuck cancelled trainingSession id={}", player.getId(), t.getId());
                });

        // Cancel active kingdom quests
        kingdomQuestRepository.findByPlayerAndStatusNotOrderByStartedAtDesc(player, QuestStatus.COLLECTED)
                .forEach(q -> {
                    if (q.getStatus() == QuestStatus.IN_PROGRESS) {
                        q.setStatus(QuestStatus.ABANDONED);
                        kingdomQuestRepository.save(q);
                        log.info("[WarriorService] player={} action=freeIfStuck cancelled kingdomQuest id={}", player.getId(), q.getId());
                    }
                });

        warrior.setOnMission(false);
        warriorRepository.save(warrior);
        log.info("[WarriorService] player={} action=freeIfStuck OK warrior released", player.getId());
        return true;
    }

    @Transactional
    public Warrior spendPoint(Player player, Attribute attribute) {
        Warrior warrior = getWarrior(player);

        if (warrior.getAvailablePoints() <= 0) {
            throw new IllegalStateException("No attribute points available");
        }

        // Attribute caps enforcement
        switch (attribute) {
            case STRENGTH     -> { if (warrior.getStrength()     >= 60) throw new IllegalStateException("STR is at cap (60).");     warrior.setStrength(warrior.getStrength() + 1); }
            case DEXTERITY    -> { if (warrior.getDexterity()    >= 40) throw new IllegalStateException("DEX is at cap (40).");     warrior.setDexterity(warrior.getDexterity() + 1); }
            case CONSTITUTION -> warrior.setConstitution(warrior.getConstitution() + 1); // no cap
            case LUCK         -> { if (warrior.getLuck()         >= 50) throw new IllegalStateException("LUK is at cap (50).");     warrior.setLuck(warrior.getLuck() + 1); }
            case INTELLECT    -> { if (warrior.getIntellect()    >= 40) throw new IllegalStateException("INT is at cap (40).");     warrior.setIntellect(warrior.getIntellect() + 1); }
        }

        warrior.setAvailablePoints(warrior.getAvailablePoints() - 1);
        return warriorRepository.save(warrior);
    }
}
