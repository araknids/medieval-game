# Auditoria Profunda — Parecer do Conselho

> Auditoria conduzida em **2026-06-03** por quatro revisores especializados independentes
> (Segurança/AppSec, Persistência/Backend, Economia/Balanceamento, Arquitetura/Dívida Técnica).
> Achados que apareceram em **mais de uma auditoria** estão marcados com 🔁 (alta confiança).
>
> **Coluna Status:** `⬜ aberto` · `🔧 em progresso` · `✅ resolvido` · `🕓 adiado`

---

## Veredito geral

Fundação sólida (camadas limpas, moeda centralizada, sem dependências circulares, ~383 testes).
Mas há **três furos que vão para produção e quebram a economia/segurança**, dois deles confirmados
por auditores diferentes. Os 4 sinks econômicos recém-implementados estão conceitualmente bem
calibrados; o problema da economia é **renda escalável sem trava + impressoras de dinheiro**, não os sinks.

---

## 🔴 CRÍTICO

| # | Status | Achado | Auditores | Local | Impacto / Correção |
|---|--------|--------|-----------|-------|--------------------|
| C1 | ✅ | `/api/admin/grant-soulstones` sem proteção de role | 🔁 Segurança + Arquitetura | `controller/AdminController.java:21` | Qualquer jogador logado se concede SoulStones (💎 premium) infinitos. **RESOLVIDO:** `@Profile("dev")` na classe → bean não registra em prod (endpoint 404). |
| C2 | ✅ | Quantidade negativa em `refineOre` imprime bronze + recursos | 🔁 Segurança + Economia | `service/SmithingService.java:80` + `controller/SmithingController.java:85` | `quantity` cru; valor negativo passava nas guardas → `spendBronze`/`removeResource` creditavam. **RESOLVIDO:** validação `1 ≤ quantity ≤ 1000` em `refineOre` + guardas `< 0` em `spendBronze`, `addResource`, `removeResource`. Testes em `ExploitRegressionTest`. |
| C3 | ✅ | Double-collect / double-spend (sem `@Version` ou lock em lugar nenhum) | Persistência | `QuestService`, `WorkService`, `GatheringService`, `ArenaService`, `ZoneService`, `MailService`, `TempleService`, `VipService` | **RESOLVIDO:** `@Version` em `ActiveQuest`, `WorkSession`, `GatheringSession`, `ArenaMatch`, `ZoneActivity`, `Mail` e `Player`. A 2ª transação concorrente falha no commit (`OptimisticLockingFailureException`) → `GlobalExceptionHandler` devolve **409** "tente novamente". Coluna `version` via `@Column(columnDefinition="bigint default 0")` + `SchemaMigrator.patchOptimisticLockVersionColumns`. Testes em `OptimisticLockingTest`. |
| C4 | ✅ | Saldo pode ficar negativo permanente | Persistência | `model/Player.java:46` (`addBronzeAmount`) | Sem `Math.max(0,…)`; no PvP de zona viraria saldo devedor irreversível. **RESOLVIDO:** clamp `Math.max(0, …)` em `addBronzeAmount`. Teste em `ExploitRegressionTest`. |

---

## 🟠 ALTO

| # | Status | Achado | Auditores | Local | Impacto / Correção |
|---|--------|--------|-----------|-------|--------------------|
| A1 | ✅ | Gemas contam na ficha mas **não** no combate | Arquitetura | stats duplicados em `ArenaService:196` / `TowerService:135` / `ZoneService:542` / `WarriorController:70` | **RESOLVIDO:** novo `WarriorStatsService` é a fonte única (base+atributos+itens efetivos+joias), consumido por Arena, Torre, Zona e WarriorController. Gemas agora contam no combate; itens quebrados não contam (nem joias). |
| A2 | ✅ | `open-in-view=false` só em prod | Persistência | `application-prod.properties` | **RESOLVIDO:** `spring.jpa.open-in-view=false` movido para o `application.properties` base → dev/test/prod iguais. Os 392 testes passam com a flag, confirmando que os fluxos testados não acessam lazy fora de `@Transactional`. |
| A3 | ✅ | Renda escalável > sinks no late-game | Economia | `ZoneService.java:217`, `TowerService.java:157` | **RESOLVIDO:** bronze da Zona COMBAT reduzido `level×15 → level×8`; Torre agora cobra taxa de subida `floor×15` (sink escalável recorrente; vitória líquida `floor×25`, derrota custa a taxa). Números fáceis de tunar. |
| A4 | ✅ | Reforja barata demais (re-roll ilimitado) | Economia | `service/SmithingService.java:258` | **RESOLVIDO:** custo `raridade²×200 → raridade³×500` (épico 3.200 → 32.000, ~10×). Frontend e testes atualizados. *Obs.:* piso de stats de itens de loja/craft ainda não preservado no re-roll (candidato a refino). |
| A5 | ✅ | Cura escalável 100% contornável com peixe | Economia | `GatheringService.consumeFish` | **RESOLVIDO:** cura por peixe limitada ao teto de **50%** (`FISH_HP_CAP`); 50→100% exige Templo (sink pago) ou regen. Peixe continua sendo recuperação de emergência + stamina. |
| A6 | ✅ | try/catch em ~60 controllers (handler morto/divergente) | Arquitetura | quase todos os controllers | **RESOLVIDO:** `GlobalExceptionHandler` agora é a fonte única (IllegalArgument/IllegalState → 400, igual ao que os controllers entregavam; `OptimisticLockingFailureException` → 409). Removidos os try/catch redundantes de 16 controllers (~50 métodos, comportamento idêntico, 399 testes verdes). **Mantidos** os catches com lógica própria: `AuthController.login` (401 genérico, A10) e `ZoneController.enter` (logging `[ZONE-ENTER]` de debug). |
| A7 | ✅ | Cron de território não é idempotente | Arquitetura | `TerritoryService.java:128` | **RESOLVIDO:** `TerritoryControl.lastResolvedCycleId` + novo `TerritoryScheduler` (cron 6h **e** catch-up no `ApplicationReadyEvent`) reprocessa ciclos perdidos em downtime/deploy. Idempotente (não recobra), cap de 8 ciclos, guard de 1º boot (não reprocessa histórico). Cada território resolvido em **transação própria** (cross-bean). Testes em `TerritoryCatchUpIntegrationTest`. |
| A8 | 🔧 | Emboscada escreve em entidades de outro jogador sem lock | Persistência | `ZoneService.java:333` | **Data-safe (C3):** `@Version` em `Player`/`ZoneActivity`/`Warrior` converte o lost-update em 409 (rollback), sem corrupção. **Hardening A8:** re-valida que o alvo ainda está `IN_PROGRESS` antes de aplicar (reduz a janela de conflito) + dedup de gravação. **Deferido (deliberado):** a degradação graciosa total (emboscada não falhar o collect do atacante) exige redesenhar a resolução PvP — a emboscada faz transferência de bronze **entre dois jogadores**, então isolar em transação aninhada criaria risco de deadlock na própria linha do atacante. Recomendado como tarefa dedicada de PvP. |
| A9 | 🔧 | N+1 ao somar bônus de joias | Persistência | `WarriorController.java:79`, `InventoryController.java:30`, `SmithingService.totalGemBonus` | **Parcial:** `WarriorStatsService` agora carrega as joias dos equipados em UMA query (`findAllByItemIn`) — `/api/warrior` resolvido. Falta o `/api/inventory` (`InventoryController.getInventory` ainda faz 1 query por item) — pendente. |
| A10 | ✅ | Sem rate limit + enumeração de usuário no login | 🔁 Segurança | `AuthController.java:66` | **RESOLVIDO:** `LoginRateLimiter` (em memória, sem dependência) — login 10 falhas / 15min por IP+usuário (sucesso reseta), forgot-password 5 req / 15min por IP → **429**; IP via `X-Forwarded-For` (Railway). Mensagem unificada **"Invalid username or password"** (sem enumeração). Testes em `AuthRateLimitTest`. |

---

## 🟡 MÉDIO

| # | Status | Achado | Auditores | Local | Correção |
|---|--------|--------|-----------|-------|----------|
| M1 | ⬜ | Vender item ignora durabilidade ("lava" o desgaste) | Economia | `InventoryService.java:138` | `sellPrice × max(0.3, dur/100)`. |
| M2 | ⬜ | Check constraints de enum só patchados p/ `zone_activities.role` | Persistência | `SchemaMigrator.java:108` | Generalizar drop/recreate a partir de `Enum.values()` ou remover check; cobre `ResourceType`, `BuffType`, `ItemType` etc. |
| M3 | ⬜ | 3 patches do SchemaMigrator ainda agrupam `ADD COLUMN` num só `IF` | Persistência | `SchemaMigrator.java:79,133,176` (mail/vip/buff2) | Converter p/ `ADD COLUMN IF NOT EXISTS` por coluna (como ambush/durability). |
| M4 | ⬜ | `@Data` do Lombok em entidades JPA | 🔁 Persistência + Arquitetura | todas as `model/*.java` | Trocar por `@Getter/@Setter` + `equals/hashCode` por `id`; `TerritoryService:279` comparar por `getId()`. |
| M5 | ⬜ | Perfil default = `dev` (instant-complete + adm/adm123) | 🔁 Economia + Arquitetura | `application.properties` | Default seguro = `prod`; abortar boot se `instant-complete=true` fora de dev. (Hoje instant está ligado de propósito p/ teste — desligar no go-live.) |
| M6 | ⬜ | JWT: fallback hardcoded no repo, expiração 7d, reset não invalida tokens | Segurança | `config/JwtUtil.java`, `application.properties` | Falhar boot sem `JWT_SECRET` em prod; expiração menor + refresh; invalidar por `passwordChangedAt`. |
| M7 | ⬜ | Política de senha fraca (min 6, sem complexidade) | Segurança | `AuthController.java:146` | Mínimo 8–10; bloquear senhas comuns. |
| M8 | ⬜ | Falta `@Valid`/Bean Validation em vários `@RequestBody` | Segurança | Smithing/Zone/Mail/Guild DTOs | Padronizar `@Valid` + `@Min/@Max/@Size`. |
| M9 | ⬜ | Testes mascaram timers (todos rodam instant-complete) | 🔁 Economia + Arquitetura | `src/test/resources/application.properties` | Perfil `test` com `instant-complete=false` cobrindo cooldown/stamina/timer. |
| M10 | ⬜ | Kingdom × Territory representam a mesma coisa (nomes duplicados) | Arquitetura | `enums/Kingdom.java` ↔ `enums/Territory.java` | Vocabulário único; `Kingdom` derivar nomes de `Territory`. |
| M11 | ⬜ | CORS `*` em produção | 🔁 Segurança + Arquitetura | `application-prod.properties`, `SecurityConfig.java:55` | Restringir a origens conhecidas (domínio app + cliente Godot). |
| M12 | ⬜ | `ddl-auto=update` + SchemaMigrator caseiro com catch-warn | Arquitetura | `application-prod.properties`, `SchemaMigrator.java` | Migrar p/ Flyway/Liquibase; enquanto isso, abortar boot em falha de patch crítico + pular migrator em dev. |
| M13 | ⬜ | Resultado de batalha por parsing de string `WINNER:` | Arquitetura | `BattleSimulator` + consumidores | `simulate` retornar record com `winner`/`hpRestante` explícitos. |
| M14 | ⬜ | Arena sem matchmaking; `findOpponent` carrega todos os players | Persistência + Economia | `ArenaService.java:192` | `ORDER BY RANDOM() LIMIT 1` / faixa de rank; ranking com `LIMIT` no banco. |
| M15 | ⬜ | `getOrCreateSkill`/`getProfession` read-then-insert sem unique nem transação | Persistência | `GatheringService.java:32`, `WorkService.java:38` | Unique `(player,skill)`/`(player,work)`; tornar transacional. |
| M16 | ⬜ | EmailService engole exceções (falha de email invisível) | Arquitetura | `EmailService.java:83` | Logar com stacktrace + sinalizar falha; considerar retry. |

---

## 🟢 BAIXO

| # | Status | Achado | Local | Nota |
|---|--------|--------|-------|------|
| B1 | ⬜ | Headers de segurança ausentes (CSP, nosniff, HSTS); `frameOptions` desabilitado | `SecurityConfig.java` | Defesa em profundidade. |
| B2 | ⬜ | `GET /api/smithing/gems/{itemId}` não valida ownership | `SmithingController.java:174` | Vazamento de baixo valor (quais gemas num item alheio). |
| B3 | ⬜ | Campo legado `evasionChance` carrega o Armor Class (nome engana) | `Warrior.getEvasionChance`, `app.js` | Migrar front p/ `armorClass` e remover legado. |
| B4 | ⬜ | `new Random()` por chamada (fairness/testabilidade) | vários services | Usar `ThreadLocalRandom`/instância estática. |
| B5 | ⬜ | Reset de senha usa UUIDv4 (não `SecureRandom`); não invalida tokens anteriores | `AuthController:103` | `SecureRandom` base64url + invalidar pendentes. |
| B6 | ⬜ | Frontend monolítico (`app.js` ~3.466 linhas) + sem versionamento de API | `static/app.js` | Quebrar em módulos; introduzir `/api/v1/` antes do cliente Godot. |

---

## ✅ Pontos fortes (preservar)

- **IDOR:** nenhum endpoint `{id}` permite operar sobre recurso de outro jogador — padrão de ownership consistente.
- Sem SQL injection (JPQL parametrizado; SchemaMigrator usa SQL estático).
- Moeda centralizada em `PlayerService` / `addBronzeAmount`.
- PvP de zona é deflacionário (50% do roubo é destruído).
- Sem dependências circulares entre services.
- Logging estruturado `[Service] player= action=`.
- `@Profile("dev")` correto no `DataSeeder`; cobertura de testes ampla.

---

## Plano de ataque (tranches)

**Tranche 1 — Tapar as impressoras** (baixo risco, alto impacto): C1, C2, C4 + A1 (gemas em combate).
**Tranche 2 — Concorrência:** C3 + A8 (`@Version` + handler 409).
**Tranche 3 — Balanço & robustez:** A3, A4, A5, M1 (economia); A7 (cron idempotente); A9 (N+1); A2/M9 (open-in-view + perfil de teste).
**Tranche 4 — Segurança & dívida:** A10, M5, M6, M7, M8, M11, M12 + baixos.

---

### Progresso

- **2026-06-03 — Tranche 1 (parcial):** C1, C2, C4 ✅ resolvidos (389 testes, 6 de regressão novos em `ExploitRegressionTest`). Falta A1 (gemas em combate) para fechar a Tranche 1.
- **2026-06-03 — Tranche 2:** C3 ✅ resolvido + A8 🔧 parcialmente mitigado (392 testes, 3 novos em `OptimisticLockingTest`). **Todos os 4 críticos fechados.**
- **2026-06-03 — Todos os ALTOS (A1-A10):** A1, A2, A3, A4, A5, A6, A7, A10 ✅; A8 🔧 (data-safe); A9 🔧 (`/api/warrior` ok, `/api/inventory` pendente). 399 testes verdes. Novos testes: `OptimisticLockingTest`, `TerritoryCatchUpIntegrationTest`, `AuthRateLimitTest`. **Restam apenas itens MÉDIOS/BAIXOS + os parciais A8/A9.**
  - *Deploy prod:* a coluna `version` é adicionada automaticamente (Hibernate `ddl-auto=update` via `columnDefinition default 0` + `SchemaMigrator`); linhas existentes recebem 0. **Sem SQL manual.**
  - *Comportamento novo:* em duplo-clique/retry no mesmo collect, o cliente recebe **409** "Ação concorrente detectada. Tente novamente." (o front pode tratar reabrindo o estado atual).

---

## Backlog — Tarefas dedicadas (decididas, fora da rodada atual)

### BL-1 — Redesenho da resolução PvP de emboscada (origem: A8)

**Decisão (2026-06-03):** adiado como tarefa dedicada. A emboscada **já é segura** (C3 garante
que não há corrupção; um conflito vira 409 + rollback, recuperável por retry). O que falta é
**não falhar o request do jogador sob concorrência**.

**Raiz do problema:** a emboscada transfere bronze **entre dois jogadores**
(`ZoneService.applyDefeatPenalty(perdedor, vencedor)`), tocando 2 linhas. Resolver isso dentro
da transação do collect do atacante escreve nas linhas do alvo → conflito possível. Lock pessimista
gera deadlock AB-BA; transação aninhada conflita na própria linha do atacante (no ramo "atacante perde").

**Opções (do mais barato ao mais completo):**
1. **Retry no cliente** (1 linha no `app.js`): reenviar o collect 1× ao receber 409. Custo trivial, esconde o conflito raro. *Stopgap recomendado.*
2. **Retry transparente no servidor**: laço de retry no collect (transação nova por tentativa, ~3x). Jogador nunca vê 409. Precisa quebrar self-invocation (padrão usado no A7).
3. **Resolução assíncrona (outbox + job)**: emboscada vira evento, processado por job serializado com retry (como o scheduler de território). Nenhum request falha. Encaixa no design de notificação por **mail** que já existe. *Refactor maior; muda o "feeling" (resultado chega depois, não no modal do collect).*

**Pergunta de design em aberto (decide a direção):** o resultado da emboscada deve continuar
aparecendo **na hora do collect** (modal) ou pode chegar **por mail / no próximo acesso**?
- "Na hora" → Opção 1 ou 2.
- "Por mail" → Opção 3 (a mais limpa de verdade).

---

*Documento vivo — atualizar a coluna Status conforme cada item for resolvido. Auditoria somente-leitura; nenhum arquivo de produção foi alterado durante a auditoria em si.*
