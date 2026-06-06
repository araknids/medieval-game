# PLANO — Fortaleza Maldita: 3 zonas de caçada (verde/amarela/vermelha) + elementos [FORTALEZA_ZONAS]

> Status: **IMPLEMENTADO** (2026-06-06). Alinha a COMBAT com os reinos de coleta
> ([PVP_FLAG], [ELEMENTOS], [ZONA_CHEFE], [ITEM_DROP_LEVEL]) — mas a "coleta" dela é **caçar monstros**.

## Conceito

A Fortaleza Maldita (`Kingdom.COMBAT`) hoje destoa dos outros reinos: tem só 2 zonas de **farm por
tempo** (Battlefield 🟡 / War Zone 🔴, sem verde, sem elementos) + um **"Hunt Beasts"** PvE à parte +
o Training Hall. O objetivo é deixá-la **com a mesma cara dos reinos de coleta**, mas onde a atividade
de toda zona é **caçar monstros** (não coletar recurso):

- **3 tiers** 🟢 SAFE / 🟡 PVP / 🔴 HIGH_RISK, todos de caçada.
- **Picker de elemento** (🔥💧🪨💨) por área, igual aos reinos de coleta.
- 🟡/🔴 mantêm o **PvP por flag/raid + item-lock** (já funciona hoje).
- **Caçada instantânea** (1 ação = 1 luta, ~10⚡), recompensa **por kill** (não por tempo).
- **Remove o "Hunt Beasts"** — ele vira a própria zona verde/amarela/vermelha.
- **Mantém o Training Hall** (inalterado).

## Decisões (alinhadas com o dono)

| Tema | Decisão |
|------|---------|
| Loot da caçada | **Materiais + essência + item**: bronze + XP + Monster Core/Beast Hide + essência do elemento + **chance pequena de item** em kills normais |
| Ritmo | **Instantâneo por caçada** (~10⚡, recompensa por kill); aposenta o farm por tempo (120min) |
| Tiers | 🟢 Lv.1 (só PvE) · 🟡 Lv.10 (PvP) · 🔴 Lv.20 (PvP + item-lock) — mesmos gates dos outros reinos |
| Elementos | As 4 áreas (🔥💧🪨💨) em todos os tiers, igual à coleta |
| Training Hall | Mantido como está |

## Zonas (nomes placeholder, temáticos da fortaleza)

| Tier | Nome | Lv | PvP | Monstro escala |
|------|------|----|----|----------------|
| 🟢 SAFE | Haunted Courtyard | 1+ | não | `player+0..3` |
| 🟡 PVP | Battlefield | 10+ | sim (flag/raid) | `+0..3`, 30% elite (+4..8) |
| 🔴 HIGH_RISK | War Zone | 20+ | sim (+ item-lock) | `+0..3`, 50% elite (+6..15) |

(Escalas e penalidades de derrota reusam o que já existe em `monsterLevelFor`/`defeat` — sem mudança.)

## Recompensa por caçada (vitória)

Reusa o multiplicador do tier (`Zone.multiplier`: 🟢1.0 / 🟡1.5 / 🔴2.5). Por kill:

- **Bronze** = `round(level × 10 × mult)`  ·  **XP do guerreiro** = `round(level × 12 × mult)`
  (mesma base do antigo Hunt Beasts, agora escalando por tier).
- **Materiais** (`resolveCombatHunt`): Monster Core sempre (`1 + level/25`, ×mult arredondado);
  Beast Hide com chance (~25% × peso do tier). Mantém Monster Core/Beast Hide **vivos na economia**
  (hoje só nasciam do Hunt Beasts; uso futuro na forja).
- **Essência do elemento** da área (se escolhida) — igual à coleta (`element.essence()`, qty escala com tier).
- **Chance de ITEM em kill normal** (`rollCombatItemDrop`): 🟢~3% / 🟡~6% / 🔴~10%. Item no **nível do
  monstro** (`monsterLevelFor`, [ITEM_DROP_LEVEL]), raridade ponderada baixa-média; mail se bag cheia.
  (Isto é **além** do chefe errante, que continua dando 1 item garantido de alta raridade.)
- **Chefe errante** [ZONA_CHEFE]: já rola para role≠HUNTING → a COMBAT herda automático.

Derrota em 🟡/🔴: KO + penalidade do tier (já existe). 🟢 nunca tem PvP nem perde item.

## O que muda no código

### Backend — `ZoneService`
1. **Libera a verde p/ COMBAT**: remover o check que bloqueia `role==COMBAT && zone==SAFE`
   (hoje força o Training Hall). [linhas ~102–106]
2. **`collect`**: no caminho de vitória, para `role==COMBAT` usar **`resolveCombatHunt`** (materiais +
   essência) no lugar de `resolveGathering`, e rolar **`rollCombatItemDrop`** (surface via `lootItemName`).
3. **`resolveCombatHunt(player, activity)`** (novo, paralelo a `resolveGathering`): monta os drops de
   material + essência e grava `xpGained`/`bronzeGained` por-kill.
4. **`applyDropsAndRewards`** (branch COMBAT): troca a fórmula **por tempo** (`hours×mult×level×20`)
   pela **por kill** (`level×12×mult` XP, `level×10×mult` bronze). Drops continuam aplicados genericamente.
5. **`rollCombatItemDrop(player, activity)`** (novo): chance por tier → `inventoryService.make` no nível
   do monstro (reusa `rollItemStats`/afixos), ou mail se bag cheia. Devolve o nome ou null.

### Backend — remover o Hunt Beasts
6. **Apaga `CombatPveService`** (raid PvE) + o endpoint `POST /api/world/{kingdom}/raid` e o campo
   `combatPveService` no `KingdomController`. `RaidResult`/`RaidDrop` somem junto.
7. **Testes**: remove/realinha `CovilRaidTest` (testa o raid); confere a menção em `ZoneAmbushIntegrationTest`.
   `MONSTER_CORE`/`BEAST_HIDE` continuam no `ResourceType` (agora dropam das zonas).

### Frontend (`app.js`)
8. **Remove** a seção "👹 Hunt Beasts" + a função `raidCombat()`.
9. **Substitui** o bloco de 2 zonas de COMBAT por um bloco de **3 tiers + picker de elemento**,
   espelhando o dos reinos de coleta (verbo "⚔ Hunt" no lugar de "🎣 Pescar"); botões chamam
   `enterCombatZone(tier, element)` instantâneo (~10⚡).
10. **`enterCombatZone`**: passa `element` + duração curta (instantâneo) no `/api/zones/enter` (role COMBAT).
11. Atualiza `ZONE_LABELS.COMBAT` (cosmético) e mantém o Training Hall.

### Migração / dados
- **Nenhuma** — `ZoneActivity.element/kingdom` já existem; sem coluna nova nem enum novo.

## Balanceamento / notas
- Números (bronze/XP por kill, chance de material/item por tier, custo de estamina) são **placeholders
  p/ tuning no playtest**.
- A vermelha continua o melhor risco/recompensa: monstros mais fortes + chefe mais frequente + maior
  chance de item + PvP/item-lock.
- A caçada instantânea por-kill rende **menos por ação** que o antigo farm de 120min, mas é repetível
  enquanto houver estamina — coerente com o resto do jogo ([SEM_TIMER]).

## Fora de escopo (futuro)
- Receitas de forja consumindo Monster Core/Beast Hide (dar uso aos materiais).
- Monstros/áreas temáticos da fortaleza (nomes próprios por tier/elemento).
