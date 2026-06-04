package com.medieval.game.enums;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Afixos aleatórios de item (Itens V2, Fase A). Cada item rola N afixos pela raridade
 * (Comum 0, Incomum 1, Raro 2, Épico 3, Lendário 4). Um afixo concede um stat plano
 * (ATK/DEF/HP) OU um atributo (STR/DEX/LUK), reusando o sistema D&D existente.
 *
 * <p>Prefixos viram adjetivo no nome ("Sharp …"); sufixos são "of the …". Todos aparecem
 * como linhas de bônus no card do item. Magnitude escala com a raridade do item.
 */
public enum Affix {

    // ── Prefixos (adjetivo) ──
    SHARP ("Sharp",  Position.PREFIX, Stat.ATK),
    HEAVY ("Heavy",  Position.PREFIX, Stat.DEF),
    STURDY("Sturdy", Position.PREFIX, Stat.HP),
    BRUTAL("Brutal", Position.PREFIX, Stat.STR),
    SWIFT ("Swift",  Position.PREFIX, Stat.DEX),
    LUCKY ("Lucky",  Position.PREFIX, Stat.LUK),

    // ── Sufixos ("of the …") ──
    OF_THE_TIGER ("of the Tiger",  Position.SUFFIX, Stat.ATK),
    OF_THE_TURTLE("of the Turtle", Position.SUFFIX, Stat.DEF),
    OF_THE_BEAR  ("of the Bear",   Position.SUFFIX, Stat.HP),
    OF_THE_OX    ("of the Ox",     Position.SUFFIX, Stat.STR),
    OF_THE_FOX   ("of the Fox",    Position.SUFFIX, Stat.DEX),
    OF_THE_CAT   ("of the Cat",    Position.SUFFIX, Stat.LUK);

    public enum Position { PREFIX, SUFFIX }

    /** Stat afetado. Planos vão direto em ATK/DEF/HP; atributos entram no pipeline D&D. */
    public enum Stat { ATK, DEF, HP, STR, DEX, LUK }

    public final String   word;
    public final Position position;
    public final Stat     stat;

    Affix(String word, Position position, Stat stat) {
        this.word = word;
        this.position = position;
        this.stat = stat;
    }

    /** Texto curto pra UI: "+8 ATK", "+2 LUK". */
    public String effectText(int magnitude) {
        return "+" + magnitude + " " + stat.name();
    }

    /**
     * Rola a magnitude do afixo escalando com o NÍVEL DO ITEM (raridade só dá um empurrão leve). [ITENS_V3]
     * Assim um Lendário Lv1 tem afixos minúsculos (~1) e um Comum Lv40 vence — a regra
     * "nível alto > raridade alta de nível baixo" passa a valer também nos afixos.
     * Planos (ATK/DEF/HP) maiores; atributos (STR/DEX/LUK) pequenos (são fortes).
     */
    public int rollMagnitude(int itemLevel, int rarity) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double scale = Math.max(1, itemLevel) * (1.0 + rarity * 0.15); // nível domina; raridade ~+15%/tier
        return switch (stat) {
            case ATK, DEF      -> 1 + rng.nextInt((int) Math.round(scale * 0.25) + 1);
            case HP            -> 2 + rng.nextInt((int) Math.round(scale * 0.9)  + 1);
            case STR, DEX, LUK -> 1 + rng.nextInt((int) Math.max(1, Math.round(scale / 15.0)));
        };
    }
}
