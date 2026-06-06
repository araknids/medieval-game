package com.medieval.game.repository;

import com.medieval.game.enums.ClassAbility;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.WarriorAbility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarriorAbilityRepository extends JpaRepository<WarriorAbility, Long> {
    List<WarriorAbility> findByWarrior(Warrior warrior);
    Optional<WarriorAbility> findByWarriorAndAbility(Warrior warrior, ClassAbility ability);
    void deleteByWarrior(Warrior warrior);
}
