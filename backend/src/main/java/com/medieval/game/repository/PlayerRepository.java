package com.medieval.game.repository;

import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    Optional<Player> findByUsername(String username);

    // [HARDENING P2-3] Lock pessimista do player p/ serializar concessões de item concorrentes (mail
    // claim) — o cap da bag é check-then-act que o @Version NÃO cobre (o INSERT é na linha do ITEM, não
    // do player, e o claim não suja o player). Duas reivindicações concorrentes da mesma conta bloqueiam
    // aqui → a 2ª conta a bag DEPOIS da 1ª e respeita o cap. Claim é infrequente → custo do lock irrelevante.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Player p WHERE p.id = :id")
    Optional<Player> findByIdForUpdate(@Param("id") Long id);
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

    // [AUDITORIA_2 A6] Matchmaking de arena: 2 buscas indexáveis (logo abaixo / logo acima do rank),
    // cada uma usa o índice idx_players_rank_points + LIMIT (sem ABS → sem full scan/sort). Antes era
    // `ORDER BY ABS(rankPoints - :rank)` = scan+sort da tabela inteira em TODA luta. [era findOpponentsByRank / M14]
    @Query("SELECT p FROM Player p WHERE p.id <> :id AND p.rankPoints <= :rank ORDER BY p.rankPoints DESC")
    List<Player> findOpponentsBelow(@Param("id") Long id, @Param("rank") int rank, Pageable pageable);
    @Query("SELECT p FROM Player p WHERE p.id <> :id AND p.rankPoints >= :rank ORDER BY p.rankPoints ASC")
    List<Player> findOpponentsAbove(@Param("id") Long id, @Param("rank") int rank, Pageable pageable);

    // [PAGINACAO] Ranking da Torre paginado (só quem subiu: bestFloor > floor), limite/offset no banco.
    List<Player> findByTowerBestFloorGreaterThanOrderByTowerBestFloorDesc(int floor, Pageable pageable);

    // [LEADERBOARDS] Rankings paginados (índice/limit no DB, payload pequeno).
    @Query("SELECT p FROM Player p ORDER BY p.rankPoints DESC")
    List<Player> findTopByRankPoints(Pageable pageable);

    @Query("SELECT p FROM Player p ORDER BY p.playerKills DESC")
    List<Player> findTopByPlayerKills(Pageable pageable);

    // Riqueza = total em bronze (bronze + prata×100 + ouro×10000). Ordena pela expressão no DB.
    @Query("SELECT p FROM Player p ORDER BY (p.bronze + p.silver * 100 + p.gold * 10000) DESC")
    List<Player> findTopByWealth(Pageable pageable);

    // PvP por flag: players atualmente expostos numa zona (vítimas potenciais de raid). [PVP_FLAG]
    @Query("SELECT p FROM Player p WHERE p.pvpFlaggedZone = :zone AND p.pvpFlaggedUntil > :now AND p.id <> :excludeId")
    List<Player> findFlaggedInZone(@Param("zone") com.medieval.game.enums.Zone zone,
                                   @Param("now") java.time.LocalDateTime now,
                                   @Param("excludeId") Long excludeId);
}
