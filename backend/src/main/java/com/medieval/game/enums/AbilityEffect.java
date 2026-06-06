package com.medieval.game.enums;

/**
 * Efeito de uma habilidade ATIVA, interpretado pelo BattleSimulator no loop de combate. [HABILIDADES]
 * O cooldown é fixo por habilidade; o nível só muda a magnitude (ClassAbility.magnitude).
 */
public enum AbilityEffect {
    BONUS_DAMAGE,     // no golpe que dispara: +dano (Shield Bash)
    EXTRA_ATTACK,     // um ataque extra naquele round, a X% do dano (Volley)
    GUARANTEED_CRIT,  // força crítico + dano bônus no golpe (Precise Shot)
    HEAL_LOW,         // 1×/luta: cura % do HP máx ao cair abaixo de 30% (Second Wind)
    ATK_BUFF_LOW,     // ao cair abaixo de 50% HP: +X% ATK por alguns rounds (Berserk)
    DODGE_INCOMING    // anula o próximo golpe recebido + reflete dano (Evasive Roll)
}
