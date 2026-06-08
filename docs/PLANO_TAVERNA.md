# PLANO — Taverna (aba do Comércio): beber + buff stackável + chat + avisos [TAVERNA]

> **Status:** ✅ IMPLEMENTADO (2026-06-08). Teste em `TavernServiceTest` (beber/buff/garrafas/marco + chat/cooldown).
> **Objetivo:** Uma aba **Taverna** no Comércio onde o jogador **bebe** (1 bronze + minigame de habilidade)
> pra **stacar um buff** em todos os stats, conversa num **chat** com outros players, e vê **avisos globais**
> (o 1º: "fulano bebeu +10 garrafas"; depois level-up, drop lendário, etc.).

## Decisões (confirmadas)
1. **Tempo real = polling** (sem WebSocket/SSE — não existe infra hoje; zero dependência nova).
2. **Buff: cada gole renova os 5 min INTEIROS** (treadmill — stacks crescem enquanto bebe; parar 5 min zera tudo).
3. **Força = 0.01%/stack (placeholder)**, cap 100% (=10.000 stacks, teto teórico). É mais loop de engajamento que buff forte.

---

## 1. Aba Taverna (Comércio)
- **index.html**: botão `🍺 Tavern` na barra de abas de `loc-panel-commerce` + `<div id="panel-tavern">` com 3 áreas: (a) beber + minigame + status do buff, (b) chat (feed + input), (c) o feed mostra também os avisos (linhas de sistema destacadas).
- **app.js**: `switchCommerceTab` ganha o caso `'tavern'` → `loadTavern()`. Padrão idêntico às 8 abas atuais.

## 2. Beber + minigame
- **Minigame** (client-side): barra de timing — um marcador varre a barra e o jogador clica quando está na **zona verde** (largura = dificuldade). Estilo "pesca". Roda no front; o resultado (acertou/errou) vai pro backend.
- **`POST /api/tavern/drink` `{success}`**: cobra **1 bronze sempre** (você comprou a bebida); `success=true` → +1 stack + renova o buff (5 min). `false` → nada (bronze gasto). Assim a **habilidade importa** (errar = desperdiçar bronze).
- **Sem estamina** (o gate é o bronze — o jogo quer que você fique bebendo). O custo (1 bronze) é o freio.
- ⚠️ **Confiança no cliente**: o minigame roda no front, então um trapaceiro pode mandar `success=true` sempre (paga 1 bronze e stacka). Pra um jogo pequeno + buff minúsculo, aceitável no v1 (o gate real é o bronze). Blindar = futuro.

## 3. Buff stackável (model + aplicação)
- **`Warrior`**: `tavernBuffStacks` (int default 0, cap 10000) + `tavernBuffExpiresAt` (LocalDateTime). Migração (`SchemaMigrator`).
- Helpers no `Warrior`: `tavernBuffActive()` = `stacks>0 && now<expiresAt`; `tavernBuffMultiplier()` = `active ? 1 + min(10000,stacks)*0.0001 : 1.0`.
- **Beber (sucesso)**: `stacks = active ? min(10000, stacks+1) : 1`; `expiresAt = now + 5min` (renova tudo). Expirado → recomeça em 1.
- **`WarriorStatsService.combatStats`**: no fim, multiplica **os 6 stats** `[atk,def,hp,dex,agi,luk]` por `tavernBuffMultiplier()`. (É a fonte de verdade dos combates.)
- **`clearBuff()` (KO/derrota)**: zera o tavern buff também (consistente com os outros buffs — perde o "porre").
- **`WarriorResponse`**: expõe `tavernBuffPct` (=stacks×0.01, máx 100) + `tavernBuffSecondsLeft` → badge 🍺 no sidebar (igual aos outros buffs).
- **Soft-wipe**: zera `tavernBuffStacks`/`tavernBuffExpiresAt` + `bottlesDrunk`.

## 4. Chat + avisos (polling)
- **`Player.bottlesDrunk`** (int default 0) — garrafas bebidas com sucesso (pros avisos de marco). Migração.
- **Model `TavernMessage`** (tabela `tavern_messages`): `id`, `senderPlayerId` (0 = sistema), `senderName` (nick + título via `AchievementService.titleString`), `text` (≤200), `type` (`CHAT`/`ANNOUNCEMENT`), `createdAt`. Por-servidor automático (1 banco por deploy [SERVIDORES]).
- **`TavernService`**:
  - `drink(player, success)`: cobra 1 bronze; em sucesso → stack + `bottlesDrunk++` + checa marco de garrafas → `announce`.
  - `postMessage(player, text)`: valida (não-vazio, ≤200, rate-limit simples ~1/3s) → salva CHAT.
  - `announce(text)`: salva ANNOUNCEMENT (sender 0/"📢"). Genérico — reusável pelos gatilhos futuros.
  - `feed(sinceId)`: mensagens com `id > sinceId` (ou últimas 50 se null), ordenadas; **prune** mantendo ~200.
- **`TavernController`**: `GET /feed?since=`, `POST /chat {text}`, `POST /drink {success}`, `GET /status` (buff + bottlesDrunk + custo).
- **Frontend (polling)**: com a aba aberta, `setInterval(~4s)` chama `feed?since=lastId`, anexa o novo, guarda `lastId`. **clearInterval ao sair da aba** (evita poll eterno). Input → POST `/chat` → refresh. Render **escapa** o texto (`escapeHtml`, XSS) e destaca ANNOUNCEMENT.
- **Avisos v1**: marco de garrafas — ao cruzar `[10,25,50,100,250,500,1000]`, `announce("🍺 {nick} bebeu {n} garrafas!")`. Os hooks de **level-up** (`WarriorService.addExperience`) e **drop lendário** (`KingdomService.rollDrop`/`ZoneService.rollBossLoot`) ficam mapeados como **follow-up fácil** (a gente liga "mais pra frente", como você disse).

## i18n
Aba/botões/minigame/badge/placeholder do chat + templates de aviso — EN + PT.

## Números (placeholders pra tuning)
0.01%/stack · cap 10000 (100%) · 5 min (renova no gole) · 1 bronze/gole · marcos `[10,25,50,100,250,500,1000]` · poll 4s · histórico 200 msgs · largura da zona do minigame = dificuldade.

## Ordem de implementação
1. Buff (Warrior fields + combatStats + WarriorResponse + badge + clearBuff + migração).
2. Beber + minigame (TavernService.drink + controller + minigame no front + status).
3. Chat + avisos (TavernMessage + feed/post + polling no front + marco de garrafas + announce genérico + soft-wipe).
Cada uma commitável separada.
