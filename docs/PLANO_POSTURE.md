# Plano — Combat Posture (postura de combate)

> Status: **implementado** (2026-06-05). Fonte da verdade da feature.
> Idioma: doc em PT; código e strings de UI em EN (traduz pro PT depois via i18n).

## Objetivo

O jogador escolhe uma **postura de combate** que ajusta seus stats num tradeoff:
ofensiva (mais dano, menos defesa), defensiva (o contrário) ou equilibrada. Vale em
**todo combate — PvE e PvP** (arena, torre, zona, quest de reino **e Guerra de Território**).

## Decisões travadas (alinhadas com o dono)

| Pergunta | Decisão |
|---|---|
| Modelo | **ATK/DEF puro (tradeoff)** — sem mexer em crit/dex/hp/luk |
| Escopo | **Todo combate, PvE e PvP** — inclui Guerra de Território |
| Guerra de Território | Passa a usar **TODOS os stats** (gear + buffs + postura), não só base ⚠️ |
| Troca de postura | **Livre (toggle)** — sem custo/cooldown |

## Modelo

`enum CombatPosture` com multiplicadores (% sobre o ATK/DEF **final**, já com gear+buffs):

| Postura | ATK | DEF |
|---|---|---|
| ⚔️ **OFFENSIVE** | ×1.20 (+20%) | ×0.85 (−15%) |
| 🛡️ **DEFENSIVE** | ×0.85 (−15%) | ×1.20 (+20%) |
| ⚖️ **BALANCED** (default) | ×1.05 (+5%) | ×1.05 (+5%) |

- Balanced com bônus pequeno nos dois → nunca é a melhor, nunca é ruim (escolha válida p/ conteúdo misto).
- Magnitude modesta (±15-20%) de propósito: não ofusca gear/level e evita stalemate por timeout em PvP.
- **Só ATK e DEF.** Não toca to-hit (STR), AC (dex), HP, crit nem luck.

## Onde engata

### combatStats (o coração)
`WarriorStatsService.combatStats(player, warrior)` é o ponto único que devolve
`[atk, def, hp, dex, strBonus, luk]` com gear + buffs. A postura entra como **último passo**,
multiplicando só `atk` e `def`:
```
atk = round((base + gear + buff) * posture.atkMult())
def = round((base + gear + buff) * posture.defMult())
```
Como arena/torre/zona/quest já usam `combatStats`, a postura passa a valer neles **de graça**.

### Guerra de Território (a correção pedida) ⚠️
Hoje `TerritoryService.buildFighters()` monta o `Fighter` com **stats base** (`w.getAttack()`,
`w.getDefense()`, `w.getHealth()`…), ignorando gear/buffs. Mudança: passar a usar
`combatStats(member, w)` (gear + buffs + postura). A ordem dos multiplicadores fica:
```
combatStats(base+gear+buff+POSTURA) × debuff-de-defensor × cansaço-de-guerra
hp = combatStats[2] × HP% atual / 100
```
> Consequência: guilds melhor equipadas/buffadas ficam mais fortes na guerra (intencional).
> O cansaço [[GUERRA_ROSTER]] e o debuff de defensor continuam aplicados por cima.

## Mudanças por arquivo

### Backend
- **`enums/CombatPosture.java`** (novo): `OFFENSIVE/DEFENSIVE/BALANCED` com `atkMult()/defMult()` + `displayName`.
- **`model/Warrior.java`**: `@Enumerated(STRING) CombatPosture combatPosture = BALANCED`
  (`columnDefinition = "varchar(20) default 'BALANCED'"`).
- **`service/WarriorStatsService.combatStats()`**: aplica `posture.atkMult/defMult` no atk/def finais.
- **`service/WarriorService`** (ou controller): `setPosture(player, posture)` — toggle livre, com log.
- **`controller/WarriorController`**: `POST /api/warrior/posture` `{posture}`; `buildResponse` expõe `combatPosture` (+ um preview dos mults p/ a UI, opcional).
- **`service/TerritoryService.buildFighters()`**: troca stats base → `combatStats(member, w)`
  (injeta `WarriorStatsService`); mantém os multiplicadores de debuff/cansaço; `hp` via combatStats[2].
- **`config/SchemaMigrator`**: `ALTER TABLE warriors ADD COLUMN IF NOT EXISTS combat_posture varchar(20) NOT NULL DEFAULT 'BALANCED'`.

### Frontend (`static/app.js`)
- Seletor de 3 botões (⚔️/🛡️/⚖️) na tela do guerreiro, destacando a ativa, com preview dos efeitos
  (+20% ATK / −15% DEF etc.). `POST /api/warrior/posture` → recarrega o guerreiro. Strings em EN.

### Testes
- **Unit**: `combatStats` aplica os mults certos por postura (offensive sobe atk/baixa def, etc.);
  Balanced = +5/+5; só mexe em atk/def (dex/hp/luk/strBonus intactos).
- **Integração**: `POST /api/warrior/posture` troca e persiste; resposta do `/api/warrior` reflete;
  Guerra de Território passa a refletir gear/buffs/postura no `buildFighters` (Fighter mais forte com gear).

## Consequências / notas
- Troca livre → o jogador otimiza a postura por atividade (esse é o ponto).
- Guerra de Território vira **gear/buff-aware** (mudança de balanceamento — ajustar números depois se precisar).
- Magnitude (±20/±15/+5) é tunável; começamos conservador.
