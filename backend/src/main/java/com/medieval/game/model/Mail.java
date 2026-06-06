package com.medieval.game.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "mail")
@Getter
@Setter
@NoArgsConstructor
public class Mail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking: impede coletar gold/item da mesma carta duas vezes. [AUDITORIA C3]
    @Version
    @Column(columnDefinition = "bigint default 0")
    private long version;

    @Column(nullable = false)
    private Long senderPlayerId;

    @Column(nullable = false)
    private String senderWarriorName;

    @Column(nullable = false)
    private Long recipientPlayerId;

    @Column(nullable = false, length = 500)
    private String message;

    // Gold attached to this letter (0 = no gold)
    @Column(columnDefinition = "bigint default 0")
    private long goldAmount = 0;

    @Column(nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    private LocalDateTime readAt;      // null = unread
    private LocalDateTime collectedAt; // null = gold not yet collected

    // ── Item attachment (bag-full overflow) ────────────────────────────────────
    private String itemName;
    private String itemType;
    @Column(columnDefinition = "integer default 0")
    private int    itemAtk     = 0;
    @Column(columnDefinition = "integer default 0")
    private int    itemDef     = 0;
    @Column(columnDefinition = "integer default 0")
    private int    itemHp      = 0;
    @Column(columnDefinition = "integer default 1")
    private int    itemRarity  = 1;
    @Column(columnDefinition = "integer default 1")
    private int    itemLevel   = 1; // [CLASSES_ARMAS] preserva o nível (arma recalcula stats pelo perfil no claim)
    @Column(columnDefinition = "integer default 0")
    private int    itemSockets = 0;
    @Column(columnDefinition = "TEXT")
    private String itemDescription;
    private String itemOrigin;
    @Column(columnDefinition = "boolean default false")
    private boolean itemCollected = false;
    private LocalDateTime expiresAt;  // null = no expiry; +7 days for item mails

    public boolean isRead()      { return readAt      != null; }
    public boolean isCollected() { return collectedAt != null; }
    public boolean hasItem()     { return itemName    != null; }
    public boolean isExpired()   { return expiresAt   != null && LocalDateTime.now().isAfter(expiresAt); }
}
