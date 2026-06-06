# PLANO — Habilidades de Classe (Abilities) [HABILIDADES]

> Status: **implementado** (fases 1 e 2). `ClassAbilityTest` + `BattleSimulatorAbilityTest` +
> `AbilityIntegrationTest` cobrem árvore/passivas/ativas/respec. Quest checks = futuro.
> **Nome:** "Habilidades/Abilities" — distinto das **profissões** (`SkillType`: Pesca/Mineração/Forja),
> que já usam o termo "skill". Enum novo: `ClassAbility`; entidade `WarriorAbility`.

## Objetivo

Cada **level** dá **1 ponto de habilidade** (separado dos 2 de atributo). Gasta-se em habilidades
**da sua classe** (árvore Warrior / árvore Archer), cada uma até **lv10**. Dois tipos:
**passivas** (bônus de stat por nível) e **ativas** (disparam sozinhas no combate, com **cooldown**
em rounds — efeito escala com o nível, cooldown fixo, pra não ficar apelão).

## Decisões (alinhadas com o dono)

| Tema | Decisão |
|------|---------|
| Árvores | **Por classe** (Warrior / Archer). Recruit acumula pontos, gasta após escolher a classe. |
| Escala da ativa | **Cooldown fixo + efeito escala** com o nível |
| Respec | **Grátis na troca de classe** + **pago** (bronze) a qualquer hora |
| Set | 5 por classe (aprovado) |

## Pontos & regras

- **`Warrior.abilityPoints`** (int): +1 por level (em `levelUp()`). Recruit acumula (não tem árvore);
  ao virar Warrior/Archer, gasta o banco acumulado.
- Cada habilidade: nível **0–10** (0 = não aprendida). Subir 1 nível = 1 ponto. Cap 10.
- **Respec pago** (`/api/abilities/respec`): devolve todos os pontos gastos (zera as habilidades),
  custa bronze (placeholder: 200 × nível do guerreiro, ou fixo). Troca de classe / soft-wipe zera de graça.
- Só o **player** tem habilidades; NPCs/monstros não (por ora). PvP usa as dos dois players.

## Set de habilidades (placeholder p/ tuning)

### 🛡 Warrior
| Habilidade | Tipo | Efeito (escala com o nível N=1..10) |
|---|---|---|
| **Toughness** | passiva | +12×N HP |
| **Weapon Mastery** | passiva | +2×N ATK |
| **Shield Bash** | ativa (CD 5) | no golpe que dispara: +(8 + 4×N) de dano |
| **Second Wind** | ativa (1×/luta) | ao cair abaixo de 30% HP: cura (10 + 3×N)% do HP máx |
| **Berserk** | ativa (CD 8) | abaixo de 50% HP: +(5×N)% ATK por 3 rounds |

### 🏹 Archer
| Habilidade | Tipo | Efeito (escala com o nível N=1..10) |
|---|---|---|
| **Eagle Eye** | passiva | +2×N LUK (janela de crit + Fortune Save) |
| **Agility** | passiva | +1×N DEX (AC/esquiva) |
| **Precise Shot** | ativa (CD 4) | crítico **garantido** + (3×N) de dano bônus no golpe |
| **Volley** | ativa (CD 5) | um ataque extra naquele round a (50 + 5×N)% do dano |
| **Evasive Roll** | ativa (CD 6) | esquiva o próximo golpe inimigo + reflete (2×N) de dano |

(Sem mana/energia — o **cooldown** é o único gate. `INT` segue reservado p/ Mage futuro.)

## Integração com o combate

### Passivas — entram no `combatStats` (sem mexer no BattleSimulator)
`WarriorStatsService.combatStats` soma os bônus das passivas no array `[atk, def, hp, dex, str, luk]`
(Toughness→hp, Weapon Mastery→atk, Eagle Eye→luk, Agility→dex). Igual ao que já faz com gear/buffs.

### Ativas — entram no `BattleSimulator`
- **Refactor do simulador:** o `simulateDetailed` já está com assinatura enorme (stats + elementos).
  Introduzir um record **`Combatant`** (name, atk, def, hp, dex, strBonus, luk, weaponElement,
  armorElement, **List<ActiveAbility>**) e um `simulate(Combatant a, Combatant b, firstLosesOnTimeout)`.
  Os overloads atuais viram delegates (montam Combatant sem abilities) → callers existentes seguem iguais.
- **`ActiveAbility`** = (effectType, level, cooldownRounds, estado interno). `effectType` (enum) que o
  simulador entende:
  - `BONUS_DAMAGE` (Shield Bash) — no turno do dono, se CD pronto: +dano no golpe.
  - `EXTRA_ATTACK` (Volley) — turno extra a X% do dano.
  - `GUARANTEED_CRIT` (Precise Shot) — força crit + dano bônus.
  - `HEAL_LOW` (Second Wind) — 1×/luta, cura ao cair < 30%.
  - `ATK_BUFF_LOW` (Berserk) — buff de ATK temporário ao cair < 50%.
  - `DODGE_INCOMING` (Evasive Roll) — anula o próximo golpe recebido + reflete.
- O loop por round mantém um contador de cooldown por ativa; quando dispara, escreve uma linha no log
  ("🏹 Precise Shot — guaranteed crit! [+N]"). Cooldown fixo por habilidade; o **nível só muda o efeito**.
- Só o lado-player carrega abilities (NPC = lista vazia). PvP/Arena: os dois lados.

### Onde aplica
Zona (PvE + PvP), Arena (PvP), Trial de classe e Torre (player tem suas ativas; o foe NPC não).

## O que muda no código

### Novo
- **`ClassAbility`** enum: as 10 habilidades, cada uma com owner (`WarriorClass`), tipo (PASSIVE/ACTIVE),
  `effectType`, cooldown base, displayName/icon/descrição. Magnitude por nível = fórmula em código.
- **`WarriorAbility`** entidade: (id, warrior, ability, level). Repo `WarriorAbilityRepository`.
- **`AbilityService`**: `list(player)` (árvore da classe + níveis + pontos), `learn(player, ability)`
  (valida classe/pontos/cap, sobe 1), `respec(player)` (pago, devolve pontos).
- **`AbilityController`** (`/api/abilities`): GET (árvore), POST `/learn/{ability}`, POST `/respec`.
- **`BattleSimulator.Combatant`** record + `ActiveAbility` + overload `simulate(Combatant, Combatant, …)`.

### Alterado
- **`Warrior`**: + `abilityPoints` (coluna); `levelUp()` += 1.
- **`WarriorStatsService.combatStats`**: soma as passivas.
- **`ClassChangeService`**: respec grátis das habilidades junto com os atributos (recruit→classe).
- **`MaintenanceService`** (soft-wipe): zera abilities + abilityPoints.
- **`ArenaService` / `ZoneService`**: montam `Combatant` com as ativas dos players.
- **`WarriorController`**: expõe abilityPoints (e talvez resumo das ativas) na ficha.
- **`SchemaMigrator`**: coluna `ability_points` + tabela `warrior_abilities` (auto-criada pelo ddl-auto).
- **Frontend**: painel "Habilidades" (na aba Personagem) — árvore da classe, nível atual, botão +,
  pontos disponíveis, respec; linha das ativas no log de batalha; abilityPoints na ficha.

## Fases (1 feature)

1. **Núcleo + passivas:** modelo (`ClassAbility`/`WarriorAbility`/pontos), `AbilityService`/Controller,
   passivas no `combatStats`, respec, UI da árvore. (Já dá pra ganhar/gastar pontos e sentir as passivas.)
2. **Ativas no combate:** refactor `Combatant`, `effectType`s no simulador, Arena/Zona montando o kit,
   linhas no log. (Fecha o sistema.)

## Fora de escopo (futuro)
- **Checks em quest** (ex.: "Precise Shot ≥ 5" libera saída sem roll) — o nível já fica gravado e
  consultável; a fase de quests interativas usa isso depois.
- Habilidades de monstro/boss (NPC com ativas).
- Árvore da classe Mage (quando existir).
- Sinergias/combos entre habilidades.
