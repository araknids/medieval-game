package com.medieval.game.repository;

import com.medieval.game.model.GuildInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GuildInviteRepository extends JpaRepository<GuildInvite, Long> {

    List<GuildInvite> findByInviteeIdAndStatus(Long inviteeId, String status);           // recebidos
    Optional<GuildInvite> findByGuildIdAndInviteeIdAndStatus(Long guildId, Long inviteeId, String status); // dup check
}
