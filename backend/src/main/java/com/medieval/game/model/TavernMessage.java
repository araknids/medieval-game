package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * [TAVERNA] Mensagem do feed da Taverna — chat entre players + avisos globais (mesmo feed).
 * Por-servidor automático (1 banco por deploy [SERVIDORES]). Lido por polling (GET /api/tavern/feed?since=).
 */
@Entity
@Table(name = "tavern_messages")
@Getter
@Setter
@NoArgsConstructor
public class TavernMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Player que mandou; 0 = sistema (aviso global). */
    @Column(nullable = false)
    private Long senderPlayerId;

    /** Nome exibido (nick + título no chat; "📢" nos avisos). */
    @Column(nullable = false, length = 60)
    private String senderName;

    @Column(nullable = false, length = 220)
    private String text;

    /** "CHAT" ou "ANNOUNCEMENT" (varchar simples — sem enum p/ evitar check-constraint). */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
