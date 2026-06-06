package com.medieval.game.service;

import com.medieval.game.enums.WeaponCategory;
import com.medieval.game.enums.WeaponType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Tipos de arma: inferência por nome (EN+PT), categoria e perfil de stats. [CLASSES_ARMAS]
@DisplayName("WeaponType | inferência por nome + perfil de stats")
class WeaponTypeTest {

    @Test
    @DisplayName("fromName mapeia nomes EN e PT pro tipo certo")
    void fromName_mapsEnAndPt() {
        assertThat(WeaponType.fromName("Iron Sword")).isEqualTo(WeaponType.SWORD);
        assertThat(WeaponType.fromName("Blade of Chaos")).isEqualTo(WeaponType.SWORD);
        assertThat(WeaponType.fromName("Greatsword of Valor")).isEqualTo(WeaponType.GREATSWORD);
        assertThat(WeaponType.fromName("Montante de Ferro")).isEqualTo(WeaponType.GREATSWORD);
        assertThat(WeaponType.fromName("Battle Axe")).isEqualTo(WeaponType.AXE);
        assertThat(WeaponType.fromName("Machado de Prata")).isEqualTo(WeaponType.AXE);
        assertThat(WeaponType.fromName("Iron Spear")).isEqualTo(WeaponType.SPEAR);
        assertThat(WeaponType.fromName("Lança de Cobre")).isEqualTo(WeaponType.SPEAR);
        assertThat(WeaponType.fromName("Short Bow")).isEqualTo(WeaponType.SHORTBOW);
        assertThat(WeaponType.fromName("Arco Curto de Ouro")).isEqualTo(WeaponType.SHORTBOW);
        assertThat(WeaponType.fromName("Long Bow")).isEqualTo(WeaponType.LONGBOW);
        assertThat(WeaponType.fromName("Elven Longbow")).isEqualTo(WeaponType.LONGBOW);
        assertThat(WeaponType.fromName("Crossbow")).isEqualTo(WeaponType.CROSSBOW);
        assertThat(WeaponType.fromName("Besta de Mithril")).isEqualTo(WeaponType.CROSSBOW);
        assertThat(WeaponType.fromName("Hunting Bow")).isEqualTo(WeaponType.SHORTBOW); // arco genérico
    }

    @Test
    @DisplayName("Categoria: espadas/machado/lança = MELEE; arcos/besta = RANGED")
    void category_byType() {
        assertThat(WeaponType.SWORD.category).isEqualTo(WeaponCategory.MELEE);
        assertThat(WeaponType.GREATSWORD.category).isEqualTo(WeaponCategory.MELEE);
        assertThat(WeaponType.AXE.category).isEqualTo(WeaponCategory.MELEE);
        assertThat(WeaponType.SPEAR.category).isEqualTo(WeaponCategory.MELEE);
        assertThat(WeaponType.SHORTBOW.category).isEqualTo(WeaponCategory.RANGED);
        assertThat(WeaponType.LONGBOW.category).isEqualTo(WeaponCategory.RANGED);
        assertThat(WeaponType.CROSSBOW.category).isEqualTo(WeaponCategory.RANGED);
    }

    // stats() = {atk, def, hp, str, dex, luk}
    @Test
    @DisplayName("Perfil: cada tipo leva o secundário certo; arma nunca dá HP")
    void stats_profilePerType() {
        int lvl = 30, rar = 2;
        int[] sword = WeaponType.SWORD.stats(lvl, rar);
        int[] great = WeaponType.GREATSWORD.stats(lvl, rar);
        int[] axe   = WeaponType.AXE.stats(lvl, rar);
        int[] spear = WeaponType.SPEAR.stats(lvl, rar);
        int[] sbow  = WeaponType.SHORTBOW.stats(lvl, rar);
        int[] lbow  = WeaponType.LONGBOW.stats(lvl, rar);
        int[] xbow  = WeaponType.CROSSBOW.stats(lvl, rar);

        // Nenhuma arma dá HP
        for (int[] s : new int[][]{sword, great, axe, spear, sbow, lbow, xbow}) {
            assertThat(s[2]).as("weapon HP must be 0").isZero();
            assertThat(s[0]).as("weapon ATK >= 1").isGreaterThanOrEqualTo(1);
        }
        assertThat(sword[1]).as("sword DEF").isGreaterThan(0);   // def
        assertThat(great[1]).as("greatsword sem DEF").isZero();
        assertThat(great[0]).as("greatsword ATK > sword ATK").isGreaterThan(sword[0]); // dano puro
        assertThat(axe[5]).as("axe LUK").isGreaterThan(0);       // luk
        assertThat(spear[3]).as("spear STR").isGreaterThan(0);   // str
        assertThat(sbow[4]).as("shortbow DEX").isGreaterThan(0); // dex
        assertThat(lbow[0]).as("longbow ATK > shortbow ATK").isGreaterThan(sbow[0]); // dano puro
        assertThat(xbow[5]).as("crossbow LUK").isGreaterThan(0); // luk
    }

    @Test
    @DisplayName("Budget escala com nível e raridade")
    void stats_scaleWithLevelAndRarity() {
        assertThat(WeaponType.GREATSWORD.stats(50, 1)[0])
                .isGreaterThan(WeaponType.GREATSWORD.stats(10, 1)[0]);          // nível
        assertThat(WeaponType.GREATSWORD.stats(30, 4)[0])
                .isGreaterThan(WeaponType.GREATSWORD.stats(30, 1)[0]);          // raridade
    }
}
