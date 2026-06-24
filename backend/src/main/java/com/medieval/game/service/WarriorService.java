package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarriorService {

    private final WarriorRepository              warriorRepository;
    private final AchievementService             achievementService; // [TITULOS] desbloqueia título de nível

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
        warrior.setCurrentHpSnapshot(80);   // [ONBOARDING v3] recruta chega ferido (80%) → motiva a quest do Templo (curar)
        warrior.setHpUpdatedAt(java.time.LocalDateTime.now());
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

        warriorRepository.save(warrior);

        if (warrior.getLevel() > levelBefore) {
            log.info("[WarriorService] action=levelUp warriorId={} newLevel={}", warrior.getId(), warrior.getLevel());
            achievementService.checkAndUnlock(warrior.getPlayer(), true); // [TITULOS] título de nível desbloqueia na hora
        }
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

    @Transactional
    public Warrior spendPoint(Player player, Attribute attribute) {
        Warrior warrior = getWarrior(player);

        if (warrior.getAvailablePoints() <= 0) {
            throw new IllegalStateException("No attribute points available");
        }

        // Attribute caps enforcement — caps são POR CLASSE (Recruit/Warrior/Archer). [CLASSES]
        WarriorClass wc = warrior.getWarriorClass();
        int cap = wc.capFor(attribute);
        switch (attribute) {
            case STRENGTH     -> { if (warrior.getStrength()     >= cap) throw new com.medieval.game.config.LocalizedException("error.attr_at_cap", "{0} is at cap ({1}) for {2}.", "STR", cap, wc.displayName); warrior.setStrength(warrior.getStrength() + 1); }
            case DEXTERITY    -> { if (warrior.getDexterity()    >= cap) throw new com.medieval.game.config.LocalizedException("error.attr_at_cap", "{0} is at cap ({1}) for {2}.", "DEX", cap, wc.displayName); warrior.setDexterity(warrior.getDexterity() + 1); }
            case CONSTITUTION -> { if (warrior.getConstitution() >= cap) throw new com.medieval.game.config.LocalizedException("error.attr_at_cap", "{0} is at cap ({1}) for {2}.", "CON", cap, wc.displayName); warrior.setConstitution(warrior.getConstitution() + 1); } // RECRUIT/WARRIOR/ARCHER: ∞ (MAX_VALUE)
            case AGILITY      -> { if (warrior.getAgility()      >= cap) throw new com.medieval.game.config.LocalizedException("error.attr_at_cap", "{0} is at cap ({1}) for {2}.", "AGI", cap, wc.displayName); warrior.setAgility(warrior.getAgility() + 1); }
            case LUCK         -> { if (warrior.getLuck()         >= cap) throw new com.medieval.game.config.LocalizedException("error.attr_at_cap", "{0} is at cap ({1}) for {2}.", "LUK", cap, wc.displayName); warrior.setLuck(warrior.getLuck() + 1); }
            case INTELLECT    -> throw new IllegalStateException("Intellect is reserved — it has no effect yet."); // [INT_RESERVADO] S2/Mage
        }

        warrior.setAvailablePoints(warrior.getAvailablePoints() - 1);
        return warriorRepository.save(warrior);
    }

    /** Troca a postura de combate (toggle livre, sem custo). Vale em todo combate. [POSTURE] */
    @Transactional
    public Warrior setPosture(Player player, CombatPosture posture) {
        Warrior warrior = getWarrior(player);
        warrior.setCombatPosture(posture);
        log.info("[WarriorService] player={} action=setPosture posture={}", player.getId(), posture);
        return warriorRepository.save(warrior);
    }
}
