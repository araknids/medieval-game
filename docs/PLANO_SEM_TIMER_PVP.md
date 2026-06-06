# Plano — Jogo sem Timer + PvP de Zona com Flag

> Planejamento (fase de design). Decisões aprovadas com o dono em 2026-06-04.
> **Status: design travado. NÃO implementado.** Regra: discutir + documentar ANTES de codar.

## Visão
Tirar os **timers** de quest/trabalho/coleta — o jogo passa a ser **centrado em estamina**:
chega, gasta estamina em ações **instantâneas**, sai, volta quando regenera. O relógio deixa de
ser o gate; a **estamina** é. Sem espera, sem "coletar depois".

## Decisões (2026-06-04)
1. **Quest / Trabalho / Coleta → instantâneos** (sem timer, sem etapa de collect; gasta estamina → recompensa na hora). Torre já é instantânea.
2. **Estamina regenera 100% em 1h** (hoje 2h).
3. **Sem idle income passivo** — tudo é "gasta estamina → recompensa". Burst play.
4. **PvP de loot = Zona com Flag** (modelo abaixo).
5. **Arena** = duelo instantâneo por **ranking** (sem loot), separada do PvP de zona.
6. **Guerra de guild (territórios)** = continua por **ciclos agendados** (não é timer de atividade; fora deste escopo).

## Coleta unificada no sistema de zona (2026-06-05)
Toda coleta (Pesca/Mineração/Mar Abençoado/Grutas) agora passa pelo `/api/zones/enter` GATHERING — **ganhou PvP** nas zonas amarela/vermelha de cada reino, mantendo os **drops específicos do reino**. `ZoneActivity.kingdom` (novo, + migração) leva o reino até `resolveGathering` → `gatheringService.collectGatheringDropsOnly(..., kingdom)` (Mar Abençoado = peixe de vida). As 3 zonas viram tiers: Safe→SAFE, Wild→PVP, Deep→HIGH_RISK. 🟢 SAFE roda só NPC (PvE 20%, sem perda de XP); 🟡🔴 rodam PvP+NPC + flag/lock/raid. Estamina role-aware: coleta `~d/2` (10⚡ por ação de 20min), combate `~d/8`. Duração mínima da zona baixada 30→5 (coleta usa chunk curto). Narrativa de coleta no modal de resultado. Front: botões de zona de coleta → `enterKingdomZone(tier, skill, d, kingdom)`. O `/api/gathering` segue só pro consumo de peixe + recursos. TC-223 cobre drops por reino. 468 verdes.

## Remoção do "busy"/onMission (2026-06-05)
Com tudo instantâneo, o flag `Warrior.onMission` ("busy") virou inútil — só causava bug de guerreiro
"preso". **Removido por completo:** o campo `onMission` (drop da coluna `warriors.on_mission` via
SchemaMigrator), todos os `setOnMission`/`isOnMission`, o método `WarriorService.freeIfStuck`, o
endpoint `POST /api/warrior/free`, o campo `onMission` do `/api/warrior`, e no front o badge ⚔Busy +
botão 🔓 Liberar + os labels "Warrior busy".

**Não há mais bloqueio CRUZADO entre atividades** (dá pra ter uma quest ativa E trabalhar). O que
**permanece** é o guard PRÓPRIO de cada atividade (sessão única por tipo): work/torre/treino já
tinham `findByPlayerAndStatus(IN_PROGRESS)`; a **quest ganhou** `existsByPlayerAndStatus(IN_PROGRESS)`
(uma quest por vez — também fecha um bypass do daily-lock: sem isso dava pra startar a mesma quest 2x
antes de coletar). Zona **auto-cancela** expedição pendurada ao re-entrar. Arena/raid são 100%
instantâneos (sem sessão), então perderam o guard sem efeito colateral. KO/HP seguem como guard
separado (inalterado). Testes de "bloqueio cruzado" (WarriorExclusivityTest, TC-058/070/094/096)
viraram testes do guard-próprio ou foram removidos. 455 verdes.

## Limpeza de endpoints legados (2026-06-05)
Com a coleta unificada na zona e Arena/quests já instantâneas, removi os fluxos legados/duplicados que ficaram órfãos na UI:
- **Quest legado**: `/api/quests`, `QuestService`/`QuestController`, `ActiveQuest(Repository)`, `enums/QuestType`. As missões vivas são as do reino (`/api/world/{kingdom}/quests`).
- **Sessão de coleta**: `GatheringSession(Repository)`, `enums/GatheringStatus`, `/api/gathering/start|collect|cancel|current` + os métodos de sessão do `GatheringService` (start/collect/cancel + `staminaCostFor`). A coleta roda 100% pela zona; `/api/gathering` ficou só com `skills`/`resources`/`consume`.

Limpezas de borda: `MaintenanceService`/`WarriorService` perderam os repos/blocos dessas sessões; `SchemaMigrator` perdeu o patch da coluna `gathering_sessions.kingdom`; front perdeu `startKingdomGathering`/`collect|cancelKingdomGather` e o banner "🎣 Gathering in Progress" (a expedição de zona é o único banner). Testes migrados: os de "guerreiro ocupado" usam `/api/work`; `FishSplitTest` chama `collectGatheringDropsOnly(..., kingdom)` direto; `WarriorExclusivityTest` ocupa o guerreiro via zona GATHERING. A tabela órfã `gathering_sessions` fica inerte em prod (não é dropada — dado preservado). **453 verdes.**

**Front legado de quest da taverna removido:** a tela antiga de missões da taverna já estava escondida (divs `display:none`), mas o boot ainda chamava `loadQuestTypes()`/`loadActiveQuests()` + `setInterval(…, 10s)` contra os `/api/quests/*` deletados — disparando **404 a cada login + a cada 10s**. Removi todo o bloco (`switchQuestTab`, `loadQuestTypes`, `renderQuestTypes`, `loadActiveQuests`, `sendOnMission`, `open/closeQuestProgress`, `abandonQuest`, `renderQuestProgress`, `collectFromProgress`, `collectReward`) + os helpers órfãos (`QUEST_NARRATIVES`, `DROP_NARRATIVES`, `questNarrative`, `questTypes`) + as chamadas no boot + os divs ocultos (`quest-types-list`/`active-quests-list`/`qp-content`). As missões vivas seguem 100% no World (`/api/world/{kingdom}/quests`).

## Tiers de zona (balance — 2026-06-05)
| Zona | PvP/NPC | Ao perder | Lock | Reward |
|---|---|---|---|---|
| 🟢 Verde (SAFE) | — / 20% | só PvE (dano/KO) | — | 1.0x |
| 🟡 Amarela (PVP, lvl 10+) | 20% / 25% | **−10% bronze + XP** (sem recursos/item) | — (recursos/gear seguros) | 1.5x |
| 🔴 Vermelha (HIGH_RISK, lvl 20+) | 40% / 35% | −50% rec + −15% bronze + item(35%) + XP | **itens + recursos** | 2.5x |
- **XP**: a vítima perde `expNeed/20`; o killer ganha **50%** (teto 10% do nível dele). [FORTALEZA_ZONAS] agora vale nas DUAS zonas (antes só na vermelha).
- **Matchmaking**: só ataca/é atacado dentro de **±10 níveis** (`PVP_LEVEL_BAND`). O minLevel da zona já protege os baixos.
- Item-lock só na vermelha; recurso-lock (bloqueia depositar no stash enquanto flagged) nas duas.

## PvP de Zona com Flag (o coração da mudança)
- Farmar numa **zona PvP** (tier PvP / Alto Risco) = **instantâneo**, custa estamina, dá loot melhor que a zona Segura.
- Farmar ali te deixa **flagged por 1h** → durante o flag, seus itens ficam **expostos**.
- **Exposto = bag + equipados não-protegidos.** **Stash e itens guardados no Templo = imunes.**
  (→ amarra o Templo como sink: guardar o gear bom protege; arriscar = mais cômodo.)
- Quando **outro jogador entra na mesma zona PvP**, há **chance de cruzar com um flagged** (matchmaking
  por nível/poder) → **PvP instantâneo** (snapshot, regras do Combate V2: %HP/timeout).
  - **Atacante vence** → rouba: **bronze + chance de 1 item + recursos** (da bag/equip não-protegidos do flagged).
  - **Atacante perde** → não loota + leva dano/KO. (e ele também está flagged por ter farmado → vira alvo.)
- **Saqueado 1x por ciclo:** ao **perder** um PvP, a vítima ganha **escudo** e o flag cai → não é farmada em sequência.
- **Preenchimento com NPC flagged:** se não há player real flagged na zona (pop baixa / teste solo), gera um
  **alvo NPC flagged** (loot menor) → o PvP sempre rola, funciona solo e no early game.
- **Zona Segura:** sem flag, sem risco, loot menor.

## Modelo de dados (esboço)
- `Warrior`/`Player`: `pvpFlaggedUntil` (timestamp; null = não exposto) + `pvpShieldUntil` (imunidade pós-derrota).
- Loot do PvP: reusa o transfer de bronze/itens/recursos (itens via `stashed=false` + não-`guarded`).
- Encontro: ao entrar numa zona PvP, sortear entre os flagged (mesma zona, nível próximo); se nenhum, NPC.
- Combate: `BattleSimulator.simulateDetailed(..., false)` (PvP, %HP) — atacante = quem entrou; defensor = flagged.

## Fases de implementação
1. **Fase 1 — Sem timer + estamina 1h.** ✅ **FEITA.** Quest/Coleta/Quest-de-reino/Treino → `finishesAt=agora`
   (coleta imediata, sem espera, independente do flag). Trabalho → instantâneo + **custo de estamina (horas×5)**
   (senão seria bronze infinito; nº de horas = dial recompensa×estamina). Regen 100% em 1h. O flag
   `instant-complete` agora controla só o **bypass de estamina** (teste). Front já lidava com instantâneo.
   *(Polish pendente: labels de duração nas quests viraram cosméticos; mostrar custo de estamina do trabalho.)*
2. **Fase 2 — PvP de Zona com Flag.** ✅ **FEITA.** `Player` ganhou `pvpFlaggedZone`/`pvpFlaggedUntil`/`pvpShieldUntil`
   (+ migração). Farm de zona = **instantâneo** (`endsAt=agora`); sobreviver numa zona PvP/Alto-Risco te deixa
   **flagged 1h**. No `collect`, o roll de encontro busca um **player flagged** na zona (`findFlaggedInZone`, sem
   escudo) → combate (PvP %HP); vencendo, o atacante **saqueia** bronze (15%) + 1 item não-protegido (35%, `!stashed`
   `!guarded`) + ~25% dos recursos da bag → tudo transferido (clamp na bag). A vítima ganha **escudo 1h** + o flag cai
   (saqueado 1x/ciclo) + mail. Sem player flagged → **NPC ambusher** de preenchimento (PvP solo/early funciona).
   Stash + Templo imunes (`!stashed`/`!guarded`). **Item-lock [PVP_FLAG]:** ao farmar, os itens bag+equipados
   expostos ganham `pvpLocked=true` (snapshot na entrada) — enquanto flagged **não podem ser vendidos, stashados
   nem guardados no Templo** (fecha o exploit de "stashar o gear bom depois de farmar"), e são exatamente esses
   que o raid sorteia. Destrava ao ser saqueado (escudo) ou quando o flag expira (lazy no `getInventory`). UI:
   badge 🔒 PvP no item. UI: banner de exposto/protegido (`GET /api/zones/pvp-status`) +
   resumo do raid no battle log. **Bug corrigido de quebra:** `collect` recarrega o `player` como *managed* (o detached
   do controller, salvo 2x num raid-win, dava `OptimisticLockException`). 466 testes verdes (TC-217..220 cobrem pool de
   flag, flag-on-farm e raid ponta-a-ponta).
3. **Fase 3 — Arena instantânea.** ✅ **FEITA.** A arena era "assíncrona" só na aparência: o `startFight`
   já simulava e decidia tudo, e o `finishesAt=now+60s` era um atraso artificial antes do `collect` aplicar
   recompensa/rank. Agora `startFight` **resolve e aplica tudo numa chamada** (bronze, rank do desafiante +
   oponente, V/D, HP/KO, desgaste) e o `POST /api/arena/fight` retorna o **resultado completo** (`won`,
   `opponent`, `goldEarned`, `rankChange`, `log`). Removidos `collectResult` + `POST /{id}/collect` + o
   timer/etapa-de-collect do front (mostra o resultado direto). Gate: estamina (25) + limite diário (5/10 VIP).
   `startFight` recarrega o player como *managed* (mesmo fix de lock da Fase 2). 464 testes verdes.

Cada fase: verde (full suite) + docs + commit. **Plano concluído (Fases 1–3).**

## Riscos / pontos de atenção
- **Perder item equipado** é hardcore — mitigado por Templo (guardar) + Stash + escudo. Acompanhar o "feel".
- **Defesa offline:** o flagged offline é alvo por 1h — o escudo pós-derrota + teto (1x) limitam o estrago.
- **População:** o NPC flagged garante PvP no início; quando houver base real, priorizar players.
- **Remoção de timers** mexe no fluxo de quest/trabalho/coleta (start+collect → ação única) e na UI (sem countdown).
- **Guerra de guild** segue agendada (não confundir com "timer de atividade").

*Decisões travadas 2026-06-04. Próximo: implementar a Fase 1 (após ok do dono).*
