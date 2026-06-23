package com.medieval.game.service;

/**
 * [VARREDURA] Stats de combate NOMEADOS — substitui o {@code int[6]} {@code [atk,def,hp,dex,agi,luk]} que
 * era acessado por índice literal ({@code s[2]}, {@code cs[4]}) longe de onde era montado (carga cognitiva
 * + risco de trocar slot). A ordem do array é preservada em {@link #toArray()} p/ o {@code BattleSimulator
 * .Combatant.of(String,int[],...)} (a "moeda comum" de stats entre cálculo e combate) — assim o NPC (que
 * também é {@code int[6]}) e o Combatant ficam intocados; só o lado do guerreiro ganha nomes.
 *
 * Slots [REBALANCE]: atk=dano, def=mitigação, hp, dex=acerto, agi=esquiva/golpe-extra, luk=crit.
 */
public record CombatStats(int atk, int def, int hp, int dex, int agi, int luk) {

    /** Ordem canônica p/ o Combatant.of / NPC arrays: [atk, def, hp, dex, agi, luk]. */
    public int[] toArray() {
        return new int[]{atk, def, hp, dex, agi, luk};
    }

    /** Constrói a partir do array canônico (ponte p/ código legado que ainda devolve int[6]). */
    public static CombatStats of(int[] a) {
        return new CombatStats(a[0], a[1], a[2], a[3], a[4], a[5]);
    }
}
