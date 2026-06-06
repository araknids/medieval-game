package com.medieval.game.repository;

import com.medieval.game.enums.PetType;
import com.medieval.game.model.Pet;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PetRepository extends JpaRepository<Pet, Long> {
    List<Pet>     findByPlayer(Player player);
    Optional<Pet> findByPlayerAndEquippedTrue(Player player);
    Optional<Pet> findByPlayerAndPetType(Player player, PetType petType);
    boolean       existsByPlayerAndPetType(Player player, PetType petType);
}
