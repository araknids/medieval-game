package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * [LEADERBOARDS] Amizade entre jogadores. 1 linha por par (requester→addressee). PENDING até o
 * destinatário aceitar (vira ACCEPTED). A relação ACCEPTED é lógica e simétrica (consultada nas duas
 * direções). Tabela auto-criada pelo ddl-auto. Status como String (PENDING/ACCEPTED) p/ evitar a
 * dança de check-constraint de enum no Postgres.
 */
@Entity
@Table(name = "friendships",
       uniqueConstraints = @UniqueConstraint(columnNames = {"requester_id", "addressee_id"}))
@Getter
@Setter
@NoArgsConstructor
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "addressee_id", nullable = false)
    private Long addresseeId;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Friendship(Long requesterId, Long addresseeId, String status) {
        this.requesterId = requesterId;
        this.addresseeId = addresseeId;
        this.status = status;
    }
}
