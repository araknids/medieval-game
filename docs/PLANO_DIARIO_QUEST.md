# Diário de Quest com abas — [DIARIO_QUEST]

Plano da tela de diário (aberta pelo ícone `quest_log` do topbar → hoje a cena "StarterQuests").
Decidido em grilling 2026-06-24.

## Decisões

- **3 abas:** **Pra pegar** (disponíveis) · **Em progresso** (aceitas-não-resolvidas = to-do) · **Completadas**.
- **Em progresso = to-do:** o jogador pode **aceitar várias** quests e resolver depois. Hoje havia guard de
  **1 `IN_PROGRESS` por vez** ([SEM_TIMER], `existsByPlayerAndStatus`) — virou **1 por `questType`**
  (`existsByPlayerAndQuestTypeAndStatus`): mantém a trava de não startar a MESMA quest 2x, mas libera o to-do.
- **Completadas = só quests ÚNICAS**, não dailies. Hoje as quests de reino são **todas dailies** (travam por
  `completedWindowId`) → as únicas de verdade são os **deveres do recruta** (StarterQuestService). Então
  Completadas hoje = deveres concluídos; **cresce** quando criarmos quests de história. Dailies de reino NÃO
  entram em Completadas (elas repetem; ficam em Pra pegar quando disponíveis).
- **Estamina:** dailies continuam cobrando (no aceitar/`startQuest`); **não-dailies (únicas/história) NÃO
  gastam estamina** — regra do dono. Já vale hoje (deveres do recruta são grátis). Quando criarmos quest única
  de reino, ela nasce não-daily → grátis (hook futuro; sem mudança de estamina agora).
- **??? (curiosidade):** adiado — entra junto com as quests de história.

## Backend

- `KingdomActiveQuestRepository`: `existsByPlayerAndQuestTypeAndStatus`, `findByPlayerAndStatus`.
- `startQuest`: guard de 1-por-vez → 1-por-questType.
- `KingdomService.questJournal(player)` (read-only) agrega 3 grupos a partir de: quests de reino IN_PROGRESS +
  disponíveis por reino (menos as done-this-window e as in-progress) + deveres do recruta por `state`
  (available→Pra pegar, accepted→Em progresso, done→Completadas).
- `QuestJournalController` `GET /api/quests/journal` → `{toPickUp, inProgress, completed}`.
- Ações continuam nos endpoints existentes: aceitar = `POST /{kingdom}/quests/start`; resolver =
  `POST /{kingdom}/quests/{id}/collect`; deveres = `/api/starter-quests/*`.

## Frontend (Godot)

- A cena do diário (topbar `quest_log`) ganha **3 abas** (sub-tab bar do UiKit). Cada aba lista os cards do
  grupo (reino: card com aceitar/resolver; recruta: card com retrato do NPC). Chama `GET /api/quests/journal`.
