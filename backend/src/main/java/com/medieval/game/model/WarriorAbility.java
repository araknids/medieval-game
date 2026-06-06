package com.medieval.game.model;

import com.medieval.game.enums.ClassAbility;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Nível (1–10) de uma habilidade de classe aprendida por um guerreiro. [HABILIDADES] */
@Entity
@Table(name = "warrior_abilities",
       uniqueConstraints = @UniqueConstraint(columnNames = {"warrior_id", "ability"}))
@Getter
@Setter
@NoArgsConstructor
public class WarriorAbility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warrior_id", nullable = false)
    private Warrior warrior;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClassAbility ability;

    @Column(nullable = false)
    private int level = 1; // 1..10
}
