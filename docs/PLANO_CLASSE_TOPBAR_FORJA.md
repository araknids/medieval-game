# PLANO — Topbar por evento + custo de Peças na Forja + Quest de Classe (Lv10)

> Grill 2026-06-25. Decisões travadas com o dono. Implementar após aprovação.

---

## #1 — Topbar atualiza por EVENTO (badge do correio preso)  *(decisão: por evento)*

**Bug:** abrir/ler o correio zera `unreadMail`, mas o "!" do correio no topbar continua. O `update_topbar`
só roda quando uma tela manda o warrior (`UiKit.set_wallet → topbar_sink`); ler correio (sem trocar de tela
nem re-buscar `/api/warrior`) não atualiza o topbar → badge stale (vale p/ moedas/HP também).

**Fix (event-driven):**
- Novo `Shell.refresh_topbar()` = re-busca `/api/warrior` → `update_topbar(w)`; exposto via sink
  `UiKit.topbar_refresh := Callable()` (set no `_ready` do Shell, igual aos outros sinks).
- `Mail.gd`: depois de **ler/coletar/claim** (qualquer ação que muda `unreadMail`), chama
  `UiKit.topbar_refresh.call()` → o "!" some na hora.
- Reusável por qualquer tela cuja ação mexa no topbar (correio é o buraco atual; moedas pós-compra/venda já
  atualizam via `set_wallet`).

---

## #2 — Forja mostra o custo em PEÇAS (craft de arma + reparo)  *(decisão: mostrar)*

**Bug:** o backend já cobra Peças (`craftWeaponScrap` no craft de arma, `repairScrapCost` no reparo), mas a
UI mostra só o bronze → o jogador não sabe que precisa de Peças até falhar.

**Fix:**
- **Craft de arma:** expor `scrapCost` na DTO de receita (`SmithingController /recipes` = `craftWeaponScrap(recipe)`;
  0 p/ não-arma). `Forge._craft_card`/`_craft_dialog`: chip **`[ícone res_scrap] ×N`** ao lado do bronze
  (vermelho se faltar Peças). Tooltip de craft inclui as Peças.
- **Reparo:** `Forge._maint_dialog` já calcula o custo em bronze client-side; somar um chip de Peças
  (`= max(1, raridade)`, espelhando `repairScrapCost`) ao lado, vermelho se faltar. (Fórmula trivial/placeholder;
  comentário "deve casar com o backend".)

---

## #3 — Quest de ESCOLHER CLASSE no Lv10 (Path Trial)  *(decisão: fluxo em modal + Guardião em batalha 3D)*

> ⚠️ **Achado:** o cliente Godot **não tem NENHUMA UI de classe/Trial** (sem chamadas a `/api/class`). Hoje é
> **impossível** escolher classe no Godot. Então isto é **construir a feature**, guiada por uma quest no molde
> do onboarding. Backend (`ClassChangeService.attemptTrial`, `/api/class`, `/api/class/trial/{path}`) já existe.

**Estado (sem migração):** "Trial disponível" = `warriorClassId == "RECRUIT" && level >= 10` (derivável do
warrior; não precisa flag nova). "Concluída" = `warriorClassId != "RECRUIT"`.

**BackendClient:** `class_info()` (GET `/api/class`) + `class_trial(path)` (POST `/api/class/trial/{path}`).

**Gatilho + guia (molde onboarding):**
- Ao virar RECRUIT Lv10 (detectado no `update_topbar`/level-up): **modal de NPC** (estilo `npc_notice`,
  retrato + fala do Capitão Garrick) — "Você está pronto, recruta. Escolha seu caminho." → botão
  **"Escolher classe"**. Auto **1×/sessão** (como o `_maybe_offer` do onboarding); reabre até escolher.
- **Badge "!"** persistente no item **Personagem** (cor própria) enquanto RECRUIT Lv10 — clicar/abrir reabre o fluxo.

**Fluxo em MODAL (sem tela nova):**
1. Modal-seletor: 3 cards **Warrior / Archer / Merchant** — stats-base, descrição curta, custo (Monster Core,
   `monsterCoreCost/Have` do `/api/class`) e **"Enfrentar o Guardião de X"** por caminho.
2. Escolher → `class_trial(path)`:
   - **Vitória:** vira a classe (permanente), consome Monster Core; mostra a **batalha 3D** do Guardião
     (BattleReplay, igual Arena/Torre) → relatório → fecha. Quest concluída (badge some).
   - **Derrota:** continua RECRUIT (não consome núcleo); batalha 3D + relatório; pode tentar de novo.
3. **Guardião = batalha 3D** (decisão): reusa `BattleReplay` com o log do `TrialResult`.

**i18n** PT/EN de tudo. Números/textos do backend já são placeholders.

---

## Ordem sugerida
1. **#1 topbar** (pequeno, isolado) — `Shell.refresh_topbar` + sink + Mail.
2. **#2 Forja** (backend expõe scrapCost + UI dos chips).
3. **#3 classe** (BackendClient + modais + BattleReplay do Guardião + gatilho/badge) — o maior.

## Decidido (2026-06-25)
- **Badge "!" da classe: no item Personagem** (nav). ✓
- **Entra no Diário de Missões também** (pra manter track): a `questJournal` (backend) injeta uma entrada
  sintética "Escolha sua classe" (`source:"class"`) quando RECRUIT && level≥10; o Diário (`StarterQuests.gd`)
  renderiza esse card com botão que abre o fluxo de classe. ✓
- Guardião: reusa `BattleReplay` direto.
