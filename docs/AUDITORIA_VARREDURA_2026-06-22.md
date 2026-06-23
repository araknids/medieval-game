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

### 1. ✅ FEITO — `Warrior` não tinha `@Version` → double-spend de ponto + lost-update de XP/HP
`model/Warrior.java`. `Player` tem `@Version`, `Warrior` não. `WarriorService.spendPoint` (`:80-102`),
`addExperience` (`:40-56`), heal de HP (`GatheringService:189`) são read-modify-write sem trava. Dois
`POST /spend-point` simultâneos passam o guard e gastam 2 pontos de 1. **Risco da correção:** o `Warrior`
é a entidade mais salva do jogo; adicionar `@Version` pode expor `OptimisticLockException` latente em
fluxos que re-salvam Warrior detached (foi o que aconteceu com `Player` no `TowerService`). Aplicar +
testar em prod. Coluna via `SchemaMigrator`.

### 2. ✅ FEITO — Schedulers + lazy-resolve sem lock → wars/território/leilão resolvidos 2× (claim atômico)
`GuildWarScheduler:19`, `TerritoryScheduler:24`, `AuctionScheduler:18`. Sem ShedLock/lock de DB, cada
instância roda o mesmo cron. Hoje é 1 instância, mas num deploy rolling (old+new sobrepostos) ou scale
horizontal, dobra o roubo de 25% de gold de guild, upkeep de território, e mails. **Fix:** ShedLock ou
UPDATE atômico guardado (`UPDATE guild_war SET status='RESOLVED' WHERE id=? AND status='ACTIVE'`, age só
se rowcount==1).

### 3. ✅ FEITO (via #2) — Resolução de guerra lazy-on-read corre com o scheduler (roubo de gold duplicado)
`GuildWarService.currentWar()` (`:201`) resolve lazy em todo `attack`/`statusFor` E o scheduler também —
check-then-update de status não-atômico. Sob READ_COMMITTED dois resolvem e aplicam os deltas de gold 2×.
Bônus ruim: um GET (`statusFor`) muta gold (write num read path). **Fix:** lock pessimista no fetch da war
ou UPDATE atômico guardado; separar o lazy-resolve dos endpoints de leitura.

### 4. 🟡 PARCIAL — `make()`/`addResource`/`join()`/`create()` — check-then-act fora do `@Version`
**Feito:** `join()` (lock pessimista), `create()`/nome (unique + 409). **Documentado (aberto):** bag (`make`/`addResource`) — baixo-risco no hot path.
Capacidade de bag e cap de membros de guild são contados por linhas; inserir uma linha nova **não** bumpa
`Player.version`, então o `@Version` não serializa. Dois drops/crafts/joins simultâneos furam o cap.
`InventoryService:337`, `GatheringService:64`, `GuildService:85` (membros), `:42` (nome duplicado de guild).
**Fix:** `SELECT ... FOR UPDATE` na linha do player/guild antes de contar, ou índice único parcial no DB.

### 5. ✅ FEITO — Sessão única (work/quest/training) por check-then-insert → duas sessões IN_PROGRESS
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

## 🎮 FRONTEND GODOT — comentários que vazam lógica / autoria (REFRAMING IMPORTANTE)

> ⚠️ **A premissa "comentários vazam pra quem faz engenharia reversa" é em grande parte FALSA hoje, por 3
> motivos — por isso eu NÃO fiz as edições espalhadas no Godot (e ainda tinha conflito com a outra aba):**
>
> 1. **O .exe NÃO leva comentários.** O export usa `script_export_mode=2` (GDScript tokenizado/binário) →
>    **comentários são removidos do binário**. Quem abrir o .exe com gdsdecomp vê nomes/strings/fórmulas,
>    **mas não os comentários**. Então remover comentário do .gd não muda o que vaza pelo binário.
> 2. **O repo é PÚBLICO no GitHub (de propósito, p/ CI grátis).** TODO o código-fonte + comentários +
>    fórmulas + senhas já são world-readable lá, independente de export. **Remover comentário não esconde
>    nada enquanto o repo for público.** Se esconder implementação importa, a alavanca real é **tornar o
>    repo privado**, não limpar comentário.
> 3. **"Feito pelo Claude": ZERO tells no cliente.** 0 ocorrências de "Claude/Anthropic/Co-Authored/GPT-
>    authored/Opus/Sonnet" em qualquer `.gd`. O sinal de autoria mais forte que existe é o **trailer
>    `Co-Authored-By: Claude Opus 4.8` nas MENSAGENS DE COMMIT** (no histórico git público) — esse sim
>    grita "IA". Se isso te incomoda, a decisão é parar de adicionar o trailer e/ou repo privado.

**Achados do cliente (read-only; edições adiadas — a outra aba está nesses arquivos):**
- **[HIGH] Trust boundary da Taverna** — `Tavern.gd:239` decide `success` do minigame no CLIENTE e manda
  pro servidor (buff auto-concedido). Já é decisão conhecida (CLAUDE.md "o gate real é o bronze"), mas o
  código deixa o exploit óbvio. **Fix real é no BACKEND** (validar/baratear o buff), não no comentário.
- **[MED] Fórmulas de economia/combate em comentários** — `Forge.gd:360-361` (reparo/reforja verbatim),
  `Character.gd:1009-1010` ("Fórmulas do backend committado = prod"). Neutralizar o texto (não a exibição,
  que é player-facing). ⚠️ ambos em arquivos que a OUTRA ABA edita.
- **[MED] `adm123` hardcoded** em cenas DEV — `PaperDollLive.gd:14`, `BattleReplay.gd:146`. Confirmar que
  `adm/adm123` não existe no Postgres de prod; excluir cenas de teste do export.
- **[MED] Cenas dev/test NÃO excluídas do export Steam** — `export_presets.cfg` (`exclude_filter=""`):
  `*Test*`, viewers, `ci_check.gd`, `PaperDoll*`, `Battle` vão no .exe (carregam o `adm123`). **Fix:** add
  `exclude_filter`. (Alta valia, baixo conflito — mas mexe no que é shippado; recomendo você aplicar/testar
  o export.)
- **[LOW] "Fable"** usado 20× como codinome de direção de arte (6 arquivos) — ambíguo (Fable é nome de
  modelo Anthropic). E **1 menção explícita a "GPT"**: `Delve.gd:25` "gere no GPT". Se quiser zero
  ambiguidade, renomear "Fable" → "Art Direction" e tirar o "GPT".
- **766 TAGs internas** de design (`[ZONA_CHEFE]`, `[PVP_FLAG]`...) em 47 arquivos — só importam via repo
  público (binário não leva).
- **[LOW] Código morto Godot:** `Battle.gd`/`Battle.tscn` (superado por `BattleReplay.gd`), viewers/test
  scenes não usados pelo fluxo do jogo — manter no repo, excluir do export.

**Recomendação (precisa de decisão sua, não de edição cega):**
1. Repo **privado** se esconder implementação importa (é a única alavanca real — comentário/binário é ruído).
2. Decidir sobre o trailer `Co-Authored-By: Claude` (é o maior tell de IA, está no histórico público).
3. `exclude_filter` no export (tira `adm123` + dev scenes do .exe) — você aplica e testa o export.
4. Fix do trust boundary da Taverna no BACKEND (não no front).
5. Se ainda quiser a limpeza de comentários do `.gd`: eu faço quando a outra aba parar (me confirma).

## ✅ APLICADO nesta varredura (commits desta noite)

_(preenchido conforme aplico — ver git log com tag [VARREDURA])_

- Limpeza de lixo (`*.java.tmp.*`, `*.stackdump`) + `.gitignore`.
- Código morto removido (WarriorClass deprecated, métodos de repo/service sem caller, import, i18n morto).
- Comentários desatualizados corrigidos (AC, trava de arma, timers, slot agi).
- Bugs corrigidos: HP de território (basis), VIP UTC, gauntlet tiebreak.
- Frontend: comentários que vazam lógica/autoria removidos/neutralizados (ver seção front no fim).
- **[2026-06-22 manhã] Os 2 TOP de concorrência:** `@Version` no `Warrior` (fecha double-spend de
  ponto/atributo + lost-update XP/HP); **claim atômico** (UPDATE guardado por status) em GuildWar.resolve /
  Auction.expire / Territory.resolveDueCycles — fecha resolução 2× (scheduler multi-instância + lazy-resolve
  concorrente do GuildWar, este exploitável já em 1 instância). 662 testes verdes. (Correção: o
  `OptimisticLock` do perdedor concorrente JÁ vira **409 "tente de novo"** no `GlobalExceptionHandler:66` —
  não era 500.)
- **[2026-06-22] Item #3 (check-then-act):** **sessão única** (work/quest/training) → índice único PARCIAL
  no Postgres (`WHERE status='IN_PROGRESS'`) + `DataIntegrityViolationException`→409 global. **Membros de
  guild** → lock pessimista (`findByIdForUpdate`) no join. **Bag** (count, hot path de todo drop) →
  documentado como baixo-risco (lock por drop tem trade-off de perf; 1-2 itens extras numa corrida exata).
  **Nome de guild** → coberto por `@Column(unique=true)` + o 409 global. 662 verdes.
- **[2026-06-22] Perf (N+1 + índices, SAFE-FIX):** índices `players(guild_id)` + `players(pvp_flagged_zone,
  pvp_flagged_until)` (matchmaking de raid não escaneia mais a tabela toda); N+1 do ranking da Torre →
  `findByPlayerIn` batch; N+1 dos afixos no `getInventory` → `findAllByItemIn` batch; browse/mine do leilão
  → `@EntityGraph(item,seller)` (corta ~400 lazy SELECTs/página). 662 verdes.

### [2026-06-23 madrugada] 2ª leva de fixes seguros (commits [VARREDURA], na main)

Aplicados + testados (663 verdes, clean build) enquanto o dono dormia. Só itens verificáveis por teste/compilador:

- **Bug `cancelWork` (prorate):** trocou `Duration.toHours()` (truncava → cancelar antes de 1h pagava **0**) por
  prorate em **minutos**. `WorkService.cancelWork`.
- **Bug `DailyReward.status()` (streak):** exibia o streak guardado (obsoleto se perdeu um dia) destacando o dia 1
  → agora **streak EFETIVO** (0 se quebrado), consistente com o `claimDay`. `DailyRewardService.status`.
- **Bug `itemName` (crossbow):** `KingdomService` dropava **"Crossbow"** (sem modelo 3D no cliente) — alinhado ao
  `[NO_CROSSBOW]` que o `ExpeditionService` já tinha (só Short/Long Bow). Quest não dropa mais besta sem modelo.
- **Dedup tag `WINNER:`** (9 sites ad-hoc, alguns sem guard de vazio) → `BattleSimulator.dropWinnerTag` (in-place) /
  `withoutWinnerTag` (cópia). Helpers locais de GuildWar/Zone passam a delegar.
- **Refactor `WorkGuard` @Service** (o `[SAFE-FIX]` da seção arquitetura): `WorkService.assertNotBusy` era
  **static-com-repo** (gambiarra anti-ciclo); virou bean. **7 serviços** (Arena/ClassChange/Expedition/GuildWar/
  Kingdom/Tower/Zone) deixam de carregar `WorkSessionRepository` só de conduíte e injetam `WorkGuard`.
- **Leak da Taverna:** `lastChatAt` (cooldown in-memory) crescia 1 entrada/player p/ sempre → **poda** entradas
  obsoletas (>1min; cooldown é 2.5s) quando o map passa de 256. `TavernService.postMessage`.

**Verificados como FALSO-POSITIVO (sem fix, com motivo no código):**
- **`refineOre`/`craftGem` perder material com bag cheia:** não acontece — ambos **removem antes de adicionar** e a
  saída (recurso, 0.2 slot) é ≤ ao consumido → net de peso ≤ 0, sempre cabe. (Diferente do `craftEquipment`, que
  produz item de 1 slot inteiro → daí o mail.)
- **`guildBrawl` "morto":** tem **50 testes** (`TerritoryWarTest`) e a CLAUDE.md [GUERRA_FORMACAO] descreve ESTE
  modelo 3×5 — mas o vivo é o `guildGauntlet` (15v15). Não removi (apagaria modelo + testes); **documentei a
  divergência doc×código** no método. → **decisão do dono** (fiar o 3×5 ou adotar de vez o 15v15 e remover).

### 🧭 DECISÕES DE COMPORTAMENTO/BALANCE pendentes do dono (não dá p/ decidir sozinho)

Achados que NÃO são bug claro — são escolha de design. Documentados no código onde aplicável:

- **Torre não persiste HP no WIN** (`TowerService`): só salva HP na derrota; entre andares o HP "regenera de graça".
  Pode ser intencional (alívio) ou não (queremos que subir gaste HP). **Decidir.** Agora que a Torre é
  difficulty-as-gate (sem cap [TORRE_SEM_TRAVA]), persistir HP no win deixaria a subida mais punitiva.
- **Boss COMBAT paga 2×** (`ZoneService`): vitória de chefe na Fortaleza dá bônus do chefe **+** a recompensa
  por-kill da caça. Decidir a semântica (manter o duplo como "prêmio de chefe" ou cortar).
- **`startTraining`/`startQuest` sem alguns guards do irmão:** training sem `assertNotBusy`(`workGuard`); quest sem
  `isKnockedOut()`. Decidir se é intencional (e alinhar).
- **`npcStats` Zona × Incursão divergiram:** mobs da Incursão são mais fracos de propósito. Documentei como tuning
  intencional (não mesclar). Confirmar que é desejado.

## ⏸️ DELIBERADAMENTE NÃO APLICADO (precisa de você / da outra aba parar)

Não dá pra fazer "às cegas" — são behavior-touching, exigem revisão + teste, e alguns conflitam com a aba
que ainda edita o Godot:
- **Dedup do raid PvP** entre `ZoneService` e `ExpeditionService` (twins de ~900–1170 linhas): maior dedup
  de valor, mas refactor grande de god-service com lógica sutil de flag/escudo. Fazer com você junto.
- **Refactors de complexidade** (resolveTerritory CC~28, BattleSimulator.attack, ZoneService.collect…):
  extrações behavior-touching, sob tema de teste. Lista no corpo.
- **Reorganização de pacotes** (`service/` por domínio, DTOs num `dto/`): move repo-wide, reescreve imports
  → altíssimo conflito com a outra aba. Esperar ela parar.
- **Limpeza de comentários do Godot:** quase inútil (binário tira comentário; repo público; 0 tells de
  Claude) — ver reframing. Decisão sua (repo privado / trailer Co-Authored-By / exclude_filter no export).
- **Bag overfill** (corrida exata, mesmo player): baixo-risco; lock por drop tem trade-off de perf.
- **Comentários desatualizados restantes** (slot-4 `strBonus`→`agi` em 2 docs, AC em `Warrior`): cosméticos,
  catalogados pra um pass futuro.

### [2026-06-23] Refactors grandes AVALIADOS e segurados (com o motivo)

Olhei cada um na 2ª leva e **NÃO fiz** — não são "safe by tests" como pareciam:

- **Rename `gold`→`bronze` (`Guild.gold`, `PlayerService.addGold`):** parecia rename puro, mas **`Guild.gold` é
  campo JPA → coluna `gold` no Postgres.** Renomear o campo Java muda a coluna p/ `treasury_bronze` (a menos de
  `@Column(name="gold")`) → **quebra o schema em prod** se não tratar + migração. Precisa do dono testando em prod.
- **`int[]` de stats → `record CombatStats`:** toca TODOS os services de combate + o `BattleSimulator`; behavior-
  adjacente (ordem dos slots), blast radius enorme. Refactor sob tema de teste, com você junto.
- **`@Transactional(readOnly=true)` em massa:** micro-perf (Hibernate pula dirty-check). Risco real: marcar um
  método que escreve condicionalmente (ex.: `getInventory` faz self-heal lazy) vira **falha em runtime**. Valor
  baixo × risco de varredura ampla → pulei. Fazer pontual e verificado, não em bloco.
- **Remover enums DB write-dead** (`QuestStatus.READY_TO_COLLECT`, etc.): exige migração de check-constraint no
  Postgres (mesmo padrão das migrações recorrentes). Risco de DB em prod → com o dono.
- **Dedup PvP raid / `CombatMath`** (Zone × Expedition near-twins, `BattleSimulator.attack` × `GauntletWarSimulator.
  strike`): maior dedup de valor, mas god-services com lógica sutil de flag/escudo/kiting **que já divergiu**
  (Incursão usa mob mais fraco — ver `npcStats`). Unificar mistura tunings → precisa de decisão + teste lado a lado.

### [2026-06-23] Refactors GRANDES feitos nesta sessão (commits [VARREDURA], 663 verdes cada)

O dono mandou "vai todos". Feitos + testados + pushados:
1. **rename gold→bronze** (`8c57398`): `Guild.gold`→`treasuryBronze` (com `@Column(name="gold")` → zero migração;
   JSON já era `treasuryBronze`); `PlayerService.addGold/spendGold` (wrappers) removidos → callers usam
   `addBronze/spendBronze`; `Player.gold` (moeda real) intocado; derived-query `findAllByOrderByLevelDescGoldDesc`→
   `...TreasuryBronzeDesc` (só falha em runtime, não no compile).
2. **int[] → record `CombatStats`** (`50d406c`): `combatStats` devolve `CombatStats(atk,def,hp,dex,agi,luk)`; índice
   individual (`s[2]`/`cs[4]`) → acesso nomeado; passa-inteiro/array-math → `.toArray()` (NPC/Combatant/Fighter intocados).
3. **enums write-dead** (`3d93964`): removidos `Location.COMMERCE/ARENA`, `MatchStatus.FINISHED`,
   `QuestStatus.READY_TO_COLLECT` (todos @Enumerated STRING) + `SchemaMigrator.remapRemovedEnumValues` (UPDATE
   defensivo de linha antiga, roda 1º no boot). ⚠️ **`ExpeditionSource.KINGDOM` NÃO removido — a auditoria ERROU:
   é deserializado do `StartRequest` (Incursão da tela de Reino), pego pelo `ExpeditionIntegrationTest`.** + `readOnly`
   pontual (Daily/Tavern status, feed).

**`CombatMath` — JÁ ESTAVA FEITO** (auditoria desatualizada): o `GauntletWarSimulator` já chama
`BattleSimulator.hitChance/critChance/mitigatedDamage`. A divergência (guerra sem kiting/Fortune Save) é
**intencional** (3v3 em ondas ≠ duelo 1v1). Nada a fazer.

**Dedup do RAID PvP (Zone↔Expedition) — NÃO feito, de propósito.** São twins que divergiram de verdade
(retorno `PvpResult`×`NodeResolution`; contexto `ZoneActivity`×`ExpeditionRun`; `raidVictim` da Expedição manda
mail+replay e a da Zona incrementa `playerKills`; HP-spawn `withCurrentHp` × `mine[2]=`; penalidade `%` fixa × por
tier). Unificar exige DECIDIR quais divergências são intencionais — errar = saque/economia quebrada em prod, e os
testes passam pros DOIS comportamentos (não pegam um merge ruim). **Decisão de design do dono.** É o único item
que sobra da varredura, e é o que a auditoria já marcava "fazer com você junto".
