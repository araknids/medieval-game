# Plano — Sistema de Quests (Daily agora · Story depois)

> Fonte da verdade do **sistema de quests de reino**. Decidido em 2026-06-05.
> Complementa `docs/PLANO_SEM_TIMER_PVP.md` (modelo instantâneo / gate = estamina).

## Visão

Duas categorias de quest, introduzidas em fases:

1. **Daily Quests (AGORA)** — as quests de reino que já existem (`KingdomQuestType`, 6 por reino)
   viram **missões diárias**: repetíveis, mas **1x por janela de 12h**. Reset global fixo.
2. **Story Quests (FUTURO — não implementado)** — linha narrativa que libera **itens, skins e afins**,
   exigindo **drops de monstro** específicos como pré-requisito. Categoria separada das dailies
   (provável novo enum/entidade `StoryQuest` + tracking de progresso por player). Só o esqueleto
   conceitual aqui; implementação fica pra depois.

---

## Daily Quests — design (Fase atual)

### Decisões (alinhadas com o dono)
- **Board: mantém a rotação 2-de-6.** A vitrine continua mostrando **2 das 6** quests do reino,
  mas a janela passa de **6h → 12h**. Cada quest visível pode ser feita **1x naquela janela de 12h**.
- **Reset: janela global fixa.** `epoch / 43200` (12h) alinha exatamente em **00:00 e 12:00 UTC**.
  Todos os players resetam juntos. A rotação da vitrine **e** o reset das conclusões acontecem
  no mesmo boundary (a cada 12h a dupla de quests muda e as conclusões zeram).
- **Mantém igual:** instantâneo (sem-timer), gate = estamina, encontro de monstro no collect,
  recompensas/escala por dificuldade, VIP instant-start (agora também respeita o lock diário).

### Mecânica do lock
- `QUEST_ROTATION_SECONDS`: `21600` → **`43200`** (12h). É só do quest — NÃO mexer no `21600`
  do território/guild-war (`getAllKingdomStatus`) nem na rotação da Loja.
- **Janela atual** = `currentQuestWindowId() = Instant.now().getEpochSecond() / 43200`.
- Conclusão é rastreada **reaproveitando as linhas `KingdomActiveQuest`** (sem tabela nova):
  no `collectQuest`, grava `completedWindowId = currentQuestWindowId()` junto com `status=COLLECTED`.
- **Lock no `startQuest`** (cobre normal **e** VIP instant, que chama `startQuest`):
  se `existsBy(player, questType, COLLECTED, currentWindowId)` → rejeita
  *"Você já fez essa missão diária hoje. Volta no próximo reset."*
- **Derrota não consome a daily:** `completedWindowId` só é gravado quando a quest é de fato
  concluída (vitória contra o monstro **ou** sem encontro). Se perder pro monstro, a daily NÃO
  trava — dá pra tentar de novo (gastando estamina/HP). Mantém a daily sempre alcançável e dá
  dente ao risco do monstro (custa tentativas, não a daily inteira).
- Usar `completedWindowId` (epoch puro) em vez de comparar `startedAt` (LocalDateTime, depende do
  fuso do servidor) deixa o lock **determinístico e testável**, e perfeitamente alinhado à rotação.

### API
`GET /api/world/{kingdom}/quests` (vitrine) ganha 2 campos por quest:
- `doneToday` (bool) — player já completou essa quest na janela atual.
- `secondsUntilReset` (long) — segundos até o próximo boundary de 12h (`secondsUntilQuestRotation`).
- `canStart` passa a ser `!doneToday && stamina >= staminaCost`.

Demais endpoints (start/collect/abandon/instant-start) inalterados, exceto o lock em start.

### Frontend
- Card de quest: se `doneToday`, troca o botão Start por estado **desabilitado "✓ Done · reset in Xh"**
  e esconde o Instant. Senão, comportamento atual (Start / Low stamina / Warrior busy).
- Cabeçalho da seção: **"🗓 DAILY QUESTS · reset in Xh"** com o countdown de `secondsUntilReset`.

### Migração (Postgres prod)
`SchemaMigrator`: `ALTER TABLE kingdom_active_quests ADD COLUMN IF NOT EXISTS completed_window_id bigint NOT NULL DEFAULT 0`.
Linhas COLLECTED antigas ficam com `0` (janela "ancestral") → não travam nada hoje.

### Testes
- Integração: completar uma quest da vitrine → re-fetch mostra `doneToday=true` + `canStart=false`;
  tentar `start` da mesma quest na mesma janela → **400**. (O reset-na-próxima-janela é implícito pelo
  `windowId` mudar; não dá pra testar sem viajar no tempo.)
- `rotatingWindow` continua igual (lógica 2-de-6 independe do período) — só ajustar comentários 6h→12h.

---

## Story Quests — esboço (FUTURO, fora desta fase)
- Categoria separada (não entra na vitrine diária). Provável `StoryQuest` enum/entidade + tabela de
  progresso por player (`StoryQuestProgress`: questId, status, requisitos cumpridos).
- **Pré-requisito de drop de monstro**: completar exige X de um material/drop específico (ex.: "traga
  3 Núcleos de Serpente"). Reaproveita o sistema de drops/inventário existente.
- **Recompensa**: itens/skins exclusivos (cosméticos + equipáveis), não-repetível (one-time).
- Sequencial/encadeada (libera a próxima ao concluir). Detalhar quando chegar a fase.
