# PLANO — Torre: andares de vários monstros viram luta SIMULTÂNEA [TORRE_GRUPO]

## Problema

Andares da Torre com mais de 1 monstro eram um **gauntlet sequencial**: o jogador lutava
um monstro por vez (HP carregava entre eles). O dono pediu que esses andares passem a ter
os monstros **todos ao mesmo tempo** ("ao invés de em sequência, 2 ao mesmo tempo... faz
pra todos que têm mais de 1").

Andares afetados (mais de 1 monstro): **4, 5, 8, 13, 14, 16, 22, 24 (3 monstros!), 28, 33,
35, 38, 42, 44**. Os MVPs (a cada 10) e andares de 1 monstro **não mudam** (continuam duelo 1x1).

## Decisões do dono

1. **Stats cheios por inimigo** — cada monstro do andar mantém HP/ataque **inteiros** (não
   divide mais o HP do andar entre eles). Enfrentar 2-3 monstros completos ao mesmo tempo é
   de propósito mais difícil. (Antes: HP dividido por N + atk ×0.85.)
2. **Andar 24 = 3 ao mesmo tempo** — literal (o andar mais perigoso da Torre, 3-contra-1).

> Números são placeholders p/ tuning no playtest. A sonda (`TowerBalanceProbeTest`) mede a
> nova curva: Floor 5 (multi) ficou ~53% geared no nível, ~0% sem gear, ~100% +5 níveis.

## Desenho (3 camadas)

### 1. `BattleSimulator.simulateGroup(...)` — motor 1 vs N
Novo método público que **reusa** o `Side`/`attackRound`/`tick`/`applySelfTriggers` (o mesmo
motor do 1x1 — combate inalterado). Por round:
- gatilhos (Berserk/Second Wind) do jogador e de cada inimigo vivo;
- o **jogador foca o inimigo vivo mais ferido** (`focusTarget` = menor HP) — derruba um por vez;
- **cada inimigo vivo revida no MESMO round** → o jogador toma o dano de todos juntos.

PvE **neutro** (sem elementos/ativas, igual ao 1x1 da Torre hoje). `firstLosesOnTimeout=true`:
ninguém morre em 40 rounds → o jogador perde. Retorna `BattleOutcome` (`firstWon` = jogador venceu).

**Eventos** (p/ o replay): `record GroupFoe(name, atk, def, hp, dex, agi, luk)`. Emite **1 spawn do
jogador (1º)** + **1 spawn por inimigo** + os golpes (actor/target por nome) + 1 victory + a tag
`WINNER:...|LOSER:...`. O replay infere os lados pela **ordem** (1º = esquerda/herói; resto = direita).

### 2. `TowerService.climb` — roteia por contagem
- `monstersFor(floor)`: cada monstro com **stats cheios** (`monster(floor, nome, 1, 1.0, false)`).
- No climb: `monsters.size() == 1` → `simulateDetailed` (duelo, como antes); senão → monta os
  `GroupFoe` com **nomes únicos** (sufixo romano: "Altar-Thing", "Altar-Thing II", "…III" — senão
  o replay colide no dicionário por nome) e chama `simulateGroup`. O resto (recompensa, XP, KO,
  checkpoint) é igual.

### 3. `BattleReplay.gd` — mostra os N ao mesmo tempo
O replay **já tinha** renderização de time/lanes (Guerra de Guilda): fighters por **nome**, billboard
de HP, `_war_hit`, `_finish`, e o **stepping por-evento** da fase `"war"`. Reaproveitado:
- `_ready`: se os eventos têm **>2 spawns** (e não é war/team) → `_build_group()`.
- `_build_group()`: herói à **esquerda** (com **gear/itens REAIS**, igual ao 1x1) + inimigos em
  **lanes à direita** (`_group_lane_z`: 2→±1.4, 3→TEAM_ROWS), todos no `fighters`/`order`. Liga
  `team_mode`, posiciona a câmera e inicia a fase `"war"` (stepping por-evento, resolve por nome).
- Spawns viram no-op (fighters pré-montados); attack/crit/miss/heal/dodge/volley são animados pelo
  `_war_hit`/`_war_event`; `_finish` acha o vencedor pelos sobreviventes. Sem código de tick novo.

## Arquivos
| Arquivo | Mudança |
|---|---|
| `service/BattleSimulator.java` | `GroupFoe` + `simulateGroup` + `focusTarget`/`anyAlive` |
| `service/TowerService.java` | `monstersFor` stats cheios; climb roteia 1x1 vs grupo; `roman()` |
| `ui/BattleReplay.gd` (Godot) | detecção >2 spawns → `_build_group` + `_group_lane_z` (reusa fase "war") |
| `test/.../BattleGroupTest.java` | novo — contrato de eventos + 2 desfechos |
| `test/.../TowerBalanceProbeTest.java` | `clearRate` usa `simulateGroup` em andar multi |

## Verificação
- `mvn -o clean test` (motor compartilhado tocado).
- No Godot (reabrir p/ soltar o cache): subir a Torre até um andar multi (ex.: 4, 5, 28; **24 = 3**)
  → o replay mostra o herói vs 2-3 inimigos **ao mesmo tempo**, todos batendo no mesmo round;
  andar de 1 monstro/MVP segue como duelo 1x1.

## Notas
- Kiting (arco vs melee) no grupo usa o mesmo `attackRound`; com vários inimigos colando, o estado
  `pinned` do jogador é compartilhado (aproximação — placeholder, tunável).
- Triângulo/elementos não entram na Torre (neutra), mantido.

---

## Curva de dificuldade — crescente [TORRE_CURVA]

Com "stats cheios + simultâneo", os andares multi viraram PICOS (F24 x3 era mais difícil que ~10
andares à frente) e os de 1 monstro eram triviais (build geared lvl40 limpava o F49 a ~98%). O dono
pediu dificuldade **crescente**. Rebalance (constantes no topo do `TowerService`, tunadas pela sonda):

- **Base do monstro** (`M_ATK_BASE/PER`, `M_HP_*`, `M_DEF_*`) recalibrada p/ o crescimento por andar
  casar com o ganho de poder do jogador por nível → **at-level fica ~constante** e a curva sobe suave
  (sem cliff). Andares de 1 monstro deixam de ser triviais.
- **MVP** (`MVP_ATK/DEF/HP/AGI/LUK`) baixado de muro (era 0% at-level) p/ **degrau vencível** — boss
  do bloco (F10≈70% → F50≈20% at-level), não parede.
- **Andar multi** (`GROUP_ATK_BUMP`/`GROUP_HP_BUMP`): a soma dos N escala p/ o andar **encaixar na
  curva** (≈ um single do andar), não N×. Modelo: p/ 2 inimigos simultâneos, `atkBump×hpBump ≈ 1.33`
  ≈ dificuldade de 1; usei produto ~1.4. Resultado: F24 x3 deixou de ser pico (at-level ~90%, em
  linha com os vizinhos).

Validação: sonda `ZzTowerCurveProbe` (temp, apagada) varreu os 50 andares em build fixo + at-level
até a curva subir suave. `TowerBalanceProbeTest` (oficial) mede at-level **20%–99%**. Números seguem
placeholders p/ playtest — todos os knobs são constantes nomeadas no topo do `monster()`/`monstersFor`.

## Roupas dos inimigos da Torre [TORRE_VESTE]
Escopo: só quando `fight_scene == "tower"` (arena/PvP/guerra intactos). Em `BattleReplay.gd`:
- **MVPs** (`TOWER_MVP_LOOK`, casa o nome por substring): **set completo** + tema + recolor alto —
  Fallen Captain=knight, Coin-Eaten/Crowned Echo/Rei Arka=noble (**HELMET=Crown** → coroa; Arka/Coin
  raridade 5 = dourado), Xamã=wizard (manto). Um tiquinho maiores (scale 1.10).
- **Comuns**: sempre **bem vestidos** (nunca pelado/sem peito; raras sem elmo), tema pela "cara" do
  nome (`_tower_theme_for`: culto→wizard, corte→noble, guarda→knight), wizard empunha "cajado" (mace).
- Tudo via os temas/peças que já existem em `Outfits.gd` (sem asset novo). `_appearance` dá tema+cor;
  `_enemy_look` dá os slots vestidos.
