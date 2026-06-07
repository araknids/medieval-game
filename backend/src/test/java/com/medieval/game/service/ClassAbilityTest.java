package com.medieval.game.service;

import com.medieval.game.enums.AbilityEffect;
import com.medieval.game.enums.ClassAbility;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Warrior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Enum de habilidades: árvores por classe, passivas, magnitude + ponto por level. [HABILIDADES]
@DisplayName("ClassAbility | árvores + passivas + magnitude + ponto por level")
class ClassAbilityTest {

    @Test
    @DisplayName("forClass separa as árvores (5 por classe, owner correto)")
    void forClass_splitsTrees() {
        var warrior = ClassAbility.forClass(WarriorClass.WARRIOR);
        var archer  = ClassAbility.forClass(WarriorClass.ARCHER);
        assertThat(warrior).hasSize(5).allMatch(a -> a.owner == WarriorClass.WARRIOR);
        assertThat(archer).hasSize(5).allMatch(a -> a.owner == WarriorClass.ARCHER);
        assertThat(ClassAbility.forClass(WarriorClass.RECRUIT)).isEmpty();
        assertThat(ClassAbility.MAX_LEVEL).isEqualTo(10);
    }

    @Test
    @DisplayName("Passivas: Toughness→HP, Weapon Mastery→ATK, Eagle Eye→LUK, Agility→AGI")
    void passiveBonus() {
        assertThat(ClassAbility.TOUGHNESS.passiveBonus(3)[2]).isEqualTo(36);   // hp
        assertThat(ClassAbility.WEAPON_MASTERY.passiveBonus(4)[0]).isEqualTo(8); // atk
        assertThat(ClassAbility.EAGLE_EYE.passiveBonus(5)[5]).isEqualTo(10);   // luk
        assertThat(ClassAbility.AGILITY.passiveBonus(6)[4]).isEqualTo(6);      // agi (slot 4) [REBALANCE]
    }

    @Test
    @DisplayName("Ativas: magnitude escala com o nível; tipo/effect/cooldown corretos")
    void activeMagnitudeAndMeta() {
        assertThat(ClassAbility.SHIELD_BASH.isActive()).isTrue();
        assertThat(ClassAbility.SHIELD_BASH.effect).isEqualTo(AbilityEffect.BONUS_DAMAGE);
        assertThat(ClassAbility.SHIELD_BASH.cooldown).isEqualTo(5);
        assertThat(ClassAbility.SHIELD_BASH.magnitude(5)).isEqualTo(28);   // 8 + 4×5
        assertThat(ClassAbility.PRECISE_SHOT.effect).isEqualTo(AbilityEffect.GUARANTEED_CRIT);
        assertThat(ClassAbility.PRECISE_SHOT.magnitude(10)).isEqualTo(30); // 3×10
        assertThat(ClassAbility.TOUGHNESS.isActive()).isFalse();
    }

    @Test
    @DisplayName("Subir de level dá 1 ponto de habilidade")
    void levelUp_grantsAbilityPoint() {
        Warrior w = new Warrior();
        int before = w.getAbilityPoints();
        w.levelUp();
        assertThat(w.getAbilityPoints()).isEqualTo(before + 1);
    }
}
