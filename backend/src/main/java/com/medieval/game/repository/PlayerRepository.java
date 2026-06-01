package com.medieval.game.repository;

import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByUsername(String username);
    Optional<Player> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<Player> findAllByGuild(Guild guild);
    int countByGuild(Guild guild);

    // Busca guilda do player sem passar pelo proxy lazy
    @Query("SELECT p.guild FROM Player p WHERE p.id = :playerId")
    Optional<Guild> findGuildByPlayerId(@Param("playerId") Long playerId);
}
