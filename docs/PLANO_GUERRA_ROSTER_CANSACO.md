# Plano — Guerra de Território: Roster de 15 + Cansaço (rotação forçada)

> Status: **implementado** (2026-06-05). Fonte da verdade da feature.
> Idioma: doc em PT; código e strings de UI em EN (traduz pro PT depois via i18n).

## Objetivo

Hoje **todos** os membros não-nocauteados de uma guild lutam automaticamente na Guerra de
Território, sem cap. Queremos:

1. **Máximo de 15 lutadores por batalha** (por guild, por ciclo).
2. **Debuff de Cansaço** em quem lutou: **−10% nos stats por ciclo consecutivo lutado** (teto **−50%**),
   só na Guerra de Território. Descansar 1 ciclo zera.
3. O **líder seleciona os 15** na tela de guild. Se não montar (ou montar <15), o sistema
   **auto-preenche até 15** (preferindo não-cansados e mais fortes).

Resultado: guilds grandes precisam **rotacionar** o time a cada ciclo pra não lutar com −10/−20/…%;
guilds pequenas (≤15) lutam sempre com o mesmo time e acumulam cansaço (desvantagem intencional —
profundidade de roster importa).

## Decisões travadas (alinhadas com o dono)

| Pergunta | Decisão |
|---|---|
| Escopo do cansaço | **Só Guerra de Território** (arena/zonas/torre intactos) |
| Duração / stack | **Acumula −10%/ciclo consecutivo, teto −50%**; 1 ciclo de descanso zera |
| Sem roster (ou <15) | **Auto-preenche até 15** (prefere não-cansado, depois mais forte) |

---

## Contexto do código (como funciona hoje)

- **Ciclos de 6h**: `TerritoryService.currentCycleId()` = `epoch/21600`. Declaração mira
  `currentCycleId()+1`. Cron (`TerritoryScheduler`, `0 0 0,6,12,18`) resolve no boundary via
  `resolveDueCyclesForTerritory(territory, current)` → `resolveTerritory(territory, cycleId)`.
- **Quem luta**: `TerritoryService.buildFighters(Guild, debuffPercent)` lê `findAllByGuild(guild)`,
  monta um `Fighter` por membro com warrior e `hp>0`. **Sem cap. Stats base** (sem equip/buffs).
  Já aplica o debuff de defensor (`×(1 - debuff/100)` em atk/def/dex) — é o mesmo gancho do cansaço.
- **Fluxo de batalha**: Phase 1 (cada atacante × defensor original) → Phase 2 (desempate entre
  vencedores). `buildFighters` é chamado várias vezes por ciclo (phase1 + phase2).
- **Guild**: sem entidade `GuildMember`; membro = `Player.guild`. Só papel **LEADER**
  (`Guild.leaderId`). Frontend: `renderGuildPanel()` (app.js ~1763) lista membros.

---

## Modelo do Cansaço (cycle-based, robusto a catch-up)

Estado **por warrior** (não por player — combate é warrior-cêntrico):

- `int warFatigueStacks` (0–5) — quantos ciclos consecutivos lutou.
- `long warLastCycleFought` — id do último ciclo em que foi escalado.

### Ler (aplicar na batalha do ciclo `C`)
```
int incoming = (warLastCycleFought == C - 1) ? warFatigueStacks : 0;  // gap ≥1 ciclo → fresco
int fatiguePct = min(50, incoming * 10);
// aplica × (1 - fatiguePct/100) em atk/def/dex (multiplicativo com o debuff de defensor)
```
> O check `== C-1` faz o **reset automático**: se houve qualquer ciclo sem lutar, `incoming=0`.
> Não precisa de job de decay.

### Escrever (1× ao fim de `resolveTerritory(territory, C)`, só pros escalados)
```
int incoming = (warLastCycleFought == C - 1) ? warFatigueStacks : 0;
warFatigueStacks   = min(5, incoming + 1);
warLastCycleFought = C;
```
Importante: **lê** o cansaço em todas as lutas do ciclo C (phase1+phase2) usando o estado
*pré-ciclo*; só **escreve** (incrementa) no finalzinho, então tudo no ciclo C usa o mesmo valor e o
incremento só afeta C+1.

### Exibir (frontend) — cansaço que valerá na PRÓXIMA batalha
A próxima batalha resolve `currentCycleId()+1`; logo o membro entra cansado se lutou o ciclo atual:
```
displayFatiguePct = (warLastCycleFought == currentCycleId()) ? min(50, warFatigueStacks*10) : 0;
```

---

## Roster (seleção dos 15)

Flag **por player**: `boolean inWarRoster` (default false) — escolha explícita do líder.

### Seleção em `buildFighters(guild, defenderDebuff, cycleId)`
1. Candidatos = membros com warrior e `hp>0`.
2. **Picks explícitos** (`inWarRoster=true`) entram primeiro; se >15, corta por poder (atk+def+hp) desc.
3. Se faltam vagas pra 15, **auto-preenche** com os demais, ordenando por `(fatiguePct asc, poder desc)`.
4. Corta em **15**. Monta `Fighter` aplicando `defenderDebuff` (se defensor) **e** o cansaço por-warrior.

> Auto-preenchimento prefere **não-cansado** → mesmo sem roster montado, o time **se auto-rotaciona**.
> O roster do líder só "fixa" quem é obrigatório. Membros auto-escalados lutam e ficam cansados, mas
> o flag `inWarRoster` deles continua false (rotação natural no ciclo seguinte).

### Aplicar cansaço aos escalados
Durante a Phase 1, coletar o **conjunto de `playerId` escalados** (atacantes de cada declaração +
defensor `currentHolder`; ignora NPC `playerId=null`). Ao fim de `resolveTerritory`, incrementar o
cansaço desse conjunto (1× no ciclo).

---

## Mudanças por arquivo

### Backend
- **`model/Warrior.java`**: + `warFatigueStacks` (`columnDefinition="integer default 0"`),
  `warLastCycleFought` (`columnDefinition="bigint default 0"`); helpers
  `fatiguePctForCycle(long c)` (read) e `currentFatiguePct(long currentCycleId)` (display).
- **`model/Player.java`**: + `boolean inWarRoster` (`columnDefinition="boolean default false"`).
  Em `GuildService.join/leave/kick`: zera `inWarRoster` ao sair/entrar de guild (limpeza).
- **`service/TerritoryService.java`**:
  - `buildFighters(guild, debuff, cycleId)` — nova assinatura; seleção+cap 15+cansaço (acima).
  - `resolveTerritory(territory, cycleId)` — passar `cycleId` aos `buildFighters`; coletar escalados;
    `applyFatigue(fieldedPlayerIds, cycleId)` no fim. Logs (`[TerritoryService] ... fatigue`).
  - Constantes: `ROSTER_MAX=15`, `FATIGUE_PER_CYCLE=10`, `FATIGUE_CAP=50`.
- **`service/GuildService.java`**: `setWarRoster(leader, List<Long> memberIds)` — leader-only,
  valida que todos são membros, cap 15 → senão `IllegalState`; seta `inWarRoster` (true nos escolhidos,
  false no resto). Logs.
- **`controller/GuildController.java`**:
  - `POST /api/guild/roster` body `{ memberIds:[...] }` → `setWarRoster`.
  - Resposta de `GET /api/guild`: cada membro ganha `inWarRoster` (bool) e `fatiguePct` (display).
- **`repository/PlayerRepository.java`**: reusa `findAllByGuild`. (sem novo repo)

### Frontend (`static/app.js`)
- `renderGuildPanel()`: por membro, badge de cansaço (`😓 −X%`) e, **se líder**, um checkbox de roster
  (desabilita os não-marcados quando 15 já marcados) + contador `War roster: X/15` + botão
  **Save battle roster** → `POST /api/guild/roster`. Strings em EN.
- Nova função `guildSaveRoster()` (coleta ids marcados, posta, recarrega painel).

### Migração
Colunas novas com `DEFAULT` (boolean false / int 0 / bigint 0) → seguras com `ddl-auto=update` (prod).
Se existir `SchemaMigrator`, adicionar `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ... DEFAULT ...` idempotente.

---

## Testes (a adicionar)
- **Cap 15**: guild com 20 membros → no máx. 15 `Fighter`s.
- **Cansaço acumula/reseta**: lutar ciclos C, C+1 consecutivos → −10% no C+1; pular um ciclo → fresco.
- **Teto −50%**: 6+ ciclos seguidos não passa de −50%.
- **Auto-preenchimento**: sem roster → escala até 15 preferindo não-cansados.
- **Roster**: `POST /api/guild/roster` — leader-only (não-líder → 4xx), cap 15 (>15 → 4xx),
  seta flags corretamente; `GET /api/guild` reflete `inWarRoster`/`fatiguePct`.
- Ajustar testes existentes de território se assumirem "todos lutam".

## Consequências / notas
- Guild **≤15** que guerreia todo ciclo: sem ninguém pra descansar → sobe até −50% (intencional).
- Defensor também respeita cap 15 + cansaço (simétrico). NPC (território neutro) escala `attackers.size()` ≤15.
- Cansaço é **multiplicativo** com o debuff de defensor (fontes independentes).
