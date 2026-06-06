# PLANO — Encontros de zona + Chefe errante (verde/amarela/vermelha) [ZONA_CHEFE]

> Status: **IMPLEMENTADO** (2026-06-06). Estende ZoneService / [PVP_FLAG] / [ITEM_DROP_LEVEL].
> Roll do chefe ligado em dev/prod (`app.zone.boss-enabled=true`); **OFF nos testes** p/ collect
> determinístico (a fuga/luta é exercitada direto via `resolveBoss*` em `ZoneBossIntegrationTest`).

## Conceito

Os encontros de monstro nas zonas escalam por tier, e há um **chefe (MVP) raro** que "escapou da
Torre e está rondando a área". Ao topá-lo, a expedição **pausa** e o jogador escolhe **fugir**
(teste de stat da classe) ou **encarar** (luta dura, loot alto). Tiers mais altos = monstros mais
fortes + chefe mais frequente → reforça o porquê de arriscar a vermelha.

## Decisões (alinhadas com o dono)

| Tema | Decisão |
|------|---------|
| Decisão fugir/encarar | **Prompt interativo** (estado `BOSS_PENDING` + endpoint) |
| Fuga falha | **Forçado a encarar** o chefe |
| Nível dos monstros | Verde ≤ +3; Amarela +0..3 & ~30% elite (+4..8); Vermelha +0..3 & ~50% elite (+6..15) |
| Loot do chefe (vitória) | **Item garantido no nível do chefe** + alta chance raro/lendário + XP/bronze bônus |

## Monstro normal (escala por tier)

`monsterLevelFor(zone, playerLevel)`:
- 🟢 **SAFE**: `player + rng(0..3)`.
- 🟡 **PVP**: base +0..3; **30%** de virar elite `+rng(4..8)`.
- 🔴 **HIGH_RISK**: base +0..3; **50%** de elite `+rng(6..15)`.

Substitui o `attacker.getLevel() + rng(4)` do `fightNpc`. Monstro mais alto = luta mais dura (e,
combinado com o drop-no-nível-do-monstro, drops melhores quando houver — aqui via o chefe).

## Chefe errante (MVP)

- **Chance por encontro** (rolada ANTES do encontro normal, no collect): 🟢 0.5% · 🟡 ~1.5% · 🔴 ~3%.
- **Nível** = `player + rng(1..20)` (aleatório). Stats = `npcStatsByLevel(bossLevel)` × multiplicador
  de chefe (ex.: ATK/DEF ×1.5, HP ×2) + elemento da área. Nome temático ("Escaped Tower Warden…").
- **Fluxo:** collect detecta o chefe → expedição vira `BOSS_PENDING` (guarda `bossLevel`/`bossName`) e
  devolve o prompt (nome, nível, % de fuga estimada). NÃO aplica coleta/encontro ainda. O jogador chama:
  - **Fugir** (`POST /api/zones/{id}/boss/flee`): teste com o stat da classe —
    🛡 Warrior=STR, 🏹 Archer=DEX, 💰 Merchant=LUK, Recruit=DEX.
    `fugaPct = clamp(20..90, 30 + stat)`. **Sucesso** → escapa, a expedição completa normal (coleta
    aplicada). **Falha** → cai direto na luta do chefe (sem escolha).
  - **Encarar** (`POST /api/zones/{id}/boss/fight`): luta (combate completo — elementos + habilidades).
    **Vitória** → **1 item garantido no nível do chefe** (raridade alta: ~35% Raro, 40% Épico, 25%
    Lendário) + **XP/bronze bônus** (escala com o nível do chefe) + coleta normal. **Derrota** →
    `DEFEATED` (KO + penalidade do tier — vermelha trava item/XP, etc., reusa o fluxo de derrota atual).

## O que muda no código

### Modelo
- **`ZoneActivityStatus`**: + `BOSS_PENDING`.
- **`ZoneActivity`**: + `bossLevel` (int), `bossName` (String). Migração: 2 colunas (já tem migração de zona).

### ZoneService
- `monsterLevelFor(zone, playerLevel, rng)` (novo) usado pelo `fightNpc`.
- No `collect`/`resolveEncounters`: **rola o chefe primeiro** (por tier). Se sair → seta `BOSS_PENDING`
  + `bossLevel`/`bossName` e retorna sem resolver o resto (a coleta espera a decisão).
- `resolveBossFlee(player, activityId)` e `resolveBossFight(player, activityId)`:
  - flee: teste de stat → sucesso completa a expedição (coleta), falha chama o fight.
  - fight: monta `Combatant` do chefe (npcStats × mult + elemento) vs o player completo; vitória dá o
    loot garantido + bônus e completa; derrota = DEFEATED + penalidade do tier.
- **Loot do chefe**: cria 1 item (`inventoryService.make`, tipo aleatório, nome temático, lore) no
  `bossLevel` com raridade alta; mail se a bag estiver cheia. (Reusa o caminho de make/mail.)
- Helper `classFleeStat(warrior)` → valor do atributo de fuga por classe.

### ZoneController
- `POST /api/zones/{id}/boss/flee` e `/boss/fight` → `ZoneService`. O `collect` retorna no corpo
  `bossPending: true` + `bossName`/`bossLevel`/`fleeChance` quando há chefe.

### Frontend
- Quando o collect responde `bossPending`, abre um **modal do chefe** (nome, nível, % de fuga) com
  **Fugir** / **Encarar**. Cada botão chama o endpoint e mostra o resultado (loot/derrota) no modal
  de resultado padrão (`showCollectModal`) com o battle log.

## Balanceamento / notas
- O chefe é o motor de loot de zona (item garantido no nível alto dele). Mais frequente na vermelha
  → mais loot de alto nível → justifica o risco (PvP + item-lock + monstros mais fortes).
- Item do chefe pode vir **acima do teu nível** (até +20) → requer subir pra equipar (consistente com
  [ITEM_DROP_LEVEL]). Aposta de "grinda o boss e cresce pro gear".
- Números (chances por tier, multiplicador do chefe, fórmula de fuga, raridades) são placeholders p/ playtest.

## Fora de escopo (futuro)
- Drop de item nos monstros NORMAIS (hoje só recurso + o chefe). Se quiser, dá pra adicionar uma
  chance pequena escalando por tier/nível.
- Chefe com mecânicas próprias (fases, imunidades).
- Chefe dropar item temático da Torre / SoulStones.
