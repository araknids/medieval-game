package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [LEADERBOARDS] Convite de guilda (caminho paralelo ao join aberto). O líder convida → o convidado
 * aceita → entra (via GuildService.join, que respeita capacidade + lock). Status String
 * (PENDING/ACCEPTED/DECLINED). Tabela auto-criada pelo ddl-auto.
 */
@Entity
@Table(name = "guild_invites")
@Getter
@Setter
@NoArgsConstructor
public class GuildInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "inviter_id", nullable = false)
    private Long inviterId;

    @Column(name = "invitee_id", nullable = false)
    private Long inviteeId;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public GuildInvite(Long guildId, Long inviterId, Long inviteeId, String status) {
        this.guildId = guildId;
        this.inviterId = inviterId;
        this.inviteeId = inviteeId;
        this.status = status;
    }
}
