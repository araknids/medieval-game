# PLANO — Formação na Guerra de Território (3×5 lanes) [GUERRA_FORMACAO]

> Status: **implementado**. `TerritoryWarTest` cobre o gauntlet por lane (HP real + maioria 2/3).

## Conceito

A guerra de território deixa de ser "embaralha e king-of-the-hill com HP grosseiro". O líder
**posiciona** os até 15 membros num **tabuleiro 3×5** (3 colunas/lanes × 5 de profundidade). Cada
**lane** vira um duelo em cadeia: a frente luta, o **vencedor segue com a vida restante** (HP exato
do simulador) contra o próximo da coluna inimiga, e assim por diante. Quem ganhar **2 das 3 lanes**
vence a batalha.

## Decisões (alinhadas com o dono)

| Tema | Decisão |
|------|---------|
| Estrutura | **3 colunas (lanes) × 5 de profundidade**; cada lane é um gauntlet independente |
| HP entre lutas | **HP real restante** (do `BattleOutcome`), carregado pelo vencedor (sem cura) |
| Vitória | **Maioria das lanes** (2 de 3) |
| Sem formação / <15 | **Auto-preenche** as células vazias por poder + frescor (como hoje) |

## Mecânica da lane

Para a lane *k* (0–2): fila do atacante = fighters da coluna ordenados por profundidade (0→4,
pulando vazios); idem defensor.
- Atacante da frente (HP cheio) vs defensor da frente (HP cheio). Luta resolve no `BattleSimulator`.
- **Vencedor fica** com o **HP restante exato**; perdedor sai; entra o próximo **fresco** (HP cheio)
  do lado que perdeu. Repete.
- Lane acaba quando uma fila zera → esse lado **perde a lane**. (Coluna vazia de um lado = lane do
  outro por W.O.)
- `attackersWon` = ganhou **≥ 2 lanes**.

Isso limita o snowball: um fighter forte varre no máximo **uma coluna (5)**, não os 15.

## Combate completo na guerra

Hoje o `guildBrawl` usa `simulateDetailed` **sem elementos nem habilidades**. Aproveitando, a
guerra passa a usar o **combate completo** (`simulate(Combatant, Combatant)`): cada `Fighter`
carrega seu **elemento de arma/armadura** (encantamento ativo) e o **kit de ativas**
(`AbilityService.activeLoadout`). Consistente com Arena/Zona. (Fatiga/debuff continuam multiplicando
só atk/def/dex.)

## O que muda no código

### Modelo / armazenamento
- **`Player`**: + `warLane` (0–2, default −1 = não posicionado) e `warDepth` (0–4, default −1).
  Migração: 2 colunas. `inWarRoster` passa a significar "posicionado na formação".
- **`Fighter`** (TerritoryService): + `weaponElement`, `armorElement`, `List<ActiveAbility> abilities`.

### Líder monta a formação
- **`GuildService.setWarFormation(leader, slots)`** — `slots` = lista de `{playerId, lane, depth}`.
  Valida: membros da guild, lane 0–2, depth 0–4, sem célula duplicada, ≤15. Seta `warLane/warDepth`
  (+`inWarRoster=true`) nos posicionados; zera nos demais. **`GuildController`** novo endpoint
  `POST /api/guild/war-formation`. (O `setWarRoster` antigo continua p/ compat / seleção simples.)

### Resolução
- **`buildFormation(guild, debuff, cycle)`** (substitui/embrulha `buildFighters`): monta um grid
  `Fighter[3][5]`:
  1. candidatos elegíveis (warrior + HP>0) com `combatStats` (gear/buff/postura) — como hoje;
  2. coloca os posicionados pelo líder nas células `warLane/warDepth`;
  3. **auto-preenche** as células vazias (frente primeiro: depth 0 de cada lane, depois depth 1…)
     com os mais frescos/fortes, até 15;
  4. aplica `× debuff × cansaço` em atk/def/dex (multiplicativos), como hoje.
- **`guildBrawl(atkFormation, defFormation, territory)`**: roda as 3 lanes (gauntlet com HP real),
  conta lanes, `attackersWon = lanesAtk ≥ 2`. Persiste o HP restante dos sobreviventes
  (`persistHpChanges`). Log por lane.
- `resolveTerritory` (Phase 1 cada atacante vs defensor; Phase 2 desempate) chama a nova
  `guildBrawl` com as formações. Defensor é reconstruído fresco a cada luta da Phase 1 (como hoje).

### Frontend
- Grade 3×5 na aba Guild (visível pro líder): arrasta/clica membros pra posicionar; mostra
  cansaço/poder por membro; salva via `/api/guild/war-formation`. Auto-fill indicado nas vazias.
- Log de batalha por lane no histórico da guerra.

### Migração / DB
- `players`: `war_lane`, `war_depth` (default −1). Sem mais schema.

## Balanceamento / notas
- HP real (sem cura entre lutas) torna a **profundidade** decisiva: o 1º de cada lane precisa
  aguentar; o fundo finaliza os feridos. Tanques na frente, finalizadores atrás.
- Maioria 2/3 permite **sacrificar uma lane**. Empate impossível (3 lanes, sempre há vencedor por luta).
- Números (sem cura, ordem de auto-fill) são ajustáveis no playtest.

## Fases
1. **Backend:** colunas no Player + `buildFormation` + `guildBrawl` por lane (HP real + Combatant)
   + `setWarFormation`/endpoint + migração + ajustar `TerritoryWarTest`.
2. **Frontend:** grade 3×5 de posicionamento + log por lane.

## Fora de escopo (futuro)
- Habilidades/itens de "formação" (ex.: bônus por lane cheia).
- Cura parcial entre lutas (se o playtest pedir).
- Formações por reino (uma por território declarado).
