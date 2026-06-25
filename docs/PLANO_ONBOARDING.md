# PLANO — Onboarding do Novato (Camada A: chegada guiada · Camada B: deveres do recruta) [ONBOARDING]

> **Status:** 🚧 EM IMPLEMENTAÇÃO (2026-06-24, build noturno) — dono valida de manhã.
> **Objetivo:** matar o "começo largado" (novato cai no jogo sem contexto nem direção) — o assassino do
> minuto 1–10. Itch lança **domingo (2026-06-29)**. Consolida design espalhado em 3 docs:
> `PLANO_QUESTS_LORE.md` (briefing da Coroa de Aravok, **texto já escrito**), `AUDITORIA_USABILIDADE_UI.md`
> §8.1 (fluxo de 60s) e os endpoints `onboardingSeen` que já existem no backend.

## Diagnóstico (confirmado pelo dono jogando)
O cliente **Godot** nunca recebeu a tela de chegada. O backend tem a flag `onboardingSeen` + endpoints
(`GET/POST /api/warrior/onboarding[/seen]`); o **web (legado)** tinha modal+coachmark; **o Godot não tem nada**.
Por isso o novato cai no dashboard sem briefing nem 1ª ação clara.

---

## Camada A — Chegada guiada (prioridade; baixa-risco; quase só UI Godot)

Fluxo (do §8.1, "fisgar em 60s"): login → checa onboarding → **overlay de boas-vindas** (briefing) com 1 CTA
dourado → leva ao **World com a 1ª quest destacada** (coachmark "Comece aqui") → o novato fecha **um loop
inteiro** (lutar → loot → equipar → subir nível) guiado uma vez.

**Briefing (já escrito em `PLANO_QUESTS_LORE.md`, EN é a língua de UI):**
> *"Coroa de Aravok was the jewel of the new world — gold in the hills, fish in the tides… Then the beasts came,
> and the King shut himself in his tower and did not come down… We begged the Old Crown for soldiers. They sent
> us recruits. They sent us **you**. Earn your place, climb the King's tower, and bring him home."*

**Implementação (Godot):**
- `net/BackendClient.gd` — 2 métodos novos: `onboarding_status()` (GET) e `onboarding_seen()` (POST), seguindo o
  padrão de `_request` autenticado já usado no arquivo.
- `ui/Shell.gd` — em `_initial_load()` (após carregar warrior/inventário, **antes** de `_show_dashboard()`):
  `await` o status; se `!seen`, mostrar o overlay.
- **Overlay** (helper novo, ex.: `ui/Onboarding.gd` `class_name OnboardingWelcome` ou função em `UiKit`):
  ColorRect dim + `UiKit.card(GOLD)` + Label autowrap (briefing) + `UiKit.action_big("Begin", cb)`. No CTA:
  `POST /seen`, fecha, e navega pro World (`Shell._open("World")`) sinalizando "destacar 1ª quest".
- **Coachmark** em `ui/World.gd` `_quest_card()`: na **primeira quest com `canStart`**, aplicar borda/glow
  dourado (StyleBoxFlat shadow GOLD) + rótulo "Start here". Some quando a quest é iniciada. (Sem sistema de
  coachmark genérico hoje — é um realce simples, não um framework.)
- **i18n:** strings novas em `i18n/Lang.gd` (chave PT → valor EN, padrão do arquivo).

**Sem mudança de backend** — os endpoints já existem.

---

## Camada B — Deveres do Recruta (a visão do dono: NPC pede item → XP+gold)

3 NPCs dão **uma quest única cada** (não-diária): a **Guarda do Training Hall**, o **Padre Anselmo** (Templo) e
o **Lojista**. Cada um **pede um item/recurso**; o jogador **entrega** e recebe **XP + gold** (a Guarda também dá
uma **arma inicial**). Isso cria direção ("consiga X e traga"), ensina os subsistemas e é um **sink** pro lixo
inicial.

**Decisão de arquitetura (do mapa do backend — menor risco p/ deadline):** **NÃO** reusar o motor de quest de
reino (`KingdomService` é acoplado a estamina/rotação 12h/drops/Luna). Em vez disso, serviço leve:

- `model/Player.java` — 3 flags (padrão do `onboardingSeen`):
  `starterGuardDone`, `starterPriestDone`, `starterShopDone` (default false).
- `config/SchemaMigrator.java` — `patchStarterQuestColumns()` (`ALTER TABLE players ADD COLUMN IF NOT EXISTS …
  boolean NOT NULL DEFAULT false`), chamado no `migrate()`.
- `service/StarterQuestService.java` (novo) — `status(player)` + `turnIn(player, which)`:
  - valida flag (já feito → `LocalizedException("error.starter_already_done")`);
  - valida posse do recurso pedido (`GatheringService.resourceQuantityTotal`) e **consome** (`removeResourceTotal`,
    bag+stash, mesmo padrão do custo da Trial [TRIAL_CUSTO]); se faltar → `LocalizedException`;
  - concede recompensa: `WarriorService.addExperience` + `player.addBronzeAmount` (+ Guarda: `InventoryService.make`
    de uma arma inicial; bag-cheia → `MailService.sendItemMail`);
  - seta a flag, salva.
- `controller/StarterQuestController.java` (novo) — `GET /api/starter-quests` (status + o que cada um pede e dá) +
  `POST /api/starter-quests/{guard|priest|shop}/turn-in`. Auth = `(Long) auth.getPrincipal()`.

**Itens pedidos (placeholders robustos — recurso básico que o 1º loop/coleta já produz; tunar depois):**
| NPC | Pede (placeholder) | Dá |
|-----|--------------------|-----|
| Guarda (Training Hall) | 2× recurso básico de combate (ex.: Monster Core / Beast Hide) | arma inicial + XP + gold |
| Padre Anselmo (Templo) | 1× peixe (ex.: Small Fish) | XP + gold |
| Lojista (Loja) | 2× recurso de coleta básico | XP + gold |

> Escolha de recurso = **placeholder**; critério: tem que ser obtível no **primeiro loop** (senão a quest
> trava). Confirmar/tunar com o dono. UI mostra **have/need** e desabilita o botão até ter — isso **direciona**
> ("vá conseguir isto") em vez de confundir.

**UI (Godot):** painel novo `ui/StarterQuests.gd` (ou seção no dashboard/World) listando os 3 com flavor do NPC,
have/need, e botão **Turn in** (habilitado só com a quantidade). i18n PT+EN. *(v1 = painel único; colocar o card
em cada tela de NPC = melhoria futura.)*

---

## Soft-wipe (pra o dono re-validar de manhã)
`service/MaintenanceService.resetPlayer()` — **resetar `onboardingSeen=false`** (hoje **não** reseta!) +
`starterGuardDone/Priest/Shop=false`. Assim um soft-wipe re-arma todo o onboarding.

## Testes
- Backend: `StarterQuestServiceTest` (turn-in feliz, sem recurso, já-feito, soft-wipe reseta) + `mvn -o clean test`
  verde antes do push (mudança de assinatura compartilhada → clean test, não só compile).
- Godot: validação manual do dono de manhã (não há harness headless prático aqui). Código segue padrões do
  `UiKit`/`BackendClient`/`Lang` pra minimizar risco de runtime.

## Commit/deploy
Commit **cirúrgico** (só arquivos do onboarding; **não** os `.res` de WIP na árvore) + push (deploy Railway p/ o
dono validar em prod, coerente com a postura de teste solo). Branch = `main` (convenção do repo).

## Ordem
A (Godot, sem backend) → B backend (testável) → B Godot UI → soft-wipe → `mvn clean test` → commit/push → relatório.

## v2 — Descoberta no NPC + diário no topbar (2026-06-24, pós-grilling)
A aba "Deveres" no nav **saiu** (renderizava como cabeçalho morto: sem ícone `starterquests` o `_nav_item`
virava texto solto). Novo modelo (fundação do sistema de quest geral, não só onboarding):

- **Estado por quest:** `available` (NPC oferece) → `accepted` (entra no diário) → `done`. Backend: 3 flags
  `accepted` novas no `Player` (além das `done`); `state(player,duty)` deriva. Endpoint novo
  `POST /api/starter-quests/{which}/accept`; `turn-in` passa a **exigir `accepted`**. `status` retorna
  `state` + `npcScreen` (Work/Temple/Shop) por quest.
- **Descoberta no NPC:** cada quest tem um `npcScreen` (Guarda→Work/Training, Padre Anselmo→Temple,
  Lojista→Shop). A tela do NPC, se tiver quest `available`, mostra uma **oferta** (NPC + flavor + botão
  **Aceitar** — só aceitar, sem recusar [grilling R2]) → diálogo → `accept` → vai pro diário.
- **Badge no nav:** "!" amarelo (reusa `quest_alert`/`warning`) no item de nav do NPC enquanto a quest dele
  está `available`. Some ao aceitar.
- **Diário no topbar:** botão novo ao lado do Mail (ícone `quest_alert`, padrão dos botões do topbar) abre o
  painel `StarterQuests` (reaproveitado como **diário**: lista as quests `accepted`/`done`, com have/need +
  **Entregar**). Badge no botão quando há quest pronta p/ entregar.

> Por que vale o estado "aceita" (grilling R3): quests futuras (não-onboarding) serão **trackeadas igual** —
> isto é a fundação. Decisão consciente do dono mesmo a 5 dias do itch (grilling R5: prioridade dele agora).

## Em aberto p/ o dono (de manhã)
- Magnitudes (XP/gold/qty pedida) = placeholders.
- Recursos pedidos por cada NPC (têm que ser early-game).
- Camada B: painel único vs card em cada tela de NPC (v1 = painel único).
