# Plano — Guerra de Território: Gauntlet 15v15 em ondas de 3v3 [GUERRA_GAUNTLET]

## Objetivo
Substituir o modelo atual de guerra (formação 3×5, gauntlets 1v1 por coluna, melhor de 3 — `[GUERRA_FORMACAO]`) por um **gauntlet 15v15 lutado em ondas de 3v3**, com **replay 3D** assistível.

## Mecânica escolhida (Modelo B — "vencedor fica curto")
Decidido com o jogador (2026-06-15):
- Cada time tem até **15 lutadores**, numa **ordem** (fila).
- O campo tem **3 de cada lado** (3v3). Começa com os 3 primeiros de cada fila.
- Uma **onda** = um 3v3 até a **morte de todos os 3 de um lado**.
- O lado **derrotado** (campo zerado) manda **3 frescos** do banco.
- O lado **vencedor MANTÉM seus sobreviventes** (1–3) **com o HP que sobrou** — **NÃO repõe** (pode seguir em 2 ou 1).
- Repete até **um time perder os 15** (campo vazio **e** banco vazio) → o outro vence a guerra.
- Consequência: um trio forte pode **varrer várias ondas** (herói/snowball), mas vai se desgastando (HP carrega).

## Resolução do 3v3 (sub-batalha melee)
Reaproveita a matemática de golpe do `BattleSimulator` (acerto por DEX/AGI, crit por LUK, mitigação `atk*100/(100+def)`, elementos ±25%). Para isso, extrair o cálculo de um golpe num helper reutilizável (`strike(attacker, defender) -> StrikeResult{dano, crit, esquiva, elemento}`), hoje embutido no loop 1v1.

Loop da onda:
1. **Ordem de iniciativa**: todos os vivos em campo, ordenados por **AGI** (desempate DEX) desc, agem 1× por rodada.
2. **Alvo**: cada atacante mira o **inimigo vivo com menor HP atual** (foco de fogo → sensação de "flanquear/cercar" o que o jogador já fez no mock).
3. Aplica `strike`, emite evento (`attack`/`crit`/`dodge`), atualiza HP; HP≤0 → evento de morte (remove do campo).
4. Fim da onda quando **um lado tem 0 vivos em campo**. Cap de rodadas (~80) → se estourar, vence quem tem **mais HP total**; o outro lado morre (sudden death) — evita loop infinito.
5. Sobreviventes voltam com o **HP restante**.

Abilities/kiting: v1 usa o núcleo (acerto/crit/mitigação/elemento). Habilidades ativas e kiting ficam como **follow-up** (o 1v1 do duelo continua completo; a guerra começa sem elas pra não explodir o escopo).

## Eventos para o replay
Um único `List<BattleEvent>` agregado da guerra inteira:
- `spawn` quando um lutador **entra em campo** (na 1ª onda os 6 iniciais; depois cada reforço que entra) — com nome, lado, e um `slot`/lane pra UI posicionar.
- `attack`/`crit`/`dodge`/morte por golpe (schema atual do `BattleEvent`).
- Marcador de **onda** (`type:"wave"`) entre as ondas, pro cliente animar transição/limpar mortos.
- `victory` no fim, com o time vencedor.

⚠️ O `BattleEvent` atual não tem `side`/`wave`. Opções: (a) adicionar campos (`side:int`, `wave:int`) ao record; (b) inferir lado pelo conjunto de nomes do `spawn`. Vou **adicionar `side` e `wave`** ao `BattleEvent` (campos novos, default 0/—; não quebra o 1v1).

## Backend — onde encaixa
- Novo: `GauntletWarSimulator` (ou método em `BattleSimulator`) — recebe `List<Combatant> teamA`, `List<Combatant> teamB` (até 15 cada) + nomes; devolve `{attackersWon, events, log, sobreviventes}`.
- `TerritoryService`: troca `guildBrawl` (3×5) pelo gauntlet. Mantém: montagem da fila a partir da formação/roster (`buildFormation` achatado por poder/frescor), **war fatigue**, **debuff de defensor**, persistência de HP.
- Persistência: guardar os **eventos** (JSON) no `TerritoryBattleLog` (coluna nova `battle_events` TEXT) além do log de texto, pra o cliente puxar e assistir.
- Endpoint: `GET /api/territory/{territory}/replay` → últimos `battleEvents` da guerra (pro Godot tocar). (A guerra resolve no scheduler de 6h; o replay fica disponível depois.)

## Frontend — Godot
- `BattleReplay`: trocar o **mock local** do team mode por um modo **orientado a eventos** que entende ondas:
  - lê `external_battle.events` (do backend), faz `spawn` posicionando 3 de cada lado em campo; reforços entram no lugar dos mortos (Modelo B: só o perdedor repõe).
  - aplica `attack/crit/dodge/morte` dos eventos (sem rolar dano local).
  - HUD de onda ("Onda %d") + "vs".
- `Territory.gd`: botão **"Assistir última batalha"** quando há replay → `request_battle` com os eventos puxados do `/replay`.

## Fases (commits incrementais)
1. ✅ **Backend core** (commit e3fe1a2): `GauntletWarSimulator` (melee 3v3 + driver Modelo B) com **evento próprio `WarEvent`** (side/wave) — desacoplado do `BattleSimulator` (havia WIP de outra aba lá; usa só APIs do HEAD: `hitChance`/`critChance`/`mitigatedDamage` + `Element.multiplier`). Testes `GauntletWarTest` (4) + suíte (646) verdes.
3. ✅ **Frontend visual** (commit 564f884): team-mode do `BattleReplay` vira gauntlet 15v15 — reservas de 15/lado, campo 3v3, perdedor repõe 3 / vencedor mantém sobreviventes, limpa corpos entre ondas, "Onda N". **Gated no `force_mock`** (1v1 real intacto). Testável no Godot via F6/force_mock. ⚠️ não testado em runtime ainda (iterar).
2a. ✅ **Backend — guerra usa o gauntlet** (commit e553da9): `resolveTerritory` chama `guildGauntlet` (Modelo B) p/ o vencedor + HP final (mapeado por índice → persistido). `guildBrawl` 3×5 mantido só pros 50 testes. War fatigue / debuff / persistência de HP intactos.
2b. ✅ **Backend — persistir + endpoint** (commit da99e4a): coluna `battle_events` (JSON) no `TerritoryBattleLog` + migração; `saveBattleLog` serializa os `WarEvent`; `GET /api/territory/{id}/replay` devolve os eventos da última batalha.
2b. ✅ **Visual — cliente toca o replay real** (commit 609fd0e): `BattleReplay` ganhou `war_mode` (orientado a eventos: spawn em lanes + attack/crit/miss/wave/victory com os visuais existentes); `Territory.gd` tem botão "Assistir última batalha" → `/replay` → `request_battle`.

## Limitações conhecidas do v1 (a iterar quando testar)
- No replay da guerra os lutadores **não andam** até o alvo (ficam na lane, tocam a anim de golpe + o alvo reage). Menos dinâmico que o mock (que aproxima). Ajustável.
- Visual **genérico** (o gear real não vai nos `WarEvent` — só nome/HP/lado).
- "Assistir" só funciona **depois** que uma guerra resolveu (cron 6h) E teve combate. Sem isso: "Sem batalha pra assistir ainda". Pra testar o visual JÁ, use o mock (force_mock, Fase 3).
- Strings novas do botão ainda **só em PT** (i18n a fazer).
- Habilidades ativas + kiting na guerra: follow-up (v1 só acerto/crit/mitigação/elemento).

## Em aberto / tuning (placeholders)
- Ordem da fila (poder? frescor? posição da formação?).
- Cap de rodadas e regra de sudden-death.
- Recompensa/territory flip continuam como hoje (só o COMBATE muda).
- Habilidades ativas + kiting na guerra = follow-up.
