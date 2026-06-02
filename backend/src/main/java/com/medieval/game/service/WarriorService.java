package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        Warrior warrior = new Warrior();
        warrior.setPlayer(player);
        warrior.setName(name);
        warrior.setWarriorClass(warriorClass);
        warrior.setAttack(warriorClass.baseAttack);
        warrior.setDefense(warriorClass.baseDefense);
        warrior.setHealth(warriorClass.baseHealth);
        return warriorRepository.save(warrior);
    }

    public Warrior getWarrior(Player player) {
        return warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found para este jogador"));
    }

    @Transactional
    public void addExperience(Warrior warrior, long exp) {
        warrior.setExperience(warrior.getExperience() + exp);

        while (warrior.getExperience() >= warrior.expNeededForNextLevel()) {
            warrior.setExperience(warrior.getExperience() - warrior.expNeededForNextLevel());
            warrior.levelUp();
        }

        warriorRepository.save(warrior);
    }

    /** Libera o guerreiro e cancela todas as sessões ativas (emergência de suporte) */
    @Transactional
    public boolean freeIfStuck(Player player) {
        Warrior warrior = getWarrior(player);
        if (!warrior.isOnMission()) return false;

        // Cancela quest ativa
        questRepository.findAllByPlayerAndStatusNot(player, QuestStatus.COLLECTED)
                .forEach(q -> {
                    if (q.getStatus() == QuestStatus.IN_PROGRESS) {
                        q.setStatus(QuestStatus.ABANDONED);
                        questRepository.save(q);
                    }
                });

        // Cancela sessão de trabalho
        workRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS)
                .ifPresent(w -> { w.setStatus(WorkStatus.CANCELLED); workRepository.save(w); });

        // Cancela sessão de coleta (pesca/mineração)
        gatheringRepository.findByPlayerAndStatus(player, GatheringStatus.IN_PROGRESS)
                .ifPresent(g -> { g.setStatus(GatheringStatus.CANCELLED); gatheringRepository.save(g); });

        // Cancela batalha na arena
        arenaRepository.findByChallengerAndStatus(player, MatchStatus.FIGHTING)
                .ifPresent(a -> { a.setStatus(MatchStatus.COLLECTED); arenaRepository.save(a); });

        // Cancel tower run
        towerRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS)
                .ifPresent(t -> { t.setStatus(TowerStatus.EXITED); towerRepository.save(t); });

        // Cancel orphaned zone activity (expedition/zone session)
        zoneActivityRepository.findByPlayerAndStatus(player, com.medieval.game.enums.ZoneActivityStatus.IN_PROGRESS)
                .ifPresent(z -> { z.setStatus(com.medieval.game.enums.ZoneActivityStatus.CANCELLED); zoneActivityRepository.save(z); });

        // Cancel active training session (Combat Kingdom)
        trainingRepository.findByPlayerAndStatus(player, com.medieval.game.enums.TrainingStatus.IN_PROGRESS)
                .ifPresent(t -> { t.setStatus(com.medieval.game.enums.TrainingStatus.CANCELLED); trainingRepository.save(t); });

        // Cancel active kingdom quests
        kingdomQuestRepository.findByPlayerAndStatusNotOrderByStartedAtDesc(player, QuestStatus.COLLECTED)
                .forEach(q -> {
                    if (q.getStatus() == QuestStatus.IN_PROGRESS) {
                        q.setStatus(QuestStatus.ABANDONED);
                        kingdomQuestRepository.save(q);
                    }
                });

        warrior.setOnMission(false);
        warriorRepository.save(warrior);
        return true;
    }

    @Transactional
    public Warrior spendPoint(Player player, Attribute attribute) {
        Warrior warrior = getWarrior(player);

        if (warrior.getAvailablePoints() <= 0) {
            throw new IllegalStateException("No attribute points available");
        }

        switch (attribute) {
            case STRENGTH     -> warrior.setStrength(warrior.getStrength() + 1);
            case DEXTERITY    -> warrior.setDexterity(warrior.getDexterity() + 1);
            case CONSTITUTION -> warrior.setConstitution(warrior.getConstitution() + 1);
            case LUCK         -> warrior.setLuck(warrior.getLuck() + 1);
        }

        warrior.setAvailablePoints(warrior.getAvailablePoints() - 1);
        return warriorRepository.save(warrior);
    }
}
