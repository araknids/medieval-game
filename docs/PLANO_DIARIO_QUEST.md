# Diário de Missões — 2 abas [DIARIO_QUEST]

Tela do diário (ícone `quest_log` do topbar → cena "StarterQuests"). Decidido em grilling 2026-06-24.

## Decisões

- **2 abas: "Diárias" e "Missões".** Separar os 2 tipos (a confusão era misturá-los).
  - **Diárias** = quests de reino (repetitivas, resetam por janela de 12h, **sem histórico**). **UMA em
    progresso por vez**: aceitar trava as outras **até resolver**; as **já feitas na janela** aparecem
    **apagadas** ("volta no ciclo"); resolver acontece **no Mundo** (lá mora o fluxo de diálogo/combate).
  - **Missões** = quests ÚNICAS/história (hoje só os **deveres do recruta**), em seções
    **Disponíveis / Em andamento / Concluídas**. Pode acumular várias aceitas (to-do, p/ história futura).
    Deveres do recruta agem inline (aceitar/entregar). Só as ÚNICAS vão pra "Concluídas".
- **`KingdomQuestType.daily`** (boolean, default `true`): hoje toda quest de reino é daily. Quest de
  história futura nasce `daily=false` → entra em "Missões" (não em "Diárias"), acumula no to-do, e
  **não gasta estamina** (regra do dono). Construtor com `daily` p/ isso.
- **Guard:** DIÁRIA → 1 em progresso por vez (`startQuest` rejeita se já há alguma daily IN_PROGRESS).
  NORMAL → pode acumular, só não a MESMA 2x (`existsByPlayerAndQuestTypeAndStatus`).
- **"!" no topbar:** aceso enquanto houver **diária não-feita na janela** (active/available) OU **missão
  disponível**. O `questJournal` devolve `badge`; o Shell (`_refresh_starter`) lê e OR com o badge de starter.
- **??? de curiosidade:** adiado (entra com as quests de história).

## Backend

- `KingdomQuestType`: campo `daily` (default true via construtor).
- `KingdomActiveQuestRepository`: `existsByPlayerAndQuestTypeAndStatus`, `findByPlayerAndStatusOrderByStartedAtDesc`.
- `startQuest`: guard daily (1 por vez) vs normal (per-questType).
- `KingdomService.questJournal(player)` (read-only) → `{daily:[{...,dailyState:active|available|done}],
  dailyLocked, missionsAvailable, missionsInProgress, missionsCompleted, badge}`. Diárias = `getQuestsForKingdom`
  por reino (janela rotativa de 2, igual ao World) com daily=true; missions = kingdom daily=false (futuro) +
  deveres do recruta por estado.
- `QuestJournalController` `GET /api/quests/journal`. Ações continuam em
  `/api/world/{kingdom}/quests/start|collect` e `/api/starter-quests/*`.

## Frontend (Godot)

- `ui/StarterQuests.gd`: 2 abas. **Diárias** = lista plana com estados (active/available-com-lock/done-apagada).
  **Missões** = 3 seções (Disponíveis/Em andamento/Concluídas). i18n PT→EN.
- `Shell.gd`: `_refresh_starter` busca também `/api/quests/journal` (flag `badge`); o "!" do topbar acende
  com starter OU diária/missão disponível.

## Histórico

- v1 (descartado): 3 abas planas (Pra pegar / Em progresso / Completadas) misturando daily + único — ficou
  confuso e o "Em progresso to-do" não fazia sentido com daily (todas as quests de reino são daily).
