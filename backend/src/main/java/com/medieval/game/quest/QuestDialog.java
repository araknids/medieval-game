package com.medieval.game.quest;

import java.util.List;

/**
 * Diálogo de uma quest interativa: narrativa de abertura + opções de escolha (decisão única).
 * Conteúdo voltado ao jogador é em INGLÊS. Ver docs/PLANO_QUESTS_INTERATIVAS.md.
 */
public record QuestDialog(String intro, List<QuestOption> options) {

    /** Uma escolha. hint = dica curta exibida no botão (ex.: "DEX 14") ou "" se não houver. */
    public record QuestOption(String id, String label, String hint, QuestOutcome outcome) {}
}
