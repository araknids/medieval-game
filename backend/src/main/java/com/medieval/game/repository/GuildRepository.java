package com.medieval.game.repository;

import com.medieval.game.model.Guild;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GuildRepository extends JpaRepository<Guild, Long> {
    Optional<Guild> findByName(String name);
    boolean existsByName(String name);
    List<Guild> findAllByOrderByLevelDescGoldDesc();
}
