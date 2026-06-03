package com.medieval.game.model;

import com.medieval.game.enums.Territory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "territory_declarations")
@Getter
@Setter
@NoArgsConstructor
public class TerritoryDeclaration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guild_id", nullable = false)
    private Guild guild;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Territory territory;

    @Column(nullable = false)
    private LocalDateTime declaredAt = LocalDateTime.now();

    // Which 6h cycle slot this declaration targets (epoch seconds / 21600)
    @Column(nullable = false)
    private long battleCycleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeclarationStatus status = DeclarationStatus.PENDING;

    public enum DeclarationStatus { PENDING, RESOLVED, CANCELLED }
}
