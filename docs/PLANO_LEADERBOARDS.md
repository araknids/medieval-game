# PLANO — Leaderboards + Perfil/Inspeção + Social (carta, amizade, convite) — [LEADERBOARDS]

> Status: **estrutura aprovada** (decisões abaixo). Implementação em fatias commitáveis.
> Idioma: doc em PT; **toda UI/string exibida em EN** (depois i18n PT via Lang.gd).

## 1. Objetivo

Nova aba **Leaderboards** na barra lateral com rankings do servidor, e — ao clicar num jogador —
um **dialog de jogador** com ações sociais: **adicionar amigo · enviar carta (compositor com nome
preenchido) · convidar p/ guilda · inspecionar (perfil read-only) · voltar**.

Escopo escolhido pelo dono: **tudo de uma vez** (inclui os subsistemas de Amizade e Convite-de-guilda,
que **não existem hoje**, e o **compositor de carta**, que também não existe no cliente Godot).

## 2. Decisões (do dono)

1. **Fases:** tudo junto (leaderboards + inspecionar + carta + amizade + convite).
2. **Aba Guildas:** tem **várias sub-categorias** próprias (não uma métrica só).
3. **Território:** a métrica é **quantas "incursões" o player fez naquele território** ("ajudando a
   cidade"). A Incursão roguelike (`docs/PLANO_INCURSAO.md`) **ainda não existe** → modelamos o
   **contador agora** (`TerritoryContribution`) alimentado pela atividade de território de hoje, e a
   futura Incursão **incrementa o mesmo contador** quando existir (forward-compatible).

## 3. Categorias do leaderboard

Sub-abas (chips, padrão da Ficha). Cada linha: `#rank · nome (+título) · classe/nível · valor`.

| Sub-aba (UI EN) | Métrica | Fonte | Novo? |
|---|---|---|---|
| **Level** | `warrior.level` (desempate: `experience`) | existe | — |
| **Arena** | `player.rankPoints` (+ wins/losses) | existe (`/api/arena/rank`) | — |
| **Tower** | `player.towerBestFloor` | existe (`/api/tower/ranking`) | — |
| **Hunter** (mobs) | `warrior.mobKills` | **novo contador** | ✅ |
| **Slayer** (PvP) | `player.playerKills` | **novo contador** | ✅ |
| **Wealth** | `player.totalBronze()` | existe | — |
| **Guilds** | sub-categorias (ver §4) | parcial | ✅ |
| **Territory** | `TerritoryContribution.incursions` por território (picker) | **nova entidade** | ✅ |

> Reaproveitamos Arena/Tower mas servimos **tudo por um endpoint unificado** (`/api/leaderboard/...`)
> pra UI ter um formato de linha só. Arena/Tower antigos continuam existindo (não removemos).

## 4. Aba "Guilds" — sub-categorias

Chips aninhados dentro da aba Guilds:

| Sub-cat (UI EN) | Métrica | Fonte |
|---|---|---|
| **Power** (Level) | `guild.level` / `guild.lifetimeGold` | existe |
| **Territory** | nº de territórios dominados (peso por `defenseStreak`) | `TerritoryControl` |
| **War kills** | Σ kills de guerra da guilda | `GuildWar.killsA/killsB` agregado (novo `guild.warKills` acumulado — ver §5) |
| **Members** | nº de membros (poder somado, opcional v2) | `Player.guild` count |

Linha de guilda: `#rank · nome da guilda · nível · valor`. Clicar numa guilda (v2) pode abrir a guilda;
v1 só exibe (não toca em `Guild.gd` — ver §9 constraint).

## 5. Modelo de dados (novos campos / entidades + migração)

Padrão de migração: `SchemaMigrator` com `ADD COLUMN IF NOT EXISTS ... DEFAULT` (ver
`patchAbilityPointsColumn`). Campos `int` novos com `@Column(columnDefinition="integer default 0")`.

### 5.1 Contadores em entidades existentes
- `Warrior.mobKills` (`int`, default 0) — **+1 a cada vitória PvE**.
- `Player.playerKills` (`int`, default 0) — **+1 a cada vitória PvP** (matou outro jogador).
- `Guild.warKills` (`long`, default 0) — acumulado de kills de guerra da guilda (alimentado quando
  uma guerra resolve / a cada kill), pra ranquear sem varrer `GuildWar` histórico.

### 5.2 Nova entidade `TerritoryContribution` (tabela `territory_contributions`)
```
id (pk)
player_id (FK Player)        -- quem contribuiu
kingdom  (varchar enum)      -- qual território (Kingdom)
incursions (int default 0)   -- nº de incursões/ajudas naquele território
UNIQUE(player_id, kingdom)   -- 1 linha por (player, território) → upsert/increment
```
Increment idempotente por (player, kingdom). Repo: `findByPlayerAndKingdom`, `incrementOrCreate`,
top por kingdom (`findByKingdomOrderByIncursionsDesc(Pageable)`).

### 5.3 Subsistema **Amizade** — `Friendship` (tabela `friendships`)
```
id (pk)
requester_id (FK Player)
addressee_id (FK Player)
status (varchar: PENDING | ACCEPTED)   -- (BLOCKED fica p/ v2)
created_at
UNIQUE(requester_id, addressee_id)
```
Aceito = relação simétrica lógica (consulta nas duas direções). Sem duplicar linha invertida.

### 5.4 Subsistema **Convite de Guilda** — `GuildInvite` (tabela `guild_invites`)
```
id (pk)
guild_id (FK Guild)
inviter_id (FK Player)     -- quem convidou (precisa ser líder)
invitee_id (FK Player)     -- convidado
status (varchar: PENDING | ACCEPTED | DECLINED)
created_at
UNIQUE(guild_id, invitee_id) WHERE status=PENDING (índice parcial)
```
> Hoje entrar em guilda é **aberto** (`POST /api/guild/join/{id}`, sem permissão). O convite é um
> caminho **paralelo**: líder convida → convidado aceita → entra (respeitando capacidade + lock).
> O join aberto continua existindo.

## 6. Onde os contadores incrementam

| Contador | Pontos de incremento |
|---|---|
| `mobKills` | `TowerService` (andar limpo), `ArenaService` (vs **NPC**), `ZoneService.fightNpc` + caça (`resolveCombatHunt`, por kill) + chefe errante vencido, `KingdomService` (quest de combate vencida) |
| `playerKills` | `ArenaService` (vs **jogador real** vencido), `ZoneService` raid (saque PvP vencido), `TerritoryService`/`GuildWarService` (kill em guerra) |
| `Guild.warKills` | a cada kill de guerra registrado (mesmos pontos do guild-war), `+=` |
| `TerritoryContribution.incursions` | **quest completada num kingdom** (`KingdomService.collectQuest`) = +1 "incursão ajudando a cidade". **Futuro:** run de Incursão concluída no kingdom = +1/+N (mesmo contador) |

> Vitória é decidida pela tag `WINNER:` do `BattleSimulator` (já removida do log antes de exibir).
> Ex.: `TowerService` usa `out.firstWon()`; `ArenaService` idem. Incrementa **só na vitória**.

## 7. Endpoints (backend)

`LeaderboardController` + `LeaderboardService` (Controller→Service→Repository, paginado estilo
`ArenaMatchRepository.findTopRanked(Pageable)`):

- `GET /api/leaderboard/{category}?page=0` — `category ∈ {level, arena, tower, hunter, slayer, wealth}`.
  Retorna `[{rank, playerId, warriorName, title, level, classId, gender, value}]` (paginado, 20/pág).
- `GET /api/leaderboard/guild/{subcat}?page=0` — `subcat ∈ {power, territory, warkills, members}`.
  Retorna `[{rank, guildId, guildName, level, value}]`.
- `GET /api/leaderboard/territory/{kingdom}?page=0` — top contribuintes daquele território.
- `GET /api/leaderboard/territories` — lista de territórios (kingdoms de guerra) p/ o picker.

**Inspeção de perfil** (novo) — `GET /api/players/{id}/profile`:
`{playerId, warriorName, title, level, classId, gender, attributes{str,dex,con,agi,luk}, combat{atk,def,hp},
equipped:[{type, name, rarity, itemLevel, attackBonus, defenseBonus, healthBonus, strBonus, dexBonus, lukBonus, sockets, outfitTheme}]}`.
Monta com `WarriorStatsService` + itens `equipped=true` (mesmo shape do inventário p/ reusar
`ItemTooltipCard`). **Read-only**, sem dados sensíveis (sem bag/stash/moeda detalhada).

**Amizade** — `FriendController` (`/api/friends`):
- `GET /api/friends` → `{friends:[...], incoming:[...], outgoing:[...]}`
- `POST /api/friends/request/{playerId}`
- `POST /api/friends/accept/{requestId}` · `POST /api/friends/decline/{requestId}`
- `DELETE /api/friends/{playerId}` (remover amigo)

**Convite de guilda** — em `GuildController` (endpoints novos, **sem alterar a UI Guild.gd**):
- `POST /api/guild/invite/{playerId}` (só líder; valida capacidade)
- `GET  /api/guild/invites` → convites PENDING **recebidos** pelo jogador
- `POST /api/guild/invites/{id}/accept` · `POST /api/guild/invites/{id}/decline`

**Carta** — backend **já existe**: `POST /api/mail/send {recipientWarriorName, message, goldAmount}`.
Falta só o **cliente** (compositor + `mail_send`).

## 8. Cliente Godot

### 8.1 Tela `ui/Leaderboards.gd` + `.tscn`
- Registrar no `Shell._build_nav()` (`_nav_item("Leaderboards", "Leaderboards")`) + `NAV_TIPS` + ícone
  (reusa `achievements`/troféu; gerar `leaderboards.png` depois se quiser).
- `UiKit.scaffold(...)` → header com voltar/refresh. Sub-abas (chips estilo Ficha). Aba Guilds tem
  chips aninhados; aba Territory tem picker de território.
- Linha = `UiKit.card` compacto: `#rank` + nome (+título) + classe/nível + valor; **linha inteira
  clicável → `_player_dialog(entry)`**.
- `Api.leaderboard(category, page)`, `Api.leaderboard_guild(sub, page)`,
  `Api.leaderboard_territory(kingdom, page)`, `Api.leaderboard_territories()`.

### 8.2 Dialog do jogador (dim+center, padrão `Forge._craft_dialog`)
Card com nome/título/nível + botões:
- **Add friend** → `Api.friend_request(id)` → toast.
- **Send letter** → fecha dialog, abre **Mail** em modo compositor com `recipient` preenchido.
- **Invite to guild** → só aparece se `meu perfil` tem guilda **e** sou líder; `Api.guild_invite(id)`.
- **Inspect** → `_inspect_dialog(id)` (perfil read-only).
- **Back/close**.

### 8.3 Inspeção (read-only) — dialog ou sub-tela
Reusa render de slots equipados + `_stat_chip` da `Character.gd` (lógica copiada/extraída p/ helper,
**sem ações** de equipar/vender). `ItemTooltipCard` nos equipados (hover idêntico ao inventário).
Fonte: `Api.player_profile(id)`.

### 8.4 Compositor de carta (NOVO) em `ui/Mail.gd`
- Botão **"Write"** (ou aba) → form: `recipient` (LineEdit, pré-preenchível) + `message` (TextEdit)
  + `gold` (SpinBox opcional) + enviar.
- `Api.mail_send(recipient, message, gold)` → `POST /api/mail/send`.
- **Prefill:** estado estático (ex.: `Shell.pending_mail_recipient` ou autoload) setado pelo dialog
  do jogador; `Mail._ready()` consome e limpa. (Shell não passa params hoje → usar estado leve.)

### 8.5 Home de Amizade e Convites (constraint Guild.gd — §9)
- **Amigos**: uma sub-aba **"Friends"** dentro de Leaderboards (lista + aceitar/recusar pedidos),
  já que é a tela social. (Não criamos tela nova solta.)
- **Convites de guilda recebidos**: como **não posso tocar `Guild.gd`**, o aceitar/recusar de convites
  fica **também numa sub-aba de Leaderboards** ("Invites") e/ou um badge. Quando a outra aba liberar a
  Guilda, dá pra mover/duplicar lá.

## 9. ⚠️ Constraint: NÃO tocar `Guild.gd`

Outra aba está editando a **tela de Guilda** (`godot-client/ui/Guild.gd`). Portanto:
- **Backend** de convite pode ir no `GuildController` (arquivo diferente, sem conflito).
- **UI** de convite (enviar/aceitar) **não** entra no `Guild.gd` — vai na tela Leaderboards.
- Conferir antes de commitar que `Guild.gd` não está no diff.

## 10. i18n

Strings novas em **EN** no código; adicionar chaves no `Lang.gd` (PT) depois. Reusar chaves de erro
existentes onde fizer sentido. Backend: mensagens via `Messages.tr()` quando aplicável.

## 11. Números / placeholders

Página = 20. Sem custo pra add amigo/convite (v1). Limite de amigos / convites pendentes: placeholder
(ex.: 50 amigos, convite expira em 3 dias — pode entrar depois). Tudo tunável no playtest.

## 12. Ordem de implementação (fatias commitáveis)

1. **Backend leitura** (baixo risco): contadores `mobKills`/`playerKills`/`warKills` + migração +
   incrementos nos serviços; `TerritoryContribution` + increment no `collectQuest`; `LeaderboardService`
   + `LeaderboardController` + repos; `GET /players/{id}/profile`. → commit.
2. **Backend social**: `Friendship` + `FriendController`; `GuildInvite` + endpoints no `GuildController`. → commit.
3. **Cliente — Leaderboards**: tela + sub-abas + linhas + `Api.*`. → commit.
4. **Cliente — dialog do jogador + inspeção**. → commit.
5. **Cliente — compositor de carta** (Mail.gd) + prefill. → commit.
6. **Cliente — Friends/Invites** sub-abas. → commit.
7. i18n PT das strings novas. → commit.

Testes: backend roda no CI (H2 + Postgres). Godot validado pelo dono (reload do projeto).
