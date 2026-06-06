package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Guerra entre duas guildas (7 dias): quem tiver mais kills nos membros inimigos vence e leva uma
 * % do gold acumulado da perdedora. Distinta da Guerra de Território. [GUERRA_GUILDA]
 */
@Entity
@Table(name = "guild_wars")
@Getter
@Setter
@NoArgsConstructor
public class GuildWar {

    public enum Status { ACTIVE, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guild_a_id", nullable = false)
    private Guild guildA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guild_b_id", nullable = false)
    private Guild guildB;

    @Column(columnDefinition = "integer default 0")
    private int killsA = 0;

    @Column(columnDefinition = "integer default 0")
    private int killsB = 0;

    @Column(nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    private Long winnerGuildId; // null = não resolvido ou empate

    // getId() do proxy lazy é seguro (a FK já está carregada) → helpers podem rodar fora de tx.
    public boolean isOver()              { return LocalDateTime.now().isAfter(endsAt); }
    public boolean involves(Long guildId){ return guildA.getId().equals(guildId) || guildB.getId().equals(guildId); }
    public Long otherGuildId(Long myId)  { return guildA.getId().equals(myId) ? guildB.getId() : guildA.getId(); }

    /** +1 kill para a guild que VENCEU a luta (a do vencedor). */
    public void incKillFor(Long winnerGuildId) {
        if      (guildA.getId().equals(winnerGuildId)) killsA++;
        else if (guildB.getId().equals(winnerGuildId)) killsB++;
    }

    public int killsFor(Long guildId) {
        return guildA.getId().equals(guildId) ? killsA : killsB;
    }
}
