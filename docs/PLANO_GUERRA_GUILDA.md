# Plano — Guerra de Guilda (kills + roubo de gold com de-level)

> Status: **implementado** (2026-06-05). Fonte da verdade da feature.
> Idioma: doc em PT; código e strings de UI em EN (traduz pro PT depois via i18n).
> ⚠️ NÃO confundir com a **Guerra de Território** (ciclos de 6h, controle de reino). São features distintas.

## Objetivo

Duas guildas declaram guerra por **7 dias**. Qualquer membro pode **atacar** membros da guilda
inimiga direto pelo painel da guild, com o **mesmo prejuízo da zona PvP vermelha**. Quem **vencer a
luta** dá +1 kill pra sua guild (simétrico). No fim dos 7 dias, a guild com **mais kills** leva **25%
do gold acumulado** da inimiga — o que pode **rebaixar o nível** do perdedor (e subir o do vencedor).
**Elegibilidade**: pra declarar/ser declarada, a guild precisa ter **conquistado um território ao menos
uma vez**.

## Decisões travadas (alinhadas com o dono)

| Tema | Decisão |
|---|---|
| Prêmio | **25% do gold ACUMULADO** (lifetimeGold) do perdedor → vencedor |
| Nível | Guerra é a **exceção à regra monotônica**: perdedor **regride**, vencedor pode subir |
| Kill | **Simétrica** — quem vence a luta ganha a kill; o perdedor leva o prejuízo da zona vermelha |
| Anti-farm | **Escudo no derrotado (1h)** + **estamina por ataque** (25) |
| Elegibilidade | Ambas as guildas precisam ter **conquistado um território** alguma vez |
| Duração | **7 dias**; **1 guerra ativa por guild** |

## Modelo

### Elegibilidade
- `Guild.everControlledTerritory` (bool, default false). Setado em `TerritoryService.resolveTerritory`
  quando a guild **toma** um território (no ponto `control.setControllingGuild(newHolder)`).

### Declaração
- Líder declara guerra numa guild-alvo elegível → cria `GuildWar(guildA, guildB, ACTIVE,
  startedAt, endsAt=+7d, killsA=0, killsB=0)`. Valida: ambas elegíveis, nenhuma já em guerra ativa,
  não é a própria guild.

### Ataque (qualquer membro) — `POST /api/guild/war/attack/{targetPlayerId}`
- Valida: atacante numa guild com guerra ativa; alvo é membro da guild **inimiga** dessa guerra;
  alvo **não escudado** e **não nocauteado**; atacante **não nocauteado**; estamina suficiente.
- Combate PvP: `combatStats` dos dois (gear+buffs+postura+pet) → `BattleSimulator.simulateDetailed`.
- **Vencedor** (atacante OU defensor): +1 kill pra guild dele (killsA/killsB). HP dos dois persiste.
- **Perdedor** leva o **prejuízo da zona vermelha** (reusa o raid): −15% bronze (metade vai pro vencedor),
  −50% recursos (pro vencedor), 35% de perder 1 item exposto, perde XP (vencedor +50%). + **escudo 1h** +
  mail. (Item: trava só os expostos no momento da derrota, rouba 1, destrava o resto — sem flag persistente
  de uma semana.)
- Cobra **25 de estamina** do atacante. Reaproveita `ZoneService.applyGuildWarRaid(winner, loser)`.

### Resolução (7 dias) — lazy + scheduler diário
- Quando `endsAt` passa: vencedor = mais kills. `stolen = round(25% × loser.lifetimeGold)`:
  - **Perdedor**: `lifetimeGold -= stolen`; `gold = max(0, gold − stolen)`; `level = levelForGold(lifetimeGold)`
    (set direto → **pode cair**).
  - **Vencedor**: `lifetimeGold += stolen`; `gold += stolen`; `recomputeLevel()` (monotônico → **pode subir**).
  - **Empate** → sem transferência (draw).
- `status=RESOLVED`, `winnerGuildId`, kills finais. Mail aos líderes. Idempotente (`GuildWarScheduler`
  diário + lazy on-read, padrão do território).

## Mudanças por arquivo

### Backend
- **`model/Guild.java`**: + `everControlledTerritory`.
- **`model/GuildWar.java`** (novo): guildA/guildB (FK), killsA/killsB, startedAt, endsAt, status (ACTIVE/RESOLVED),
  winnerGuildId. Helpers: `isOver()`, `otherGuildId(myId)`, `incKillFor(guildId)`.
- **`repository/GuildWarRepository.java`** (novo): `findActiveByGuild`, `findByGuildOrderByStartedAtDesc`.
- **`service/GuildWarService.java`** (novo): `declare`, `attack`, `resolve(war)`, `activeWarFor(guild)`,
  `eligibleTargets(guild)`, `resolveDueWars()` (scheduler).
- **`service/ZoneService.java`**: + `public RaidLoot applyGuildWarRaid(Player winner, Player loser)`
  (reusa os helpers privados de loot/penalty/escudo).
- **`service/TerritoryService.java`**: marca `everControlledTerritory=true` ao tomar território.
- **`controller/GuildController.java`** (ou novo `GuildWarController`): `POST /api/guild/war/declare/{guildId}`,
  `POST /api/guild/war/attack/{playerId}`, `GET /api/guild/war` (status + membros inimigos),
  `GET /api/guild/war/targets` (guilds elegíveis).
- **`service/GuildWarScheduler.java`** (novo): cron diário → `resolveDueWars()` + on-startup catch-up.
- **`config/SchemaMigrator.java`**: `ever_controlled_territory` em guilds (tabela `guild_wars` auto-criada).

### Frontend (`static/app.js`)
- Painel da guild: banner "⚔ At War with [inimiga] — Xd left · Kills: nós K1 × K2 eles" + lista dos
  membros inimigos com botão **Attack** (mostra escudo/KO). Botão **Declare War** (líder, escolhe guild elegível).
- Resultado do ataque: ganhou/perdeu + loot + kills atualizadas.

### Testes
- Elegibilidade (sem território → não declara); declarar cria guerra; 1 guerra por guild.
- Ataque: vencedor forte → kill pra ele + loot no perdedor + escudo; perdedor forte (atacante perde) →
  kill simétrica pro defensor + prejuízo no atacante.
- Não pode atacar escudado/KO/aliado/sem-guerra; custa estamina.
- Resolução: mais kills leva 25%; perdedor regride nível; vencedor pode subir; empate = sem transferência.

## Consequências / notas
- Guerra é a **única** forma de reduzir lifetimeGold/nível (resto é monotônico).
- Sem level-band no ataque (você escolhe o alvo no painel); o escudo + estamina limitam o farm.
- Item: prejuízo de perder 1 item na derrota, sem travar a bag por 7 dias (simplificação vs zona vermelha).
- Números (7d/25%/25 estamina/escudo 1h) são constantes fáceis de tunar.
