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
2. **Fase 2 — PvP de Zona com Flag.** Campos de flag/escudo; flag ao farmar zona PvP; encontro ao entrar;
   roubo de loot (bag+equip não-protegidos; stash/templo imunes); escudo pós-derrota; NPC flagged de preenchimento.
   Remove a emboscada-no-collect atual.
3. **Fase 3 — Arena instantânea.** Converter a arena assíncrona (timer 1min) em duelo instantâneo por rank.

Cada fase: verde (full suite) + docs + commit.

## Riscos / pontos de atenção
- **Perder item equipado** é hardcore — mitigado por Templo (guardar) + Stash + escudo. Acompanhar o "feel".
- **Defesa offline:** o flagged offline é alvo por 1h — o escudo pós-derrota + teto (1x) limitam o estrago.
- **População:** o NPC flagged garante PvP no início; quando houver base real, priorizar players.
- **Remoção de timers** mexe no fluxo de quest/trabalho/coleta (start+collect → ação única) e na UI (sem countdown).
- **Guerra de guild** segue agendada (não confundir com "timer de atividade").

*Decisões travadas 2026-06-04. Próximo: implementar a Fase 1 (após ok do dono).*
