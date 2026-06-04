package com.medieval.game.repository;

import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import org.springframework.data.domain.Pageable;
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

    // M6: lê só o instante de invalidação de token (projeção leve, 1 coluna) p/ o filtro JWT.
    // Optional vazio = sem restrição (valor null) OU player inexistente — ambos tratados como "sem trava".
    @Query("SELECT p.tokenValidFrom FROM Player p WHERE p.id = :playerId")
    Optional<java.time.LocalDateTime> findTokenValidFrom(@Param("playerId") Long playerId);

    // Matchmaking de arena: candidatos mais próximos em rank (limitado no banco,
    // não carrega todos os jogadores). [AUDITORIA M14]
    @Query("SELECT p FROM Player p WHERE p.id <> :id ORDER BY ABS(p.rankPoints - :rank)")
    List<Player> findOpponentsByRank(@Param("id") Long id, @Param("rank") int rank, Pageable pageable);

    // Ranking da Torre, limitado no banco. [AUDITORIA M14]
    List<Player> findTop20ByOrderByTowerBestFloorDesc();
}
