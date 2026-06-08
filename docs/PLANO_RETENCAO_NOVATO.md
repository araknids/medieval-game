# PLANO — Retenção do Novato (Daily Reward + Buff de Novato + Work Idle)

> **Status:** ✅ **Design aprovado — NÃO IMPLEMENTAR AINDA.**
> ⏸️ **BLOQUEADO:** outra aba/sessão do Claude está trabalhando em outro assunto neste mesmo repositório.
> **Esperar essa outra sessão terminar** antes de começar a codar qualquer coisa daqui (pra evitar conflito
> de arquivos / merge sujo). Quando liberar, seguir a "Ordem de implementação sugerida" abaixo.
>
> **Objetivo:** Atacar o **primeiro penhasco de estamina** do jogador recém-chegado — o momento em que
> ele queima os 100 de estamina em ~10–20 min de cliques e bate num muro de ~1h de regen, fechando a aba.
> Três features compõem o pacote de retenção inicial.

---

## Contexto / Problema

Um player novo nasce com **100 estamina, level 1, 50 prata e gear inicial**. As missões custam 5–15 estamina
cada; ele zera a estamina rápido. Hoje, quando zera, **quase nada do que sobra é interessante pra um level 1**
(forja sem materiais, loja com 50 prata, leilão depende de outros players, battery loop de peixe é circular e
invisível). Resultado: ele não volta.

Este plano cria **o que fazer agora** (Work idle) **+ um motivo pra voltar** (daily reward) **+ menos espera nos
primeiros dias** (buff de novato).

---

## Feature 1 — Trabalho Idle em Tempo Real + Trava de "Ocupado"

### Decisão de design (confirmada com o dono do jogo)
O Trabalho **deixa de ser instantâneo** e vira a **atividade idle** do jogo: o jogador escolhe a duração
(1h/2h/6h/12h), o personagem fica **trabalhando em tempo real** e, quando o tempo passa, ele **coleta** a
recompensa. **Enquanto trabalha, fica BLOQUEADO** de combate/missão/zona/arena/torre/guerra.

> ⚠️ **Reversão arquitetural deliberada do [SEM_TIMER]:** o modelo "tudo instantâneo, sem busy" (removido em
> 2026-06-05) passa a ter **uma exceção: o Trabalho**. As atividades de aventura (zona/arena/torre/missão)
> continuam instantâneas e gated por estamina; o Trabalho é o único timer/idle. Atualizar o CLAUDE.md
> ([SEM_TIMER]) pra documentar a exceção.

### ⭐ Decisão derivada — Trabalho idle **NÃO custa estamina** (recomendado)
O ponto da feature é dar o que fazer **quando o jogador está SEM estamina**. Se o trabalho custasse 60 estamina
pra começar, o novato sem estamina **não conseguiria iniciar** — matando a feature. Portanto:

- **Custo de estamina do Trabalho = 0.** O "custo" é a **trava** (não pode aventurar) + o **tempo real** de espera.
- O gate deixa de ser estamina e passa a ser **tempo + oportunidade**.

*(Hoje o trabalho cobra `hours × 5` estamina em [WorkService.java:88-99](../backend/src/main/java/com/medieval/game/service/WorkService.java#L88). Esse bloco será removido.)*

### Mudanças no Backend

**`model/WorkSession.java`** — já tem `startedAt`, `finishesAt`, `status`, `hours`, `goldReward`, `xpReward`.
Sem mudança de schema. Muda só o **valor** de `finishesAt`.

**`service/WorkService.java`**
- `startWork(player, workType, hours)`:
  - Mantém validação `1 ≤ hours ≤ 12` ([já existe, L59](../backend/src/main/java/com/medieval/game/service/WorkService.java#L59)).
  - Mantém o guard de sessão única (`findByPlayerAndStatus(IN_PROGRESS)`).
  - **Remove** o bloco de cobrança de estamina.
  - `finishesAt = LocalDateTime.now().plusHours(hours)` (era `now().minusSeconds(1)` — [L108](../backend/src/main/java/com/medieval/game/service/WorkService.java#L108)).
- `collectWork(player, sessionId)`:
  - **Passa a exigir** `session.isReadyToCollect()` (agora `now >= finishesAt` só é verdade depois do tempo).
    Se não estiver pronto → erro "Work not finished yet".
- `cancelWork` — mantém (prorata por horas decorridas; serve pra cancelar cedo e se destravar).
- **Novo:** `boolean isWorking(Player player)` → existe `WorkSession IN_PROGRESS` com `!isReadyToCollect()`
  (i.e., trabalhando agora, ainda não terminou). Usado pela trava.

**Trava cruzada ("ocupado") — novo guard em cada atividade de aventura:**
Antes de iniciar, chamar `workService.isWorking(player)` e, se true, lançar `LocalizedException`
("You are working — finish or cancel your job first."). Pontos:
- `ZoneService.enter()` (cobre coleta **e** combate de zona)
- `ArenaService.fight()`
- `TowerService.initTowerRun()` (ou onde inicia a subida)
- `KingdomService` (iniciar/resolver missão)
- `GuildWarService.attack()`
- `ClassChangeService` (Path Trial — é combate)

> **Permanecem liberados enquanto trabalha** (são o "o que fazer enquanto espera"): loja, forja, leilão, templo,
> inventário, mail, gestão de guilda, daily reward, **consumir peixe**. Só as ações de **aventura ativa** travam.

**`WorkController.java`** — endpoints já existem (`/start`, `/current`, `/{id}/collect`, `/{id}/cancel`,
`/jobs`). O `/start` **deixa de auto-coletar no front** (ver Frontend). Sem mudança de assinatura.

### Mudanças no Frontend (`app.js` + `index.html`)
- **Botões de duração:** trocar o botão fixo de 2h ([app.js:1897](../backend/src/main/resources/static/app.js#L1897))
  por um loop de `[1, 2, 6, 12]`. Cada botão mostra `Xh` + recompensa de bronze (**sem ⚡ estamina agora**).
- **`startWork()`** ([app.js:1912](../backend/src/main/resources/static/app.js#L1912)): **remove** o
  `collectWork()` automático. Em vez disso, abre a tela de progresso (`openWorkProgress`).
- **Tela de progresso** (`work-progress` já existe no [index.html](../backend/src/main/resources/static/index.html)):
  countdown em tempo real (`secondsRemaining` do `WorkResponse`), botão **Collect** (habilitado só quando
  `readyToCollect`), botão **Cancel** (coleta parcial). Atualiza o countdown via `setInterval`.
- **Indicador global:** quando trabalhando, mostrar um aviso/lock leve nas abas de aventura (ou deixar o backend
  rejeitar com a mensagem). Mínimo viável: backend rejeita + toast amigável.
- **i18n:** novas strings (`work.duration.1h`, `work.in_progress`, `work.locked_msg`, etc.) em PT + EN.

### Balanceamento (placeholders)
Recompensa já escala `goldPerHour × hours × bônus de profissão`. 12h de idle = payout gordo de graça (só
oportunidade). Números são **placeholders pra tuning** — provável reduzir `goldPerHour` agora que não custa
estamina e rende ao longo de horas reais.

### Notificação (opcional, v1.1)
Email "seu trabalho terminou, volte coletar" via Brevo — reforça o gancho de retorno. Fora do v1.

---

## Feature 2 — Buff de Novato (estamina **+ HP** enchem em 15 min, primeiros 3 dias)

### Design
Nos **primeiros 3 dias após criar a conta**, tanto **estamina** quanto **HP** regeneram **100% em 15 min**
(em vez de 60 min). Sessões iniciais mais longas → o jogador se fisga antes do muro.

- Janela: `createdAt + 3 dias` (o campo `createdAt` **já existe** em
  [Player.java:186](../backend/src/main/java/com/medieval/game/model/Player.java#L186)).
- Regen: 15 min dentro da janela, 60 min fora.
- **Sem coluna nova no banco** — derivado de `createdAt`.

### Mudanças no Backend

**`model/Player.java`** — fórmula de estamina ([getCalculatedStamina L89-93](../backend/src/main/java/com/medieval/game/model/Player.java#L89)):
```java
public int staminaRegenMinutes() {
    return Duration.between(createdAt, LocalDateTime.now()).toDays() < 3 ? 15 : 60;
}
public int getCalculatedStamina() {
    long minutes = Duration.between(staminaUpdatedAt, LocalDateTime.now()).toMinutes();
    int regen = (int) (minutes * 100.0 / staminaRegenMinutes());   // ← usa a janela
    return Math.min(100, currentStamina + regen);
}
```
Também ajustar `getMinutesToFullStamina()` ([L95-99](../backend/src/main/java/com/medieval/game/model/Player.java#L95))
pra usar `staminaRegenMinutes()`.

**HP (`model/Warrior.java`, [getCalculatedHpPercent L65-69](../backend/src/main/java/com/medieval/game/model/Warrior.java#L65)):**
mesma fórmula, mas o HP fica no `Warrior`, que **não tem `createdAt`**. ⚠️ **Ponto a resolver na implementação:**
- Verificar a navegação `Warrior → Player` (FK). Se existir e os reads de HP forem **transacionais** (são:
  `WarriorService`, `WarriorController.buildResponse`), usar `getPlayer().getCreatedAt()` dentro do getter.
- Cuidado com `open-in-view=false` (prod): acessar `player` lazy fora de transação quebra. Se houver call site
  de HP fora de `@Transactional`, a alternativa é **passar a janela por parâmetro** (`getCalculatedHpPercent(int regenMin)`)
  ou **duplicar `createdAt` no Warrior**. Decidir ao implementar (preferência: nav transacional; fallback: parâmetro).

### Exposição na UI
Mostrar o buff pro jogador (buff que ninguém vê não retém): no `WarriorResponse`, expor
`newbieBuffActive: boolean` + `newbieBuffHoursLeft`. No sidebar/header, badge tipo "✨ Novato: regen rápido
(expira em Xh)". Sem isso o jogador nem percebe a vantagem.

### Edge cases
- Conta criada há > 3 dias: sem buff (esperado).
- **Soft-wipe → resetar `createdAt = now()`** ✅ **(confirmado)** — assim o personagem soft-wipado volta a
  receber o buff de novato por 3 dias (útil pro teste solo + coerente: soft-wipe "renasce" o personagem).
- Descontinuidade exata na virada dos 3 dias é irrelevante (regen recalcula no momento da leitura).

---

## Feature 3 — Recompensa de Login Diária (ciclo de 7 dias, popup + aba + badge, peixe de stamina)

### Design
Ciclo de **7 dias** com recompensa escalando (dia 1 → dia 7). **Faltar um dia zera o streak** (volta ao dia 1).
Recompensa = **peixe de stamina** (battery loop), melhor a cada dia, com bônus no dia 7. Ensina o jogador a
**estocar peixe** pra esticar a sessão.

Peixes de stamina disponíveis ([ResourceType.java](../backend/src/main/java/com/medieval/game/enums/ResourceType.java)):
`SMALL_FISH` (+5), `SALMON` (+8), `TUNA` (+11), `SHARK` (+14), `LEGENDARY_FISH` (+18).

**Tabela do ciclo (placeholders pra tuning):**
| Dia | Recompensa |
|-----|------------|
| 1 | 2× Small Fish |
| 2 | 3× Small Fish |
| 3 | 2× Salmon |
| 4 | 3× Salmon |
| 5 | 2× Tuna |
| 6 | 3× Tuna |
| 7 | 1× Legendary Fish + bônus de bronze |

### Mudanças no Backend

**`model/Player.java`** — 2 campos novos:
```java
@Column(columnDefinition = "date")            private LocalDate lastDailyClaimDate;
@Column(columnDefinition = "integer default 0") private int dailyStreak = 0;
```
**Migração** (`config/SchemaMigrator.java`, seguindo o padrão de
[patchPlayerVipColumns L419-435](../backend/src/main/java/com/medieval/game/config/SchemaMigrator.java#L419)):
```sql
ALTER TABLE players ADD COLUMN IF NOT EXISTS last_daily_claim_date date;
ALTER TABLE players ADD COLUMN IF NOT EXISTS daily_streak integer NOT NULL DEFAULT 0;
```

**`service/DailyRewardService.java` (novo):**
- `status(player)`: `canClaim` (= `lastDailyClaimDate == null || lastDailyClaimDate < hoje`), `streak` atual,
  preview da recompensa de hoje, e a tabela dos 7 dias com o dia atual destacado. Sem scheduler — reset por
  comparação de data (igual aos contadores diários do
  [VipService L99-106](../backend/src/main/java/com/medieval/game/service/VipService.java#L99)).
- `claim(player)`:
  - Se já reivindicou hoje → rejeita.
  - Novo streak: se `lastDailyClaimDate == ontem` → `streak+1` (cicla 1→7); senão → `streak = 1`.
  - Entrega a recompensa do dia (via `gatheringService.addResource(player, fish, qty)` —
    [L65-78](../backend/src/main/java/com/medieval/game/service/GatheringService.java#L65)).
  - `lastDailyClaimDate = hoje`. Salva.
- **Bag cheia → mandar pelo mail** ✅ **(confirmado).** O que não couber na bag vai por correio; o claim do
  dia **sempre** sucede (nunca trava por bag cheia). **Implica trabalho extra:** o mail hoje só carrega
  `InventoryItem` ([MailService.sendItemMail L144-175](../backend/src/main/java/com/medieval/game/service/MailService.java#L144)),
  **não** `ResourceType`. Precisa de **mail de recurso**:
  - Adicionar campos de recurso no `Mail` (`resourceType` + `resourceQty`) + migração (`SchemaMigrator`).
  - Novo `MailService.sendResourceMail(recipient, reason, ResourceType, qty)`.
  - Novo claim de recurso (`MailController` + `MailService.claimResource`) que faz `addResource` ao abrir
    (e respeita o espaço da bag — se ainda cheia, o mail continua lá pra reivindicar depois).
  - Frontend do mail: renderizar/claim de anexo de recurso (hoje só trata anexo de item).
  - **Reaproveitável:** esse mail de recurso serve pra outras recompensas futuras (não só a daily).
  - *No claim da daily:* tenta `addResource` o que couber na bag; o restante vai via `sendResourceMail`.

**`controller/DailyRewardController.java` (novo):**
- `GET /api/daily-reward/status` → `{ canClaim, streak, todayReward, days[] }`.
- `POST /api/daily-reward/claim` → `{ rewardGiven[], newStreak }`.

### Mudanças no Frontend
- **Aba** no nav ([index.html:66-77](../backend/src/main/resources/static/index.html#L66)):
  `🎁 Daily` → `goTo('daily-reward')` → `loadDailyReward()`. Adicionar case no
  [goTo() app.js:475-489](../backend/src/main/resources/static/app.js#L475).
- **Tela** `<section id="loc-panel-daily-reward">`: calendário de 7 dias (dia atual destacado, dias passados
  marcados, futuros bloqueados) + botão **Claim**. Modelar no padrão de `loadMail()`/`renderMailPanel()`
  ([app.js:2964+](../backend/src/main/resources/static/app.js#L2964)).
- **Popup no login:** em `enterGame()`/`loadWarrior()` ([app.js:253-259](../backend/src/main/resources/static/app.js#L253)),
  checar `GET /api/daily-reward/status`; se `canClaim`, abrir modal automático com a recompensa + botão Claim
  (reusa o padrão de `showCollectModal`).
- **Badge** no botão do nav quando `canClaim` (igual ao badge de mail, `updateMailBadge`).
- **Pós-claim:** modal de recompensa, atualiza badge, atualiza inventário/warrior.
- **i18n:** strings PT + EN (`daily.title`, `daily.claim`, `daily.day`, `daily.streak`, etc.).

### Edge cases
- **Soft-wipe:** zerar `lastDailyClaimDate` + `dailyStreak`.
- Fuso/virada de dia: usar a mesma referência de data dos contadores diários existentes (conferir UTC vs local
  no VipService pra manter consistência).

---

## Interações entre as features
- **Buff novato + Work idle:** sinergia — nos 3 primeiros dias o jogador faz aventuras (regen rápido) **e** deixa
  o personagem trabalhando idle quando para de jogar.
- **Daily + battery loop:** a daily entrega peixe → ensina a estocar/consumir estamina → sessões mais longas.
- **Work idle + trava:** enquanto trabalha, o jogador ainda pode forjar/comprar/abrir daily (downtime produtivo).

---

## Ordem de implementação sugerida (do mais fácil ao mais complexo)
1. **Buff de novato** (estamina trivial; HP precisa resolver o acesso a `createdAt`). Pequeno, alto valor.
2. **Botões de duração do Work** + **modelo idle/trava** (front + backend; reverte SEM_TIMER → atualizar CLAUDE.md
   + cuidado com a trava em vários services + testes de "ocupado").
3. **Daily reward** (maior: service + controller + migração + **mail de recurso** + tela + popup + badge + i18n).
   Também ajustar o **soft-wipe** (zerar daily + resetar `createdAt`).

Cada uma é commitável de forma independente.

---

## Decisões — TODAS CONFIRMADAS ✅
1. **⭐ Trabalho idle custa 0 estamina** — gate vira tempo real + trava de aventura. ✅
2. **Soft-wipe reseta `createdAt = now()`** — re-concede o buff de novato (3 dias). ✅
3. **Daily com bag cheia → manda pelo mail** — requer construir **mail de recurso** (ver Feature 3). ✅
4. **Números** (recompensa do work pós-remoção da estamina, tabela dos 7 dias) ficam como placeholders pra tuning. ✅

> Nada mais pendente de decisão — assim que a outra sessão do Claude liberar o repo, pode implementar direto
> na ordem sugerida acima.

---

## Impacto em docs existentes
- **CLAUDE.md [SEM_TIMER]:** documentar a exceção do Trabalho (timer real + estado "ocupado").
- **CLAUDE.md:** nova seção `[RETENCAO_NOVATO]` ou `[DAILY]`/`[BUFF_NOVATO]` resumindo as 3 features.
- **docs/PLANO_SEM_TIMER_PVP.md:** nota de que o Trabalho saiu do modelo instantâneo.
