# PLANO — PvP por escolha na Incursão + sets dos inimigos + mail da vítima [INCURSAO_PVP]

## Contexto

O PvP da Incursão **já existe em grande parte** (`ExpeditionService`): flag ao entrar em 🟡/🔴
(`Player.pvpFlaggedZone/Until`, 60min), matchmaking `findFlaggedOpponent` (±10 níveis, sem escudo),
`resolvePvpRaid` (combate completo) + `raidVictim` (🟡 10% bronze+XP · 🔴 15% bronze + 50% recursos +
1 item travado + XP) + mail de sistema pra vítima. **MAS** dispara por **chance aleatória** (20%/40%
por nó de combate, linhas 343-348) — não por escolha.

## Decisões do dono
1. **PvP só por ESCOLHA** — tira o raid aleatório. Em nó de combate 🟡/🔴, se há vítima flagada, o
   atacante escolhe "Lutar monstro (PvE)" ou "Atacar jogador (PvP)".
2. **Replay no mail AGORA** — o mail da vítima guarda o LOG + os eventos da batalha; a vítima vê o
   texto + botão "Ver replay" (anim 3D, o cliente já toca eventos externos).

## Fase A — Sets + mobs dos inimigos (cliente, `BattleReplay.gd`)
Hoje os inimigos da Incursão caem na lógica genérica (`fight_scene` ≠ "tower") → vestidos pela
metade/pelados. Os **nomes** já vêm temáticos por reino (`KingdomQuestNarrator.pickMonster/Elite/Boss`).
Adicionar um caminho pras cenas da Incursão/Zona (`coast/sea/cave/fortress`):
- **Set completo** sempre (igual ao [TORRE_VESTE]), tema **por bioma**:
  `fortress→knight/noble`, `cave→knight/ranger`, `coast→ranger/noble`, `sea→ranger/knight` (hash escolhe
  → variedade + cara do bioma). Recolor por raridade (hash). Boss um pouco maior/raridade alta.
- Resolve "inimigos sem set" + dá skin relacionada à zona. Nome já é da zona (narrator).

## Fase B — PvP por escolha (backend + cliente)
**Backend (`ExpeditionService`/`Controller`/`ExpeditionRun`):**
- `ExpeditionRun.pendingPvpVictimId` (Long, migração) + preview da vítima no `pendingEventData`.
- `choose()`: nó COMBAT/ELITE em zona 🟡/🔴 com vítima disponível (`findFlaggedOpponent`) →
  `NODE_PENDING` + devolve `pvpChoice` + preview {nome, nível, poder} em vez de auto-resolver.
- Novo `resolveCombatChoice(player, runId, boolean pvp)` + endpoint `POST /{id}/combat {pvp}`:
  `pvp=false` → `resolveBattleNode(nó guardado)`; `pvp=true` → `resolvePvpRaid(vítima guardada)`.
- **Remove** o bloco de raid aleatório (343-348) — `pvpRaidEnabled` deixa de gatilhar no combate.
- `ChooseResult`/`runState` ganham `pvpChoice` + `pvpOpponent`.

**Cliente (`Delve.gd`):** resposta com `pvpChoice` → `_icon_choice_dialog` ("Lutar monstro" vs
"Atacar <vítima>") → chama `/combat` com a escolha → o resultado segue o fluxo de replay normal.

## Fase C — Mail da vítima com log + replay (backend + cliente)
**Backend (`Mail`/`MailService`):**
- `Mail` ganha `battleLog` (TEXT), `battleEventsJson` (TEXT), `battleScene` (String) — migração
  `patchMailBattleColumns`.
- `MailService.sendRaidMail(victim, attackerName, lootSummary, log, eventsJson, scene)` — substitui o
  `sendSystemMail` do `raidVictim`. Eventos serializados via Jackson (mesmo shape do `battleEvents` da API).
- `raidVictim` passa o log/eventos/cena (perspectiva: atacante=esquerda, vítima=direita).

**Cliente (tela de Mail):** mostra o `battleLog` no detalhe + botão "Ver replay" que emite
`request_battle {events, scene}` (App abre o `BattleReplay` por cima). Sem replay salvo = sem botão.

## Arquivos
| Camada | Arquivo | Mudança |
|---|---|---|
| A | `BattleReplay.gd` | caminho de cena de Incursão → set completo temático por bioma |
| B | `ExpeditionRun.java` | `pendingPvpVictimId` + migração |
| B | `ExpeditionService.java` | choose injeta escolha; `resolveCombatChoice`; tira raid aleatório |
| B | `ExpeditionController.java` | endpoint `/combat`; `pvpChoice`/`pvpOpponent` no JSON |
| B | `Delve.gd` | diálogo de escolha PvE/PvP |
| C | `Mail.java` | `battleLog`/`battleEventsJson`/`battleScene` + migração |
| C | `MailService.java` | `sendRaidMail` (serializa eventos) |
| C | tela de Mail (cliente) | log + botão de replay |

## Verificação
- `mvn -o clean test` (toca ExpeditionService/Mail — testes de raid/expedition).
- Godot: Incursão em zona 🟡/🔴 com 2 contas → nó de combate oferece PvE/PvP; vítima recebe mail com
  log + replay; inimigos PvE aparecem **bem vestidos** e temáticos do bioma.

## Notas
- BOSS não oferece PvP (clímax PvE). Só COMBAT/ELITE.
- Replay na ótica da vítima mostra o atacante à esquerda e ela à direita (ela perdeu) — aceitável v1.
- Números (penalidades, banda de nível) seguem os já existentes.
