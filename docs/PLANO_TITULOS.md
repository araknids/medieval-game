# PLANO — Achievements + Títulos [TITULOS]

> Status: **IMPLEMENTADO** (2026-06-06). Arena-opponent title (modal de resultado) ficou fora do v1
> (snapshot transiente — exigiria coluna/plumbing); demais superfícies entregues.
> Cria um **sistema de achievements de 1ª classe** (entidade, página, desbloqueio rastreado,
> notificação) e os **títulos** exibidos antes do nick, visíveis pros outros (ranks/guilda/arena).

## Conceito

Cada **achievement** é desbloqueado ao bater um marco (nível, vitórias de arena, andar da torre,
riqueza, classe, guilda). Achievement desbloqueado libera um **título** (prefixo curto antes do nome).
O jogador **escolhe** 1 título ativo entre os desbloqueados (ou nenhum). O título aparece antes do
nick **em tudo que os outros veem**: ranking de arena, roster de guilda, inimigos de guerra de guilda,
oponente de arena, e o próprio header. Ex.: `Aventureiro Arak`.

## Decisões (alinhadas com o dono)

| Tema | Decisão |
|------|---------|
| Modelo | **Sistema de achievements completo** (entidade `PlayerAchievement` + página + notificação) |
| Seleção do título | **Jogador escolhe** 1 ativo entre os desbloqueados (ou "nenhum") |
| Categorias v1 | **Classe, Nível/Veterania, Arena/PvP, Torre/Riqueza/Guilda** (as 4) |

## Modelo de dados

- **`Achievement` (enum)** — catálogo estático. Cada constante:
  `(category, title, displayName, description, metric, threshold)`.
  - `category` (`AchievementCategory`): CLASS / LEVEL / ARENA / TOWER / WEALTH / GUILD.
  - `title` (String EN) — o prefixo exibido (ex.: "Adventurer"). **Todo achievement v1 dá um título** (1:1).
  - `metric` (`AchievementMetric`) + `threshold` (long) — regra genérica de desbloqueio
    (desbloqueia quando `valor(metric) >= threshold`). Sem lambdas no enum → o service mapeia metric→valor.
- **`PlayerAchievement` (entidade nova)** — `(id, player FK, achievement enum, unlockedAt)`,
  **único** por `(player, achievement)`. Tabela `player_achievements` (auto-criada pelo JPA).
- **`Player.activeTitle`** (String, nullable) — guarda o `Achievement.name()` do título ativo
  (null/"" = sem título). Coluna nova `players.active_title` (via `SchemaMigrator`).

> Por que metric+threshold em vez de lambda: o enum fica puro (sem dep de service), o
> `AchievementService` calcula o valor de cada métrica 1x por player e compara. Igual ao padrão
> de `KingdomQuestType` (dados no enum, lógica no service).

### `AchievementMetric` → de onde vem o valor (já existe tudo)
| Metric | Valor | Fonte |
|---|---|---|
| `LEVEL` | `warrior.getLevel()` | Warrior |
| `ARENA_WINS` | `player.getArenaWins()` | Player |
| `RANK_POINTS` | `player.getRankPoints()` | Player |
| `TOWER_FLOOR` | `player.getTowerBestFloor()` | Player |
| `WEALTH` | `player.totalBronze()` | Player |
| `CLASS_WARRIOR/ARCHER/MERCHANT` | `warriorClass==X ? 1 : 0` | Warrior |
| `GUILD_MEMBER` | `guild != null ? 1 : 0` | Player |
| `GUILD_LEADER` | `é líder ? 1 : 0` | Player/Guild |

## Achievement enum — lista v1 (~16 títulos; números = placeholder)

| Category | Achievement | Título | Condição |
|---|---|---|---|
| CLASS | PATH_WARRIOR | **Blade** | classe = Warrior |
| CLASS | PATH_ARCHER | **Hunter** | classe = Archer |
| CLASS | PATH_MERCHANT | **Trader** | classe = Merchant |
| LEVEL | LEVEL_10 | **Adventurer** | nível ≥ 10 |
| LEVEL | LEVEL_25 | **Veteran** | nível ≥ 25 |
| LEVEL | LEVEL_50 | **Legend** | nível ≥ 50 |
| ARENA | ARENA_10 | **Duelist** | 10 vitórias de arena |
| ARENA | ARENA_50 | **Gladiator** | 50 vitórias de arena |
| ARENA | RANK_1500 | **Champion** | rankPoints ≥ 1500 |
| TOWER | TOWER_10 | **Tower Climber** | melhor andar ≥ 10 |
| TOWER | TOWER_25 | **Tower Conqueror** | melhor andar ≥ 25 |
| WEALTH | WEALTH_RICH | **Wealthy** | riqueza ≥ 100.000 bronze (10🥇) |
| WEALTH | WEALTH_MAGNATE | **Magnate** | riqueza ≥ 1.000.000 bronze (100🥇) |
| GUILD | GUILD_MEMBER | **Kin** | está numa guilda |
| GUILD | GUILD_LEADER | **Guildmaster** | é líder de guilda |

(Strings em inglês — UI em EN, i18n PT depois, conforme padrão do projeto.)

## `AchievementService` (lógica)

- `checkAndUnlock(player)` → carrega player+warrior, calcula o valor de cada métrica, e **insere**
  em `player_achievements` os achievements recém-cumpridos (que ainda não tem linha). Retorna a
  lista dos **novos** (p/ notificação). Idempotente (o índice único + o "já tem?" evitam duplicar).
- `list(player)` → todos os achievements do catálogo com `{unlocked, unlockedAt, title, category, desc, progress}`
  (progress = valor atual vs threshold, p/ a barra na página).
- `unlockedTitles(player)` → os títulos desbloqueados (subconjunto, p/ o picker).
- `selectTitle(player, achievementId|null)` → valida que está desbloqueado e seta `player.activeTitle`.
  `null`/"none" limpa.
- `activeTitleOf(player)` → resolve o `activeTitle` → string do título (ou "" se nenhum/ inválido).
  **In-memory** (lê `player.getActiveTitle()` → `Achievement.title`), sem hit extra de DB por linha
  de ranking.

### Gatilhos de `checkAndUnlock`
Chamado depois das ações que mexem nas métricas, pra desbloquear "na hora":
- Arena (`ArenaService.fight` — wins/rank), Torre (`TowerService` — andar),
- Ganho de XP/level (`WarriorService.addExperience` quando sobe de nível),
- Troca de classe (`ClassChangeService.attemptTrial` — títulos de classe),
- Entrar/criar/virar líder de guilda (`GuildService`),
- E **lazy** no `GET /api/achievements` (rede de segurança p/ marcos já batidos antes da feature).

## Endpoints (`AchievementController`, `/api/achievements`)

- `GET /api/achievements` → `{ activeTitle, achievements: [...], titles: [...] }` (roda `checkAndUnlock` antes).
- `POST /api/achievements/title` body `{ id: "LEVEL_10" | null }` → seleciona/limpa o título ativo.

## Onde o título aparece (pros outros) — campo `title` separado no DTO

| Superfície | Arquivo | Mudança |
|---|---|---|
| Ranking de arena | `ArenaService.RankEntry` / `ArenaController` | + campo `title` (de `activeTitleOf(player)`) |
| Roster de guilda | `GuildController.toDetail` (member map) | + chave `"title"` por membro |
| Inimigos de guerra de guilda | resposta do war roster | + `"title"` por inimigo |
| Oponente de arena | `ArenaService` (opponentName) | resposta separa `opponentTitle` (NÃO grava no `ArenaMatch`, fica vivo) |
| Header do próprio jogador | `WarriorController.buildResponse` | + `title` (mostra no topo) |

> **Mail**: o nome do remetente é um **snapshot histórico** (gravado no envio) → **não** recebe título
> (ficaria desatualizado). Fora do v1.

O front compõe `${title} ${nome}` (com espaço só quando há título). Título separado do nome evita
parsing e mantém o nome "puro" pra busca/igualdade.

## Frontend

- **Página de Achievements/Títulos** (nova aba ou dentro do Character): lista os achievements por
  categoria com ✓/🔒 + barra de progresso; um seletor de **título ativo** entre os desbloqueados
  (inclui "Nenhum"). `POST` ao escolher.
- **Render do título** em: `loadRank` (arena), `renderGuildPanel` (roster + doação), `loadGuildWar`
  (inimigos), modal de `startFight` (oponente), e header do jogador. Helper `titleName(o)` →
  `o.title ? o.title+' ' : ''`.
- **Toast de desbloqueio**: quando uma resposta de ação trouxer `newAchievements`, mostra
  "🏆 Achievement unlocked: X". (Fonte da verdade continua a página.)

## Migração (Postgres)

- `player_achievements` — auto-criada pelo Hibernate (entidade nova).
- `players.active_title varchar(40)` — `SchemaMigrator.ALTER TABLE ... ADD COLUMN IF NOT EXISTS`.
- Sem check-constraint de enum no banco (achievement guardado como String).

## Soft-wipe

`MaintenanceService.softWipe`: **apaga `player_achievements`** (filho do player) e zera
`player.activeTitle` no `resetPlayer`. (Consistente: wipe = fresh start, conquistas zeram.)

## Testes

- `AchievementServiceTest`: thresholds (level/arena/tower/wealth/class/guild), idempotência do
  `checkAndUnlock` (não duplica), `selectTitle` valida desbloqueio (rejeita título travado),
  `activeTitleOf` resolve certo / vazio quando none.
- Integração: `GET /api/achievements` lista + desbloqueio lazy; `POST .../title` seta e aparece no
  ranking de arena (campo `title`); soft-wipe limpa achievements + activeTitle.

## Fora de escopo (futuro)

- Achievements "de evento" (matar X mobs, coletar Y, craftar Z) — exigem **contadores** novos
  (hoje não há tracking de kills/coletas acumulados). v1 só usa métricas que já existem.
- Recompensas por achievement (bronze/SoulStone) além do título.
- Título no mail / no log de batalha histórico.
- Multi-título / molduras / cores de raridade de título.

## Números / placeholders

Thresholds (10/25/50 níveis, 10/50 wins, rank 1500, torre 10/25, riqueza 100k/1M) e os nomes dos
títulos são **placeholders p/ tuning no playtest**.
