# Plano — Quests Interativas (diálogo + escolha + roll d20)

> Design alinhado com o dono em 2026-06-05. **Planejado, não implementado.**
> Regra: discutir + documentar ANTES de codar.

## Visão
As **daily quests atuais** ganham um **diálogo de escolha** (estilo livro-jogo) inserido entre o
start e o resultado. Como tudo é instantâneo, o diálogo entra natural no meio:

```
clica Iniciar → [start] devolve história + opções
              → MODAL DE DIÁLOGO (história + botões de escolha)
              → escolhe → [collect {opção}] resolve o efeito
              → MODAL DE RESULTADO (vitória/derrota/recompensa — o de hoje)
```

Reaproveita combate (`BattleSimulator`), recompensa, daily-lock e o modal de resultado. O **novo** é:
o diálogo, o ramo da escolha, e o **roll de atributo d20**.

## Decisões (forks aprovados — finais 2026-06-05)
1. **Escopo:** **TODAS as 30 dailies** (`KingdomQuestType`) viram interativas — narrativa reescrita
   (curta, **em inglês**) + 2-3 opções cada. O motor mantém o diálogo **opcional por quest** (quest
   sem entrada no registry cai no fluxo antigo com encontro aleatório), mas o MVP cobre as 30.
   Profundidade rasa por ora (lore não fechada).
2. **Mecânicas:** **combate-ou-paz** (risco) + **teste de atributo** com **roll d20 estilo D&D**.
3. **Profundidade:** **decisão única** (1 diálogo → 1 escolha → resolve). Sem árvore (futuro).
4. **VIP:** o **instant-start é removido** (não cabe com escolha). No lugar: VIP pode fazer cada
   daily **1× a mais** por janela (não-VIP 1×, VIP 2×) — daily-lock vira **contagem**.
5. **Idioma:** narrativas/labels **em inglês** (i18n pro PT depois). Recompensas das opções =
   **multiplicadores** da reward base da quest (auto-escala + balance).

## Modelo de conteúdo (code-defined, registry)
Registry `InteractiveQuests`: `Map<KingdomQuestType, QuestDialog>`. Quest sem entrada = não-interativa.

```
QuestDialog(String intro, List<QuestOption> options)              // intro = narrativa de abertura
QuestOption(String id, String label, String hint, Outcome outcome) // hint ex.: "DEX 14+"

sealed interface Outcome:
  Peaceful(long bronze, long xp, int dropChance, String narrative)        // resolve em paz
  Combat(MonsterSpec monster, long bronze, long xp, int dropChance,       // dispara luta;
         String winNarrative, String loseNarrative)                       // vence=recompensa, perde=KO
  Check(Attribute attr, int dc, Outcome onSuccess, Outcome onFail)        // roll d20; ramifica
```

- `Check` é **recursivo**: rola o d20 e resolve pra `onSuccess`/`onFail` (que são Peaceful ou Combat).
  Ex.: "Roubar (DEX 14)" → passou: `Peaceful(+400)` | falhou: `Combat(guarda)`.
- Conteúdo no código (como `KingdomQuestType`). MVP: **~5 quests interativas** (1 por reino) pra
  validar; arquitetura suporta adicionar mais sem mexer no fluxo.

## Roll de atributo (d20, igual ao combate)
Coerente com o d20 do `BattleSimulator` (to-hit = d20 + bônus vs DC):

```
roll  = 1d20 + mod(attr)          mod(attr) = floor(attr / 4)   // STR cap 60→+15, DEX 40→+10…
sucesso se roll >= DC             (nat 1 sempre falha; nat 20 sempre passa — flavor D&D)
DC tiers: Fácil 10 · Médio 14 · Difícil 18 · Épico 22           // por opção, tunável
```
O modal mostra o roll: **"🎲 14 + 8 (DEX) = 22 vs DC 14 — Sucesso!"**. Faz os atributos importarem
fora do combate (build de DEX abre fechaduras, build de STR arromba, INT/LUK pra outros).

## Fluxo backend
- `KingdomService.startQuest`: **inalterado** (cria IN_PROGRESS, daily-lock, guard de "uma em progresso").
- `KingdomController` start: se a quest tem diálogo → resposta inclui
  `interactive:true, dialog:{intro, options:[{id,label,hint}]}` (sem revelar os outcomes).
- `KingdomService.collectQuest(player, questId, optionId)`: ganha `optionId` (opcional).
  - **Interativa:** exige `optionId`; resolve o `Outcome` da opção (recursivo p/ `Check`):
    Peaceful → recompensa; Combat → `BattleSimulator` (vence=recompensa, perde=KO/0); Check → rola
    d20 e segue onSuccess/onFail. **Ignora o `monsterChance` aleatório** (a escolha manda).
  - **Não-interativa:** comportamento atual (rola `monsterChance`).
- `KingdomController` collect: aceita `optionId` no body; resposta acrescenta `roll` (attr/rolled/mod/
  dc/passed) quando houve check + os campos atuais (narrativa, combate, recompensa, drop).
- **VIP:** instant-start removido (endpoint + `vipInstantQuestsToday` + `consumeInstantQuest` + botão).
  `isQuestDoneThisPeriod` vira contagem: `count(COLLECTED na janela) >= limite`, limite = `isVip()?2:1`.

## Fluxo frontend
- `startKingdomQuest` → `/start`. Se `r.interactive` → `showQuestDialogModal(kingdom, questId, r.dialog)`.
  Senão → auto-collect atual.
- `showQuestDialogModal`: modal (estilo showCollectModal) com a narrativa + um botão por opção
  (mostra o `hint`, ex.: "🎲 DEX 14+"). Clicar → `collectKingdomQuest(kingdom, questId, optionId)`.
- `collectKingdomQuest(kingdom, questId, optionId?)` → `/collect {optionId}` →
  `showQuestResultModal(r)` estendido: se houve check, mostra a linha do **roll** antes do desfecho.

## O que NÃO muda
- Dailies sem diálogo: idênticas (encontro aleatório por `monsterChance`).
- Daily-lock 12h, rotação 2-de-6, guard "uma quest em progresso", recompensa por reino, drops.
- Combate (BattleSimulator/d20), modal de resultado, narrador.

## Testes
- Resolução de cada tipo de Outcome: Peaceful (recompensa exata), Combat (vence/perde), Check
  (sucesso → onSuccess, falha → onFail) — com guerreiro forte/fraco e atributo alto/baixo p/
  forçar os ramos. `nat 1`/`nat 20` determinísticos testáveis injetando o RNG.
- Start de quest interativa devolve `interactive:true` + opções (sem outcomes vazando).
- Collect de interativa **exige** `optionId` (sem → 400); não-interativa ignora `optionId`.
- Daily-lock continua valendo (não dá pra refazer a interativa na mesma janela).

## Futuro (fora do MVP)
- **Ramificação** (árvore de cenas) — `Check`/opções apontando pra novos `QuestDialog`.
- **Story quests** (a fase futura): cadeia narrativa que libera itens/skins, exige drops de monstro;
  reaproveita este motor de diálogo + outcomes.
- Mais conteúdo interativo (cobrir as 30 dailies aos poucos).
