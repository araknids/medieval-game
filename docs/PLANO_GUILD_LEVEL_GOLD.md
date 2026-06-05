# Plano — Nível da Guild derivado do Gold acumulado

> Status: **implementado** (2026-06-05). Fonte da verdade da feature.
> Idioma: doc em PT; código e strings de UI em EN (traduz pro PT depois via i18n).

## Objetivo

Hoje o líder **sobe o nível manualmente** (botão "Level Up" gasta `level×1000` bronze do tesouro).
Mudar para: o nível da guild é **derivado automaticamente do gold ACUMULADO** que a guild já
recebeu (doações). Quanto mais a guild arrecada ao longo do tempo, maior o nível — sem botão.

## Decisões travadas (alinhadas com o dono)

| Pergunta | Decisão |
|---|---|
| Métrica do nível | **Gold acumulado** (contador que só cresce; nunca desce) |
| Tesouro | **Continua existindo** como recurso gastável (upkeep de território) — separado do nível |
| Curva | **Prestígio** (~10× a atual): Lv10 = **450.000 bronze (45 ouro)** acumulados |
| Level-up manual | **Removido** (leveling vira automático a partir das doações) |

## Contexto atual (resumo do código)

- `Guild.level` (campo, default 1) → dá `maxMembers()` (10+(lvl-1)×5, teto 50), `xpBonus()` (≤20%),
  `dropBonus()` (≤7%), `bronzeBonus()` (≤10%). **Esses formulas/tetos ficam iguais.**
- `Guild.levelUpCost()` = `level×1000` bronze. `GuildService.levelUp(leader)` checa tesouro, desconta, `level++`.
- Tesouro = `Guild.gold` (em bronze). Cresce em `donate()`; gasto em level-up **e** upkeep de território.
- `donate()` soma no tesouro + no `Player.guildDonatedBronze` (ranking de doadores).

## Modelo novo

### Campo novo
- `Guild.lifetimeGold` (long, bronze, default 0) — **total já doado** à guild. Só cresce.

### Curva (cumulativa, bronze para ATINGIR o nível N) — teto Lv10
`threshold(N) = 10_000 × (N-1) × N / 2`

| Nível | Gold acumulado | em ouro |
|---|---|---|
| 1  | 0       | — |
| 2  | 10.000  | 1  |
| 3  | 30.000  | 3  |
| 4  | 60.000  | 6  |
| 5  | 100.000 | 10 |
| 6  | 150.000 | 15 |
| 7  | 210.000 | 21 |
| 8  | 280.000 | 28 |
| 9  | 360.000 | 36 |
| 10 | 450.000 | 45 |

> Os **tetos de bônus** continuam: XP maxa no Lv5, drop no Lv6, bronze no Lv6, membros (50) no Lv10.
> Com a curva nova, maxar é objetivo de longo prazo da guild inteira.

### Lógica
- `Guild.levelForGold(long bronze)` (static) → maior N em 1..10 com `threshold(N) <= bronze`.
- `Guild.recomputeLevel()` → `level = max(level, levelForGold(lifetimeGold))` (cap 10).
  **Monotônico pra cima**: nunca rebaixa (protege guilds legadas que já subiram manualmente).
- `Guild.goldForNextLevel()` → `threshold(level+1)` (absoluto) ou `-1` se já no Lv10 (p/ exibir progresso).

### Fluxo de doação
`GuildService.donate(player, amount)`:
1. `playerService.spendBronze(player, amount)` (gold sink do jogador — mantém).
2. `guild.gold += amount` (tesouro gastável — mantém).
3. `guild.lifetimeGold += amount` (novo).
4. `guild.recomputeLevel()` → se subiu, loga `levelUp` e a resposta sinaliza pra UI dar o toast.

## Mudanças por arquivo

### Backend
- **`model/Guild.java`**: + `lifetimeGold`; + `levelForGold(long)` (static), `recomputeLevel()`,
  `goldForNextLevel()`. `levelUpCost()` removido (ou vira `goldForNextLevel()`). Constante `MAX_LEVEL=10`.
- **`service/GuildService.java`**: `donate()` soma `lifetimeGold` + `recomputeLevel()` + log de level-up;
  **remove** `levelUp(leader)`.
- **`controller/GuildController.java`**: remove `POST /api/guild/levelup`. Resposta de `GET /api/guild`:
  troca `levelUpCost/levelUpCostFmt` por `lifetimeGold`, `lifetimeGoldFmt`, `nextLevelGold`
  (absoluto, ou null no Lv10), `goldToNextLevel` (faltante), `progressPct`. `donate()` pode devolver
  `level`/`leveledUp` pra UI.
- **`config/SchemaMigrator.java`**: `ALTER TABLE guilds ADD COLUMN IF NOT EXISTS lifetime_gold bigint NOT NULL DEFAULT 0`;
  seed `UPDATE guilds SET lifetime_gold = gold WHERE lifetime_gold = 0` (baseline; não rebaixa nível).

### Frontend (`static/app.js`)
- `renderGuildPanel()`: remove o botão "Level Up" e `guildLevelUp()`. Mostra **barra de progresso**:
  `Lv.X · accumulated <lifetimeGoldFmt> · next level at <nextLevelGoldFmt> (<goldToNextLevelFmt> to go)`,
  ou `Max level` no Lv10. `guildDonate()` mostra toast "Guild reached level N!" quando `leveledUp`.

### Testes
- **`GuildModelTest`**: ajusta o que assume `levelUpCost`/level manual; novos: `levelForGold` nos
  limiares (9.999→Lv1, 10.000→Lv2, 449.999→Lv9, 450.000→Lv10, 9.999.999→Lv10 cap), `recomputeLevel`
  monotônico (não rebaixa).
- **Integração**: remove TCs do endpoint `/levelup`; novos: doar até cruzar um limiar sobe o nível
  (e `maxMembers` acompanha); doar abaixo do limiar não sobe; resposta traz os campos de progresso.

## Consequências / notas
- Nível **nunca cai** (acumulado só cresce) → `maxMembers` nunca encolhe → ninguém é "expulso" por de-level.
- Upkeep de território drena só o **tesouro**, não o `lifetimeGold` → não afeta o nível. ✔
- Doação continua sendo **gold sink do jogador** (sai do bolso do doador pro tesouro). ✔
- Guilds legadas que já subiram manualmente **mantêm** o nível (recompute é monotônico).
