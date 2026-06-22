# Auditoria — Varredura Completa do Código (2026-06-22)

> Varredura noturna com 8 especialistas (read-only) + aplicação dos fixes seguros. Backend Java +
> cliente Godot. Cada achado tem `[SAFE-FIX]` (mecânico, verificável por teste/compilador) ou
> `[NEEDS-REVIEW]` (mexe em comportamento / blast radius grande — revisar antes).
>
> **Coordenação:** a outra sessão estava editando o Godot `ui/` (INV_COMPACTO, commitado em `0828c3c`).
> Por isso os **edits foram no BACKEND**; a limpeza de comentários do front foi feita por último.

## Sumário executivo

O código já passou por auditorias anteriores ([AUDITORIA]/[AUDITORIA_2]) e está bem defendido nas
classes de risco (IDOR, auth, currency, injection). **Nenhum CRÍTICO de segurança confirmado.** Os pontos
estruturais mais importantes são de **concorrência** (falta `@Version` no `Warrior`; schedulers sem lock
distribuído) e **um bug de lógica real** (HP de guerra de território persistido na base errada). O resto é
código morto, comentários desatualizados, N+1 em telas de ranking, e complexidade alta em ~6 god-services.

---

## 🔴 TOP PRIORIDADES (NEEDS-REVIEW — não aplicadas, exigem teste do dono em prod)

### 1. `Warrior` não tem `@Version` → double-spend de ponto de atributo + lost-update de XP/HP
`model/Warrior.java`. `Player` tem `@Version`, `Warrior` não. `WarriorService.spendPoint` (`:80-102`),
`addExperience` (`:40-56`), heal de HP (`GatheringService:189`) são read-modify-write sem trava. Dois
`POST /spend-point` simultâneos passam o guard e gastam 2 pontos de 1. **Risco da correção:** o `Warrior`
é a entidade mais salva do jogo; adicionar `@Version` pode expor `OptimisticLockException` latente em
fluxos que re-salvam Warrior detached (foi o que aconteceu com `Player` no `TowerService`). Aplicar +
testar em prod. Coluna via `SchemaMigrator`.

### 2. Schedulers `@Scheduled` sem lock distribuído → wars/território/leilão resolvidos 2× ao escalar
`GuildWarScheduler:19`, `TerritoryScheduler:24`, `AuctionScheduler:18`. Sem ShedLock/lock de DB, cada
instância roda o mesmo cron. Hoje é 1 instância, mas num deploy rolling (old+new sobrepostos) ou scale
horizontal, dobra o roubo de 25% de gold de guild, upkeep de território, e mails. **Fix:** ShedLock ou
UPDATE atômico guardado (`UPDATE guild_war SET status='RESOLVED' WHERE id=? AND status='ACTIVE'`, age só
se rowcount==1).

### 3. Resolução de guerra lazy-on-read corre com o scheduler (roubo de gold duplicado)
`GuildWarService.currentWar()` (`:201`) resolve lazy em todo `attack`/`statusFor` E o scheduler também —
check-then-update de status não-atômico. Sob READ_COMMITTED dois resolvem e aplicam os deltas de gold 2×.
Bônus ruim: um GET (`statusFor`) muta gold (write num read path). **Fix:** lock pessimista no fetch da war
ou UPDATE atômico guardado; separar o lazy-resolve dos endpoints de leitura.

### 4. `make()`/`addResource`/`join()`/`create()` — check-then-act fora do alcance do `@Version`
Capacidade de bag e cap de membros de guild são contados por linhas; inserir uma linha nova **não** bumpa
`Player.version`, então o `@Version` não serializa. Dois drops/crafts/joins simultâneos furam o cap.
`InventoryService:337`, `GatheringService:64`, `GuildService:85` (membros), `:42` (nome duplicado de guild).
**Fix:** `SELECT ... FOR UPDATE` na linha do player/guild antes de contar, ou índice único parcial no DB.

### 5. Sessão única (work/quest/training) por check-then-insert → duas sessões IN_PROGRESS
`WorkService:81`, `KingdomService:181` (quest), `:548` (training). `findByPlayerAndStatus(IN_PROGRESS)` +
`save(new)` — `@Version` só guarda UPDATE de 1 linha, não INSERT duplicado. Resultado: gold/XP idle dobrado
e `getCurrentSession` (Optional single-result) pode lançar `IncorrectResultSizeDataAccessException`.
**Fix:** índice único parcial no Postgres `UNIQUE (player_id) WHERE status='IN_PROGRESS'` + catch.

---

## 🟠 BUGS DE LÓGICA (alguns aplicados — ver seção "Aplicado")

- **[HIGH] HP de guerra de território na base errada** — `TerritoryService.persistHpChanges:609-610` divide
  `f.hp` (calculado contra o **max de combate** `cs[2]` = base+gear+buff) pela base `warrior.getHealth()`.
  Pra qualquer guerreiro com gear, `pct` estoura e clampa em ~100% → dano de guerra de território **não
  persiste**; e `getHealth()==0` dá divisão por zero. O irmão correto é `GuildWarService.persistHp:257`
  (usa `a[2]` + guard `>0`). **→ A CORRIGIR (precisa carregar `cs[2]` no `Fighter`).**
- **[MEDIUM] `refineOre`/`craftGem` perdem material+bronze se a bag não cabe a saída** —
  `SmithingService:191,281`: consome e gasta ANTES do `addResource` (que descarta o excedente em silêncio).
  `craftEquipment` já trata bag-cheia com mail; estes não. **Fix:** checar `resourceSpaceLeft` antes, ou mail.
- **[MEDIUM] Torre não persiste HP no WIN** — `TowerService:272-307` só salva HP na derrota; todo outro
  combate persiste `out.firstHpFinal()`. Torre vira "HP grátis" entre andares. Pode ser intencional —
  documentar ou corrigir.
- **[MEDIUM] Boss COMBAT paga 2×** — `ZoneService:468-479`: vitória de chefe na Fortaleza dá bônus XP/bronze
  do chefe **+** a recompensa por-kill da caça (assimétrico vs flee/gathering). Decidir semântica.
- **[MEDIUM] `cancelWork` trunca pra horas inteiras** — `WorkService:184`: `Duration.toHours()` trunca →
  cancelar antes de 1h paga 0; progresso parcial perdido. **Fix:** proratear por minutos.
- **[LOW][SAFE] Gauntlet tiebreak favorece atacante** — `GauntletWarSimulator:184` `hpA >= hpB` dá empate
  exato ao lado A (sempre o atacante), contra a regra "empate → defensor". **→ A CORRIGIR (`>`).**
- **[LOW][SAFE] VIP reset usa zona default, msg diz "UTC"** — `VipService:114,117,127` `LocalDate.now()`.
  **→ A CORRIGIR (`LocalDate.now(ZoneOffset.UTC)`).**
- **[LOW] `startTraining`/`startQuest` sem alguns guards do irmão** — training sem `assertNotBusy`; quest
  sem `isKnockedOut()`. Divergência de irmãos; decidir se é intencional.
- **[LOW] `guildBrawl` (modelo 3×5 da doc) está MORTO** — `TerritoryService:396`; o vivo é `guildGauntlet`
  (15v15). A doc [GUERRA_FORMACAO] descreve o modelo que não roda. Remover ou documentar.
- **[LOW] DailyReward `status()` mostra streak inconsistente** — `DailyRewardService:82` mistura streak
  guardado com `claimDay` derivado do pending. Só display.

---

## 🟡 PERFORMANCE / ESCALABILIDADE (vários aplicáveis)

- **[HIGH][SAFE] N+1 no `getInventory`** — `InventoryService:58` faz `findAllByItem(i)` por item; o batch
  `findAllByItemIn` já existe e está sem uso. Método ainda é `@Transactional` read-write com `saveAll`
  self-heal em toda abertura da mochila. **Fix:** batch + gate do self-heal + `readOnly`.
- **[HIGH][SAFE] N+1 no ranking da Torre** — `TowerController:68` faz `findByPlayer(p)` por linha (Arena já
  usa `findByPlayerIn`). **Fix:** batch (método existe).
- **[HIGH][SAFE] Falta índice na matchmaking PvP-flag** — `findFlaggedInZone` escaneia `players` inteira em
  todo collect em zona flagada. **Fix:** `CREATE INDEX idx_players_pvp_flag ON players(pvp_flagged_zone,
  pvp_flagged_until)`.
- **[MEDIUM][SAFE] Falta índice `players.guild_id`** — roster/contagem/join escaneiam.
- **[HIGH] Cooldown anti-spam da Taverna in-memory** — `TavernService:44` `ConcurrentHashMap` sem prune
  (leak) e por-JVM (furável multi-instância). **Fix:** cache TTL ou via DB.
- **[MEDIUM] N+1 em `eligibleTargets`/`buildFormation`** (GuildWar/Territory) + `findAll()` sem paginação.
- **[MEDIUM] Auction browse hidrata `item`+`seller` lazy por linha** — falta `@EntityGraph`.
- **[LOW][SAFE] `@Transactional(readOnly=true)`** faltando em vários reads.

---

## ⚪ CÓDIGO MORTO (maioria aplicada — ver "Aplicado")

- `WarriorClass.damageAttribute()`/`isRanged()`/`weaponCategory()` — @Deprecated/0 refs.
- Bloco inerte de trava de arma por classe em `InventoryService.equip()` (`canEquip()` sempre true).
- 6 métodos de repositório sem caller; 9 métodos de service públicos sem caller (2 do BlueMerchant são
  scaffold Steam — manter).
- Import sem uso `GatheringService:8`; `ItemBonus.NONE`; i18n `error.stash_full`.
- Arquivos lixo: `*.java.tmp.*` na árvore de fonte, `*.stackdump` na raiz.
- Comentários desatualizados: `AC = 10 + DEX` (combate mudou), trava de arma "Archer só arco" (removida),
  "zera timers" no `application-dev.properties` (não há timers), slot 4 do array de stats `strBonus`→`agi`.
- Duplicação: strip do `WINNER:` em 8 lugares (divergiu); `itemName` em 2 (divergiu — crossbow); tabela de
  preço de drop `switch(rarity)` duplicada; bloco "roll → make-or-mail"; `npcStatsByLevel` em 2.
- DB enums write-dead (precisam migração p/ remover): `QuestStatus.READY_TO_COLLECT`, `MatchStatus.FINISHED`,
  `Location.COMMERCE/ARENA`, `ExpeditionSource.KINGDOM`.

---

## 🟣 NOMENCLATURA (renomes — maioria NEEDS-REVIEW por blast radius)

- **`int[]` de stats `[atk,def,hp,dex,agi,luk]` acessado por índice literal** em todos os services de combate
  (`s[2]`, `cs[4]`) longe de onde é montado — maior carga cognitiva. Candidato a `record CombatStats`.
- **`gold`/`getGold`/`setGold`/`addGold` que na verdade é BRONZE** — `Guild.gold`, `PlayerService.addGold`
  (o param até se chama `bronzeAmount`). Renomear p/ `treasuryBronze`/`addBronze`.
- `monsterChance` é percent (não chance); `Side.pinned` é int 0/1/2 com nome de boolean; single-letters
  `fr`/`lg`/`cs`/`m`/`o`/`out` em métodos longos; `red` = tier por cor de UI.

---

## 🔵 COMPLEXIDADE (top — todos NEEDS-REVIEW, refator com teste)

| Método | Arquivo:linha | ~CC | ~linhas |
|---|---|---|---|
| `TerritoryService.resolveTerritory` | `:196` | ~28 | ~158 |
| `ExpeditionService.choose` | `:182` | ~22 | ~88 |
| `BattleSimulator.attack` | `:443` | ~22 | ~94 |
| `ZoneService.collect` | `:163` | ~20 | ~76 |
| `KingdomService.resolveAndReward` | `:253` | ~19 | ~72 |

**God-classes:** `ExpeditionService` (1174 linhas), `ZoneService` (903). O **subsistema de raid PvP está
duplicado** entre os dois (near-twins) — maior dedup de valor. `BattleSimulator.attack` e
`GauntletWarSimulator.strike` são re-implementações do mesmo combate (já divergiram: war não tem kiting/
Fortune Save). **Recomendação:** extrair `PvpRaidService` compartilhado e `CombatMath` compartilhado.

---

## 🏛️ ARQUITETURA / PASTAS (NEEDS-REVIEW — moves são conflito-prone, adiar)

- **Layer-skip:** `AuthController`/`AdminController` salvam via repo direto (deveria ser service).
- **`service/` é um god-package de 48 arquivos** misturando beans com helpers stateless (BattleSimulator,
  TowerFloors, narrators, simulators) + schedulers + `service/steam/`. Proposta de subpacotes por domínio
  (combat/character/world/economy/guild/social/...) no fim do arquivo do agente (adiar o move).
- **`WorkService.assertNotBusy` static-com-repo** evita um ciclo **não-estrutural** — extrair um `WorkGuard`
  @Service resolve (7 services carregam `WorkSessionRepository` só de conduíte). **[SAFE-FIX] de baixo risco.**
- DTOs como records aninhados espalhados por controller; sem pacote `dto/`.
- Frontend web LEGADO ainda servido em `static/` (~370 KB: app.js/index.html/battleArena.js/ragdoll-proto).
- `docs/` com 77 arquivos `PLANO_*` num monte só.
- Godot `ui/` com 34 scripts flat (UiKit 70KB, Character 57KB); root com BattleReplay 150KB.

---

## ✅ APLICADO nesta varredura (commits desta noite)

_(preenchido conforme aplico — ver git log com tag [VARREDURA])_

- Limpeza de lixo (`*.java.tmp.*`, `*.stackdump`) + `.gitignore`.
- Código morto removido (WarriorClass deprecated, métodos de repo/service sem caller, import, i18n morto).
- Comentários desatualizados corrigidos (AC, trava de arma, timers, slot agi).
- Bugs corrigidos: HP de território (basis), VIP UTC, gauntlet tiebreak.
- Frontend: comentários que vazam lógica/autoria removidos/neutralizados (ver seção front no fim).
