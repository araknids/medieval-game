# PLANO — Luna interrompe a missão normal [LUNA_INTERRUPT]

> **Status:** ✅ IMPLEMENTADO (2026-06-08). Testes em `PetSystemTest` (interrupção help/ignore + Luna fora da rotação).
> **Objetivo:** A Luna (pet) deixa de ser uma quest avulsa rara e passa a **interromper missões normais**:
> no meio de uma missão, um cãozinho aparece e você **escolhe** terminar a missão (mantém a recompensa) ou
> **ajudar o doguinho** (abre mão da recompensa, mas rola a chance da Luna + sobe a afeição/pity com um texto
> tipo "ela está começando a gostar de você").

## Decisões (confirmadas)
1. **Frequência:** chance pequena **toda missão** (`LUNA_INTERRUPT_PER_MILLE = 80` ≈ 8%, placeholder), só enquanto **não tem a Luna**.
2. **Ao ajudar:** rola a chance de pegar a Luna ALI (reusa a pity escalante); se não pegar, **pity++ + texto de afeição**.
3. **Substitui** a quest avulsa `RESCUE_STRAY_DOG` (sai do rodízio/vitrine).

## Modelo (espelha o chefe errante [ZONA_CHEFE])
- Novo `QuestStatus.LUNA_PENDING` (pausa, igual ao `BOSS_PENDING`).
- `KingdomActiveQuest.pendingOptionId` (guarda a escolha do diálogo p/ retomar no "terminar"). Migração em `SchemaMigrator`.
- `CollectResult` ganha `boolean lunaPending`.

## Backend (`KingdomService` + `KingdomController`)
- `collectQuest`: após validar e ANTES de resolver, rola `shouldLunaInterrupt(player)` (não tem Luna + chance + flag `app.luna.interrupt-enabled`). Se interromper → `pendingOptionId=optionId`, status `LUNA_PENDING`, devolve `lunaPending=true` + intro. Senão → `resolveAndReward` (a resolução+recompensa atual, extraída p/ reuso).
- `resolveLunaIgnore(player, questId)`: retoma → `resolveAndReward` com o `pendingOptionId` (recompensa normal).
- `resolveLunaHelp(player, questId)`: marca COLLECTED (consome a daily, sem recompensa) → `rollLunaHelp` (lógica da pity: pega a Luna ou pity++ + afeição).
- Remove `lunaQuestActive`/`isLunaWindow`/`LUNA_WINDOW_DENOM` + `collectLunaQuest` (repurposed) + o bloco da vitrine no controller.
- Endpoint `POST /api/world/{kingdom}/quests/{id}/luna/{action}` (`help`/`ignore`).
- Flag de teste `app.luna.interrupt-enabled=false` (em `application.properties` test + pgtest) p/ collect determinístico; o teste exercita `resolveLunaHelp/Ignore` direto num quest `LUNA_PENDING` (igual ao `ZoneBossIntegrationTest`).

## Frontend (`app.js`)
- `collectKingdomQuest`: se `r.lunaPending` → `showLunaInterruptModal` (em vez do resultado).
- `showLunaInterruptModal`: intro + 2 botões → `resolveLuna(kingdom, id, 'help'|'ignore')`.
- `resolveLuna`: `help` sem pet → modal de afeição; `help` com pet → celebração; `ignore` → resultado normal. Reusa `showQuestResultModal`.

## i18n
- `luna.interrupt.intro`, `luna.interrupt.help.nopet` (texto de afeição "começando a gostar de você"), `luna.help.title`, labels dos botões (`luna.btn.help`/`luna.btn.ignore`) — EN+PT.

## Números (placeholders pra tuning)
`LUNA_INTERRUPT_PER_MILLE=80` (8%/missão); pity reusa `LUNA_BASE_PPM=100`/`STEP=50`/`CAP=10000`.
