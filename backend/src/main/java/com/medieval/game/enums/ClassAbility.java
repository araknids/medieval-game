package com.medieval.game.enums;

import java.util.Arrays;
import java.util.List;

/**
 * Habilidades de classe (Abilities) — distintas das profissões (SkillType). Cada classe tem sua
 * árvore; cada habilidade vai até lv10. Passivas dão bônus de stat; ativas disparam no combate com
 * cooldown fixo (efeito escala com o nível). [HABILIDADES] Desenho: docs/PLANO_HABILIDADES.md.
 */
public enum ClassAbility {
    //              owner,                 kind,          effect,                       cd, display,          icon, descrição
    TOUGHNESS      (WarriorClass.WARRIOR, Kind.PASSIVE, null,                          0, "Toughness",      "🛡", "+12 max HP per level."),
    WEAPON_MASTERY (WarriorClass.WARRIOR, Kind.PASSIVE, null,                          0, "Weapon Mastery", "⚔", "+2 ATK per level."),
    SHIELD_BASH    (WarriorClass.WARRIOR, Kind.ACTIVE,  AbilityEffect.BONUS_DAMAGE,    5, "Shield Bash",    "💥", "Every 5 rounds: +(8+4×lvl) bonus damage."),
    SECOND_WIND    (WarriorClass.WARRIOR, Kind.ACTIVE,  AbilityEffect.HEAL_LOW,        0, "Second Wind",    "❤", "Once per fight, below 30% HP: heal (10+3×lvl)% max HP."),
    BERSERK        (WarriorClass.WARRIOR, Kind.ACTIVE,  AbilityEffect.ATK_BUFF_LOW,    8, "Berserk",        "🔥", "Below 50% HP: +(5×lvl)% ATK for 3 rounds (CD 8)."),

    EAGLE_EYE      (WarriorClass.ARCHER,  Kind.PASSIVE, null,                          0, "Eagle Eye",      "🎯", "+2 LUK per level (crit window + Fortune Save)."),
    AGILITY        (WarriorClass.ARCHER,  Kind.PASSIVE, null,                          0, "Agility",        "💨", "+1 DEX per level (AC / dodge)."),
    PRECISE_SHOT   (WarriorClass.ARCHER,  Kind.ACTIVE,  AbilityEffect.GUARANTEED_CRIT, 4, "Precise Shot",   "🏹", "Every 4 rounds: guaranteed crit + (3×lvl) bonus damage."),
    VOLLEY         (WarriorClass.ARCHER,  Kind.ACTIVE,  AbilityEffect.EXTRA_ATTACK,    5, "Volley",         "☄", "Every 5 rounds: extra attack at (50+5×lvl)% damage."),
    EVASIVE_ROLL   (WarriorClass.ARCHER,  Kind.ACTIVE,  AbilityEffect.DODGE_INCOMING,  6, "Evasive Roll",   "🌀", "Every 6 rounds: dodge next hit + reflect (2×lvl) damage.");

    public final WarriorClass  owner;
    public final Kind          kind;
    public final AbilityEffect effect;   // null p/ passiva
    public final int           cooldown; // rounds (0 = passiva ou 1×/luta, controlado pelo effect)
    public final String        displayName, icon, description;

    public static final int MAX_LEVEL = 10;

    ClassAbility(WarriorClass owner, Kind kind, AbilityEffect effect, int cooldown,
                 String displayName, String icon, String description) {
        this.owner = owner; this.kind = kind; this.effect = effect; this.cooldown = cooldown;
        this.displayName = displayName; this.icon = icon; this.description = description;
    }

    public enum Kind { PASSIVE, ACTIVE }
    public boolean isActive() { return kind == Kind.ACTIVE; }

    /** Delta no array de combate [atk, def, hp, dex, strBonus, luk] p/ passivas no nível N. */
    public int[] passiveBonus(int level) {
        return switch (this) {
            case TOUGHNESS      -> new int[]{0, 0, 12 * level, 0, 0, 0}; // HP
            case WEAPON_MASTERY -> new int[]{2 * level, 0, 0, 0, 0, 0};  // ATK
            case EAGLE_EYE      -> new int[]{0, 0, 0, 0, 0, 2 * level};  // LUK
            case AGILITY        -> new int[]{0, 0, 0, level, 0, 0};      // DEX (AC)
            default             -> new int[]{0, 0, 0, 0, 0, 0};
        };
    }

    /** Magnitude do efeito da ATIVA no nível N (significado depende do effectType). */
    public int magnitude(int level) {
        return switch (this) {
            case SHIELD_BASH  -> 8 + 4 * level;  // dano bônus
            case SECOND_WIND  -> 10 + 3 * level; // % do HP máx curado
            case BERSERK      -> 5 * level;      // % de ATK
            case PRECISE_SHOT -> 3 * level;      // dano bônus no crit
            case VOLLEY       -> 50 + 5 * level; // % do dano do ataque extra
            case EVASIVE_ROLL -> 2 * level;      // dano refletido
            default -> 0;
        };
    }

    public static List<ClassAbility> forClass(WarriorClass c) {
        return Arrays.stream(values()).filter(a -> a.owner == c).toList();
    }
}
