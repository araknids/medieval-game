package com.medieval.game.repository;

import com.medieval.game.model.Guild;
import com.medieval.game.model.GuildWar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GuildWarRepository extends JpaRepository<GuildWar, Long> {

    @Query("SELECT w FROM GuildWar w WHERE (w.guildA = :g OR w.guildB = :g) AND w.status = :status")
    List<GuildWar> findByGuildAndStatus(@Param("g") Guild g, @Param("status") GuildWar.Status status);

    // [VARREDURA] Claim ATÔMICO da resolução: só UMA tx muda ACTIVE→RESOLVED (rowcount==1). Serializa via
    // lock de linha — protege o scheduler (multi-instância) E o lazy-resolve concorrente contra roubo 2×.
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("UPDATE GuildWar w SET w.status = :resolved WHERE w.id = :id AND w.status = :active")
    int claimForResolution(@Param("id") Long id, @Param("active") GuildWar.Status active, @Param("resolved") GuildWar.Status resolved);

    List<GuildWar> findByStatusAndEndsAtBefore(GuildWar.Status status, LocalDateTime now);

    default List<GuildWar> findActiveDue(LocalDateTime now) {
        return findByStatusAndEndsAtBefore(GuildWar.Status.ACTIVE, now);
    }

    default Optional<GuildWar> findActiveByGuild(Guild g) {
        return findByGuildAndStatus(g, GuildWar.Status.ACTIVE).stream().findFirst();
    }
}
