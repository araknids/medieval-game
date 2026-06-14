# PLANO — Incursão: run roguelike de nós (quest + coleta) [INCURSAO]

> Status: **IMPLEMENTADO (backend + web + Godot)** em 2026-06-14 — Fases 1+2+3 (motor + fonte
> KINGDOM **e** ZONE + UI no cliente web app.js **e** no cliente Godot `ui/Delve.gd`). Falta só
> tuning/migração do daily (Fase 4). Design alinhado com o dono em 2026-06-14. 638 testes verdes (9 novos).
>
> **Nome interno:** "Incursão" (tag `[INCURSAO]`). Nome voltado ao jogador (EN) = TBD
> ("Delve" / "Expedition" / "Foray"). ⚠️ "Expedição" já é usado p/ `ZoneActivity` no código —
> NÃO reusar essa palavra pro novo sistema p/ evitar colisão de conceito.

---

## 1. Problema

Hoje **quest** e **coleta (pesca/mineração)** não são divertidos:

- **Quest** ([KingdomService]): daily, 2-de-6 por reino, reset 12h. Cada daily é interativa
  ([InteractiveQuests]) mas resolve **UMA escolha só** (`Peaceful`/`Fight`/`Check` d20) e acaba.
  Clica → 1 modal → 1 botão → fim. Sem progressão dentro da quest, sem tensão.
- **Coleta** ([ZoneService]): entra na zona → `collect` resolve tudo de uma vez (drops + talvez 1
  encontro/chefe). É um "abre a caixa e vê o que veio". O único momento de decisão é o chefe errante
  (`BOSS_PENDING` → fugir/encarar).

## 2. Visão (Slay the Spire-like)

A quest e a coleta viram uma **Incursão**: um **mini-mapa ramificado** de nós. O jogador entra,
**escolhe o caminho** entre 2-3 nós por camada, resolve cada nó (batalha / baú / evento / descanso),
e a cada ponto decide **SAIR (sacar o loot, seguro)** ou **AVANÇAR (mais fundo = monstro mais forte =
loot melhor, mas risco de KO)**. É *push-your-luck* com mapa.

```
                 ┌─ COMBATE ─┐         ┌─ ELITE ──┐
   [ENTRADA] ───┤            ├── CAMP ─┤          ├── BOSS ── (extrair)
                 └─ EVENTO ──┘  (bank) └─ BAÚ ────┘
        ↑ escolhe 1 por camada        ↑ a cada nó: SAIR ou AVANÇAR
```

### O loop, em uma frase
> Entra gastando **estamina** → escolhe nós num mapa → o **HP dren a** entre batalhas → a cada parada
> decide **sacar e ir embora** ou **arriscar mais fundo** → **KO** ou **extrair** encerra a run.

---

## 3. Decisões travadas (com o dono, 2026-06-14)

| Tema | Decisão |
|------|---------|
| **Escopo** | Quest **E** coleta usam o **mesmo motor** de Incursão (parametrizado por fonte). |
| **Formato** | **Mini-mapa ramificado** (camadas; escolhe o caminho), não cadeia linear. |
| **Risco** | **Banca o que foi sacado.** Loot vai p/ uma *bolsa carregada*; **checkpoints** (nó CAMP / extrair) **travam** a bolsa em "seguro". Perder uma batalha = **perde só a bolsa não-travada** (desde o último checkpoint) + KO. |
| **Gate** | **Estamina (entrar) + HP (drena entre batalhas; KO = teto)**. **Repetível** — sai do daily-lock. |

---

## 4. Conceito central: o motor único de Incursão

Uma **`ExpeditionRun`** (nome de código) é uma máquina de estados de um mapa de nós. Ela é
**parametrizada pela FONTE**, que define o tema dos monstros e a **tabela de recompensa**:

| Fonte | De onde nasce | Recompensa dos nós | Tema / monstros |
|-------|---------------|--------------------|-----------------|
| **KINGDOM** | tela de Reino (substitui a daily) | **gear** (`rollDrop`/loot de chefe) + bronze + XP | `narrator.pickMonster(kingdom)`, lore do reino |
| **ZONE** | tela de Zona/coleta | **recursos** (peixe/minério/essência/monster core) + bronze/XP + gear ocasional | bioma da zona + áreas de elemento; PvP flag nas 🟡/🔴 |

> **O motor é UM só.** Só muda (a) o **theming** dos nós e (b) a **tabela de drop** por fonte.
> Toda a lógica de mapa, escolha, bolsa, checkpoint, KO, extração é compartilhada.

### Reuso do que já existe (quase tudo)
- **Combate encadeado:** cada nó de batalha chama `battleSimulator.simulate(...)`; o **HP persiste**
  entre nós via `warrior.currentHpSnapshot` (já é assim no `fightQuestMonster`). De graça.
- **Padrão "pausa + decisão do jogador":** já existe 2× — Luna (`LUNA_PENDING` + `/luna/{help|ignore}`)
  e chefe errante (`BOSS_PENDING` + `/boss/{flee|fight}`). A Incursão **generaliza esse padrão** para
  N decisões numa run.
- **Motor de evento/escolha:** `QuestOutcome` (`Peaceful`/`Fight`/`Check` d20, recursivo) + o catálogo
  `InteractiveQuests` (30 diálogos já escritos) **vira o pool de nós EVENTO**. Conteúdo não é jogado fora.
- **Drop de gear:** `rollDrop(player, dropChance, guildBonus, dropLevel)` e o loot garantido de chefe
  (`rollBossLoot`: ~25% Lendário / 40% Épico / 35% Raro no nível do monstro).
- **Drop de recurso:** `gatheringService.addResource` (cap pela bag; overflow → mail via `sendResourceMail`).
- **Nível/stats de monstro:** `questMobLevel`/`questMobStats` (kingdom) e `monsterLevelFor`/`npcStatsByLevel`
  + `bossLevel` (zona). A **profundidade** do nó escala isso (camada mais funda = nível maior).

---

## 5. O mapa

### Forma (MVP)
- A run tem **`D` camadas** (profundidade). `D` escala com o tier/dificuldade da fonte
  (ex.: fácil ~3 camadas, difícil/vermelha ~5-6). Última camada = **BOSS**.
- Cada camada apresenta **2-3 nós** à escolha (o "branch"). O jogador **escolhe 1**, resolve, e então:
  **SAIR** (extrair) **ou** seguir pra próxima camada.
- Mapa **gerado proceduralmente** no start (seed = id da run → determinístico/testável), com pesos de
  tipo de nó por tier. Mais fundo = nível de monstro maior = item de nível maior + raridade maior +
  rendimento de recurso maior. **É isso que cria "avançar = loot melhor".**

### Tipos de nó (MVP)
| Nó | O que faz | Recompensa |
|----|-----------|-----------|
| ⚔️ **COMBATE** | luta normal (`simulate`) | baú pequeno (drop por chance) |
| 💀 **ELITE** | luta dura (monstro elite, +nível) | baú bom (raridade ↑) |
| 🎁 **BAÚ/TESOURO** | abre (chance de armadilha = mini-luta) | loot direto |
| 📜 **EVENTO** | reusa um `QuestDialog`/`Check` d20 do `InteractiveQuests` | depende da escolha/roll |
| 🔥 **CAMP/DESCANSO** | **cura HP** + **trava a bolsa** (checkpoint = "sacado") | — (segurança) |
| 👑 **BOSS** | luta final (loot garantido, alto nível) | item garantido + bônus; extrair depois |

> Nós EVENTO são onde o catálogo `InteractiveQuests` (já escrito, com d20) revive. O EVENTO ainda é
> "decisão única" interna — encaixa perfeito como **um nó** da run maior.

---

## 6. Risco / bolsa / checkpoints (o coração)

- Todo loot ganho num nó cai numa **bolsa carregada** (visível na UI; **ainda não está no inventário**).
- **SAIR / EXTRAIR** (disponível em cada parada): bolsa carregada → inventário/recursos (**seguro**),
  run encerra. Sempre seguro.
- **AVANÇAR** para um nó de batalha e **PERDER (KO)** → **perde toda a bolsa carregada NÃO-travada**
  (o que ganhou desde o último checkpoint) + KO. Run encerra.
- **CHECKPOINT** = nó **CAMP** (e o próprio ato de extrair). Chegar num CAMP **trava** a bolsa (move
  carregado → "seguro garantido"), mesmo que você morra depois numa camada seguinte.
  → "**garante o que já foi sacado num checkpoint**", exatamente a decisão travada.

Resultado: a bolsa **cresce** quanto mais fundo você vai → a tentação aumenta → mas o CAMP ocasional
trava o progresso, então uma run funda não é all-or-nothing puro. A frequência de CAMP é um botão de
balance.

### Como o loot fica "na bolsa" sem entrar no inventário
- **Gear (item):** cria o `InventoryItem` na hora **marcado `runPending=true`** (sai da bag, não
  equipa/vende/stasha — mesmo padrão de flag de `pvpLocked`/`listed`/`consigned`/`guarded`). Ao
  **extrair/travar** → limpa a flag (vira item normal; se a bag estiver cheia → mail). Ao **perder** →
  deleta o item. Reusa 100% o padrão de flag de item já existente.
- **Recurso (peixe/minério/essência/core):** só inteiros guardados na linha da run; aplicados via
  `gatheringService.addResource` no extract/checkpoint (overflow → mail).
- **Bronze/XP:** inteiros guardados na run; creditados no extract/checkpoint.

---

## 7. Gate (estamina + HP, repetível)

- **Entrar** custa estamina (como entrar numa zona hoje). `instant-complete` bypassa só a estamina (teste).
- **HP dren a** entre batalhas (já persiste). **KO** durante a run = encerra com o modelo de perda.
- **Repetível:** a Incursão **sai do daily-lock**. Implica:
  - Remover (ou neutralizar) `isQuestDoneThisPeriod` / `completedWindowId` / a rotação 2-de-6 como
    *trava*. A rotação de 12h pode virar **"runs em destaque"** (sabor/lore), não um limite.
  - **Rehome do perk VIP** "1 daily a mais": vira outra coisa (ex.: +1 nó de baú garantido, custo de
    estamina reduzido, +1 CAMP). ⚠️ decisão em aberto (§12).
  - Travas mantidas: `WorkService.assertNotBusy` (não incursiona trabalhando); KO/HP; **uma run por
    vez** (guard tipo `existsByPlayerAndStatus(IN_PROGRESS)`).

> ⚠️ **Risco de economia:** repetível + loot bom = inflação de gold/recurso/gear. O **custo de
> estamina por run** + o **dreno de HP** são os dois reguladores. Tudo placeholder p/ playtest.

---

## 8. O que muda no código

### 8.1 Modelo
- **Novo enum `ExpeditionNodeType`**: `COMBAT, ELITE, TREASURE, EVENT, CAMP, BOSS`.
- **Novo enum `ExpeditionStatus`**: `IN_PROGRESS, NODE_PENDING, COMPLETED (extraído), DEFEATED, ABANDONED`.
  (Espelha o `BOSS_PENDING`/`LUNA_PENDING` já existentes, mas genérico p/ qualquer nó.)
- **Nova entidade `ExpeditionRun`**: `id, player, warrior, source (KINGDOM|ZONE), kingdom?, zone?,
  skillType?, tier/difficulty, depth, currentLayer, status` + **mapa serializado** (JSON/text, como
  o `battleLog` já é texto) + **bolsa carregada** (bronze/xp/recurso inteiros + flag dos itens via
  `runPending`) + `securedBronze/Xp/...` (travado por checkpoint).
  - Migração Postgres: tabela nova `expedition_runs` (sem `NOT NULL` sem default; check-constraint dos
    enums no `SchemaMigrator`, ver [TESTE_POSTGRES]).
- **`InventoryItem`**: + `runPending` (boolean default false) — guard em `InventoryService` (equip/sell/
  stash/guard) e nos outros services que listam a bag, igual aos flags existentes. Soft-wipe limpa.

### 8.2 Service (`ExpeditionService` — novo, compartilhado)
- `start(player, source, params)`: valida (not busy/KO/uma-por-vez), consome estamina, **gera o mapa**
  (proc, seed = id), salva, devolve estado inicial (camada 0 visível).
- `choose(player, runId, nodeId)`: valida que o nó é alcançável da posição atual; **resolve o nó**:
  - COMBAT/ELITE/BOSS → `simulate` (HP carrega); vitória credita à bolsa; KO → `DEFEATED` + perda.
  - TREASURE → loot direto (chance de armadilha = mini-luta).
  - EVENT → devolve `NODE_PENDING` com o `QuestDialog` (reusa `InteractiveQuests`); a escolha vem por
    `resolveNode`.
  - CAMP → cura HP + **trava a bolsa**.
  - Avança `currentLayer`; se era a última (boss vencido) → permite extrair.
- `resolveNode(player, runId, optionId)`: para nós que pausam (EVENTO d20, baú armadilhado, boss
  encarar/fugir) — resolve com o `QuestOutcome`/`Check` recursivo já existente.
- `extract(player, runId)`: bolsa carregada → inventário/recurso/bronze/xp (limpa `runPending`;
  overflow → mail); `COMPLETED`.
- `abandon(player, runId)`: encerra sem extrair (perde a bolsa não-travada, sem KO? — ver §12).
- Helpers de tabela de recompensa por fonte (`kingdomNodeReward` vs `zoneNodeReward`) — o único ponto
  que diverge entre quest e coleta.
- **PvP flag:** se fonte=ZONE e tier 🟡/🔴, mantém `Player.pvpFlaggedZone/Until` + matchmaking de raid
  como hoje ([PVP_FLAG]) — ortogonal ao mapa.

### 8.3 Controller (`/api/expedition` — novo)
- `POST /start` `{source, kingdom?|zone?, skillType?, tier?}` → estado da run (mapa + camada atual + bolsa).
- `GET  /current` → run em progresso (mapa, posição, bolsa, opções de próximo nó, pode-extrair?).
- `POST /{id}/choose` `{nodeId}` → resolve/avança; pode devolver `NODE_PENDING` (ex.: diálogo de evento).
- `POST /{id}/node` `{optionId}` → resolve a decisão interna do nó pendente (evento d20 / baú / boss).
- `POST /{id}/extract` → saca a bolsa, encerra.
- `POST /{id}/abandon` → desiste.

> O `KingdomController`/`ZoneController` antigos perdem o fluxo de quest-collect/zone-collect e passam
> a **lançar uma Incursão**. Decisão de migração em §11 (não apaga tudo de uma vez).

### 8.4 Frontend
- **app.js (web atual):** nova tela de Incursão — **render do mapa** (camadas + nós clicáveis), bolsa
  carregada (HUD), botão **Extrair**, modais de batalha (reusa `battleArena.js` / `showCollectModal`),
  modal de evento (reusa `showQuestDialogModal`).
- **Godot (cliente Steam):** a mesma tela no cliente Godot ([project_godot_migration]) — é a peça de
  UI **nova** mais pesada. Backend-first; UI nos dois clientes depois.

---

## 9. Theming por fonte (quest vs coleta)

- **KINGDOM:** nó de batalha usa `narrator.pickMonster(kingdom)` + `questMobStats` escalado pela
  camada; baú/boss → `rollDrop`/loot de chefe (gear). Lore = `KingdomQuestType.flavor` semeada nos nós.
- **ZONE:** nó de batalha = NPC da zona (`npcStatsByLevel`, `monsterLevelFor` por tier); baú → **recursos**
  (peixe/minério/essência da área de elemento + monster core) escalando com a profundidade; gear
  ocasional (como o loot de chefe de zona hoje). Áreas de elemento ([ELEMENTOS]) = modificador de nó.

---

## 10. Balanceamento (tudo placeholder)
- Custo de estamina por run (por tier).
- `D` (profundidade) e curva de nível por camada (quão mais forte fica).
- Pesos de tipo de nó por tier; frequência de CAMP (frequente = run segura; raro = greed alto).
- Curva de raridade/nível de loot por profundidade (o incentivo de avançar).
- Rendimento de recurso por profundidade (fonte ZONE).
- Validação: estender `CombatBalanceProbeTest` p/ runs encadeadas (HP sobrevive a N batalhas?).

---

## 11. Faseamento (mesmo "juntos", entrega em ondas testáveis)
- **Fase 0** — este doc. ✅
- **Fase 1** — ✅ Motor + modelo + API + **fonte KINGDOM** (quest vira Incursão), mapa ramificado,
  bolsa/checkpoint/extract/KO/abandono, nós COMBAT/ELITE/TREASURE/EVENT/CAMP/BOSS. Testes H2 verdes.
  Artefatos: enums `Expedition{Source,NodeType,Status}`, `ExpeditionRun`(+repo), `ExpeditionMapGenerator`
  (+test), `ExpeditionService`, `ExpeditionController` (`/api/expedition`), flag `InventoryItem.runPending`
  (guards em Inventory/Auction/BlueMerchant/Stash/Temple), patch no `SchemaMigrator`, soft-wipe.
- **Fase 2** — ✅ **Fonte ZONE** (coleta vira Incursão) no MESMO motor: recursos (Monster Core/Beast
  Hide/essência/peixe-minério-fragmento por skill) + PvP flag no extract de zona 🟡/🔴. Chefe errante
  ainda é o do ZoneService legado (não foi dobrado num nó — fica p/ depois).
- **Frontend web** — ✅ aba 📜 Delve (app.js): launcher (tier/elemento + Quest/Gather), mapa clicável,
  HUD carregado/garantido, modais reusados (combate+loot+evento), extract/abandon. i18n EN/PT.
- **Fase 3** — ✅ UI Godot: tela `ui/Delve.gd`+`.tscn` (launcher tier/elemento + Quest/Gather, mapa
  de nós clicável, HUD carregado/garantido, replay de batalha 3D via `request_battle`, modais de
  evento/resultado reusando UiKit). Registrada na nav do Shell (Aventura) + métodos no `BackendClient`
  (`expedition_*`). ⏳ Falta só riqueza extra de nós (loja na run, eventos do catálogo mais ricos).
- **Fase 4** — parcial (2026-06-14):
  - ✅ **i18n PT** das narrativas + erros da Incursão (`delve.*` + `error.expedition_*`/`error.knocked_out`
    em `messages_pt.properties`; EN inline via `messages.getOr`/`LocalizedException`).
  - ✅ **Perk VIP**: +1 nó de TESOURO garantido por run (`ExpeditionMapGenerator.generate(...,bonusTreasure)`,
    `player.isVip()?1:0`) + −20% estamina no start. Rehome do antigo "+1 daily".
  - ✅ **Guard tests** de balance (boss é o nó mais forte; VIP = +1 baú). Números seguem placeholders p/ playtest.
  - ⏸ **HOLD — remoção do daily-lock / quest→Incursão:** muda a economia viva (quest viraria farm repetível),
    reescreve testes deliberados (`dailyQuest_lockedAfterSuccessInSameWindow`, "VIP 2× daily") e **conflita com
    a retenção** [DAILY_QUESTS]/PLANO_RETENCAO_NOVATO. §12.2 aprovou em tese, mas por mexer no loop vivo +
    churn de teste, segurei p/ confirmação explícita (faço em seguida se o dono topar).

> **Nota de escopo (aditivo):** a Incursão foi adicionada como **nova aba**, sem remover os fluxos
> antigos de quest (`/api/world/.../quests`) nem de zona (`/api/zones`). A migração "quest/zona viram
> só Incursão" + remoção do daily-lock é a Fase 4 (decisão §12.2 mantém o daily como vitrine).

---

## 12. Decisões resolvidas (2026-06-14)
Todas fechadas com o dono — Fase 1 liberada.

1. **Coleta = igual à quest.** "Hoje a pesca é igual uma quest (pode aparecer inimigo ou não), vamos
   fazer igual." → A fonte **ZONE usa a MESMA profundidade/interação** que a KINGDOM (mesmo motor,
   mesmo mapa ramificado). Sem versão "rasa" separada.
2. **Daily fica como "run em destaque".** Mantém a rotação 12h **só como sabor/destaque** (quais runs
   aparecem em evidência), **NÃO** como trava. A Incursão é repetível (gate = estamina). Remover o
   *lock* (`isQuestDoneThisPeriod`/`completedWindowId` como bloqueio), manter a rotação como vitrine.
3. **Abandonar:** perde a bolsa **não-travada**, **sem KO**, e **só em parada segura** (nunca no meio
   de uma batalha). Confirmado.
4. **Nome EN (default):** **"Delve"** (curto, roguelike, não colide com "Expedition"=ZoneActivity).
   Placeholder — trocável no i18n.
5. **Profundidade default (default):** **4 camadas × 2-3 nós**, última = BOSS. Tier fácil ~3 camadas,
   difícil/🔴 ~5. Placeholder p/ tuning.
6. **Perk VIP (default, rehome do "1 daily a mais"):** VIP entra com **+1 nó de TESOURO garantido** na
   run (mais loot por incursão) + **−20% estamina de entrada**. Placeholder.

## 13. Fora de escopo (futuro)
- Mapa com mais tipos de nó (loja dentro da run, fonte de cura paga, mini-boss intermediário).
- Modificadores de run ("afixos de masmorra": +elite, −cura, +loot).
- Cadeia narrativa / story-quests por cima do motor (as authored quests viram arcos).
- Co-op / run de guild.

---

## Arquivos-chave (referência p/ implementação)
- [KingdomService](../backend/src/main/java/com/medieval/game/service/KingdomService.java) — quest
  atual, `rollDrop`, `fightQuestMonster`, padrão Luna `LUNA_PENDING`.
- [ZoneService](../backend/src/main/java/com/medieval/game/service/ZoneService.java) — coleta,
  `BOSS_PENDING`, `monsterLevelFor`, loot de chefe, PvP flag.
- [QuestOutcome](../backend/src/main/java/com/medieval/game/quest/QuestOutcome.java) +
  [InteractiveQuests](../backend/src/main/java/com/medieval/game/quest/InteractiveQuests.java) — motor
  de evento (vira nó EVENTO).
- [BattleSimulator](../backend/src/main/java/com/medieval/game/service/BattleSimulator.java) — combate
  encadeado (HP persiste).
- `docs/PLANO_ZONA_CHEFE.md`, `docs/PLANO_QUESTS_INTERATIVAS.md` — os dois padrões pending que a
  Incursão generaliza.
