# Plano — Rebalance de Combate: atributos com papel claro (sai o AC)

> Origem: auditoria em [AUDITORIA_BALANCE.md](AUDITORIA_BALANCE.md). O modelo antigo tinha a
> "parede de AC" (`AC = 10 + DEX`, acerto travado em d20+4), crit one-shot e CON inútil.
> Decisão do dono do jogo: redesenhar os atributos dando a cada um um papel óbvio, **mantendo o d20**.

## Modelo novo de atributos

| Atributo | Papel | Fórmula |
|---|---|---|
| **STR** (Força) | Dano (melee) | `ATK = baseAttack + STR` p/ espada/machado/marreta |
| **CON** (Constituição) | Vida | `HP = baseHealth + CON×8` |
| **DEX** (Destreza) | **Acerto** (+ dano do ARCO) | teste de acerto; e p/ Arqueiro **escala o dano** (`ATK = base + DEX`) |
| **AGI** (Agilidade) — NOVO | **Ataques + Esquiva** | chance de golpe extra + termo de esquiva no acerto inimigo |
| **LUK** (Sorte) | **Crítico** | janela de crit + Fortune Save; crit agora **×1.5** |
| **INT** (Intelecto) | Reservado (Mage) + economia | sem efeito de combate |

**Dano por classe** (`WarriorClass.damageAttribute()`): Força não devia aumentar o dano do **arco**. Logo o
**Arqueiro escala o dano com DEX** (precisão = acerto **+** dano → DEX vira a stat-chave dele, STR vira descarte);
espada/machado/marreta (Warrior/Merchant/Recruit) com **STR**. `Warrior.getTotalBaseAttack()` + o afixo-de-dano
do gear (`combatStats`) seguem esse atributo.

**Sai o AC** (`10 + DEX`). DEF continua como **mitigação de dano** (`ATK×100/(100+DEF)`), vinda de base de classe + gear (nenhum atributo aumenta DEF direto).

## Kiting — Arqueiro vs corpo-a-corpo (distância) — [KITING]

O Arqueiro escala dano+acerto com DEX e velocidade+esquiva com AGI → muito eficiente, dominava o melee.
Correção temática: quando o **melee cola no arqueiro**, o arqueiro **atira de perto com dano reduzido** e
depois **perde um turno recuando** pra reabrir distância.

- O simulador agora sabe quem é **ranged** (`Combatant/Side.ranged`, `WarriorClass.isRanged()` = Archer);
  passado em todos os call sites (Arena/Zona PvE+PvP/Torre/Quest/Guerra). NPC/chefe/guardião = melee.
- Estado do arqueiro: `0` à distância (tiro cheio) → o melee **cola** (chance) → `2` encurralado
  (tiro de perto a `ARCHER_CLOSE_DMG=×0.5`) → `1` recuando (**perde o turno**) → volta a `0`.
- Chance do melee colar: `clamp(20,85, MELEE_CLOSE_CHANCE(60) + (AGI_melee − AGI_arqueiro)/3)` — melee rápido
  cola mais, arqueiro ágil escapa um pouco (mas não some).
- **Resultado (sonda, Lv50, sem gear):** virou um **triângulo** — Archer › Warrior (56%) › Merchant (63%) ›
  Archer (55%). Tudo soft-counter (55–63%), sem atropelo. Números são placeholders.

## Motor de combate (BattleSimulator)

Mantém d20, 40 rounds, desempate por %HP. Por golpe:

```
roll = d20 (1..20)
roll == 1            -> erro automático (fumble), salvo Precise Shot
isCrit  = roll >= critThreshold(LUK_atacante)         // crit por sorte
fortuneSave: LUK_def/10 % de anular um crit recebido
acerto  = roll + DEX_atacante/5 - AGI_defensor/8
acertou = (acerto >= 11) OU isCrit                    // crit fura a esquiva (perfeito acha a brecha)
dano    = max(1, round(ATK×100/(100+DEF) × elemento))
if isCrit: dano = round(dano × 1.5)                   // era ×2 (matava o one-shot)
```

**Ataques extra (AGI ofensivo):** depois do golpe base, se o defensor está vivo:
```
chanceExtra% = clamp(0, 75, (AGI_atacante - AGI_defensor) × 1.0)
if rng < chanceExtra%: faz +1 golpe (mesmo teste de acerto/crit)
```
1 golpe extra por round (MVP, tunável). `EXTRA_PER_AGI = 1.0`, `EXTRA_CAP = 75` (reduzido do 1.5/90 inicial p/ suavizar o snowball do Arqueiro).

**Por que isso resolve a auditoria:**
- DEX não cria mais "parede" — é uma curva suave de acerto (`/5`), e a esquiva (AGI) é limitada por cap.
- Crit ainda fura esquiva (LUK é a resposta a quem some), mas ×1.5 não one-shota.
- AGI dá um eixo novo (velocidade/esquiva) que não existia → mais variedade de build.
- STR vira dano puro; CON (HP) volta a valer porque ninguém é "imune a acerto normal".

## Constantes do acerto/esquiva (placeholders p/ tuning)

- `HIT_DC = 11` (alvo do teste de acerto).
- acerto: `DEX/5` (cada 5 DEX = +1). Cap natural: roll 1 sempre erra; sem teto explícito além do d20.
- esquiva: `AGI/8` (cada 8 AGI do defensor = −1 no acerto inimigo). Com cap de AGI ~40–55 por classe,
  a esquiva máx fica ~−5 a −7 → não vira parede (crit sempre fura).

## Caps por classe (6 atributos)

| Classe | STR | DEX | CON | **AGI** | LUK | INT | base ATK/DEF/HP |
|---|---|---|---|---|---|---|---|
| RECRUIT | 40 | 40 | ∞ | 40 | 40 | 30 | 12/10/100 |
| WARRIOR | 80 | 30 | ∞ | 25 | 30 | 20 | 15/14/130 |
| ARCHER | 45 | 45 | ∞ | 40 | 70 | 20 | 12/9/95 (dano = DEX) |
| MERCHANT | 55 | 40 | ∞ | 35 | 60 | 20 | 15/11/115 |

Identidade: **Warrior** = dano+HP (lento, pouca esquiva); **Archer** = acerto+velocidade+crit (golpes extras, esquiva, frágil); **Merchant** = equilibrado, sorte alta. Números são placeholders — a sonda valida.

## Mapeamento na combatStats `[atk, def, hp, dex, agi, luk]`

- slot 3 = **dex** (acerto): `getDexterity() + gear.dex + passiveDex`
- slot 4 = **agi** (era strBonus): `getAgility() + buff.evasion + pet.agi + passiveAgi`
  - o buff "evasão" do Templo e o "AGI plana" do pet passam a alimentar AGI (esquiva), não mais DEX/AC.
- slot 5 = **luk**: `getLuck() + gear.luk + passiveLuk`
- STR sai do slot de acerto (não há mais `STR/20`); STR só compõe ATK (slot 0) via `getTotalBaseAttack()`.

Passivas (ClassAbility): **Agility** (Archer) → AGI; **Eagle Eye** → LUK; **Toughness** → HP; **Weapon Mastery** → ATK; **Haggler** → LUK.

## Migração

- `Warrior.agility` `@Column(columnDefinition="integer default 0")`.
- SchemaMigrator: `ALTER TABLE warriors ADD COLUMN IF NOT EXISTS agility integer NOT NULL DEFAULT 0;`
- Respec: ClassChange e soft-wipe somam/zeram `agility` junto com os outros. Sem migração de dados de
  jogador (soft-wipe reseta no teste; respec pago realoca).

## Fora de escopo (por agora)

- Gear com `agiBonus` (itens dando AGI) — depois; hoje AGI vem só de atributo/pet/buff/passiva.
- Segundo/terceiro golpe extra por AGI (hoje cap em 1 extra). Tunar com a sonda.
- Revalidar o triângulo de classe **com arma+elemento** (a sonda isola atributos).

## Verificação

Re-rodar `CombatBalanceProbeTest` (atualizada pro novo modelo) + suíte completa + `ClassTrialBalanceTest`
(re-tunar guardião se a Trial ficar fácil/impossível). Alvo: pior-vs-melhor build menos atropelo,
sem build único dominante, crit sem one-shot.
