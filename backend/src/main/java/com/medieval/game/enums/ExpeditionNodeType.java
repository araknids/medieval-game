package com.medieval.game.enums;

/** [INCURSAO] Tipos de nó do mapa de uma Incursão (Delve). Ver docs/PLANO_INCURSAO.md §5. */
public enum ExpeditionNodeType {
    COMBAT,   // luta normal + baú pequeno
    ELITE,    // luta dura (+nível) + baú bom
    TREASURE, // baú direto (pode ter armadilha)
    EVENT,    // diálogo/escolha (reusa QuestOutcome d20)
    CAMP,     // cura HP + checkpoint (banca a bolsa)
    BOSS,     // luta final (loot garantido alto)
    GATHER    // [INCURSAO] coleta de recurso (minerar/pescar/garimpar) — só em runs de coleta (ZONE c/ skill)
}
