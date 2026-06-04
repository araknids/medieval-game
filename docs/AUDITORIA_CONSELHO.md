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
| A8 | ✅ | Emboscada escreve em entidades de outro jogador sem lock | Persistência | `ZoneService.java:333` | **FECHADO (BL-1, Opção 2):** `ZoneCollectCoordinator` refaz o collect em transação nova sob conflito (até 3×, player fresco por tentativa) → o collect do atacante não falha mais; esgotando, 409 + retry do cliente. Resultado segue imediato. **Data-safe (C3):** `@Version` em `Player`/`ZoneActivity`/`Warrior` converte o lost-update em 409 (rollback), sem corrupção. **Hardening A8:** re-valida que o alvo ainda está `IN_PROGRESS` antes de aplicar (reduz a janela de conflito) + dedup de gravação. **Deferido (deliberado):** a degradação graciosa total (emboscada não falhar o collect do atacante) exige redesenhar a resolução PvP — a emboscada faz transferência de bronze **entre dois jogadores**, então isolar em transação aninhada criaria risco de deadlock na própria linha do atacante. Recomendado como tarefa dedicada de PvP. |
| A9 | ✅ | N+1 ao somar bônus de joias | Persistência | `WarriorController.java:79`, `InventoryController.java:30`, `SmithingService.totalGemBonus` | **RESOLVIDO:** `WarriorStatsService` carrega as joias dos equipados em UMA query (`findAllByItemIn`) — `/api/warrior` ok. E `InventoryController.getInventory` agora carrega as joias de **todos** os itens em 1 query (`findAllByItemIn` + `groupingBy`) em vez de 1 por item — N+1 eliminado nos dois endpoints. |
| A10 | ✅ | Sem rate limit + enumeração de usuário no login | 🔁 Segurança | `AuthController.java:66` | **RESOLVIDO:** `LoginRateLimiter` (em memória, sem dependência) — login 10 falhas / 15min por IP+usuário (sucesso reseta), forgot-password 5 req / 15min por IP → **429**; IP via `X-Forwarded-For` (Railway). Mensagem unificada **"Invalid username or password"** (sem enumeração). Testes em `AuthRateLimitTest`. |

---

## 🟡 MÉDIO

| # | Status | Achado | Auditores | Local | Correção |
|---|--------|--------|-----------|-------|----------|
| M1 | ✅ | Vender item ignora durabilidade ("lava" o desgaste) | Economia | `InventoryService.java:138` | **RESOLVIDO:** preço de venda efetivo = `sellPrice × max(0.30, dur/100)`. Item a 100% não muda; surrado vende por menos (piso 30%). |
| M2 | ✅ | Check constraints de enum só patchados p/ `zone_activities.role` | Persistência | `SchemaMigrator.java:108` | **RESOLVIDO (BL-6):** `SchemaMigrator.dropStaleEnumCheckConstraints()` dropa genericamente, no boot, os `*_check` das colunas de enum afetadas (skill_type, resource_type, quest_type, kingdom, territory); o app (JPA) valida o enum. |
| M3 | ✅ | 3 patches do SchemaMigrator ainda agrupam `ADD COLUMN` num só `IF` | Persistência | `SchemaMigrator.java` (mail/vip/buff2) | **RESOLVIDO:** convertidos para `ADD COLUMN IF NOT EXISTS` por coluna (mail, players VIP, warriors buff2). |
| M4 | ✅ | `@Data` do Lombok em entidades JPA | 🔁 Persistência + Arquitetura | todas as `model/*.java` | **RESOLVIDO:** `@Data` → `@Getter @Setter` nas 22 entidades (sem equals/hashCode/toString sobre campos lazy). `TerritoryService` agora compara guild por `getId()` (null-safe). |
| M5 | ✅ | Perfil default = `dev` (instant-complete + adm/adm123) | 🔁 Economia + Arquitetura | `config/StartupChecks.java` | **RESOLVIDO (parcial):** `StartupChecks` loga banner WARN gritante no boot quando `instant-complete=true` (mais forte ainda se perfil=prod). Não aborta de propósito — você usa instant em prod p/ teste; `adm/adm123` já é `@Profile("dev")`. |
| M6 | ✅ | JWT: fallback hardcoded no repo, expiração 7d, reset não invalida tokens | Segurança | `config/JwtUtil.java` | **RESOLVIDO:** boot aborta em prod sem `JWT_SECRET`. **Invalidação na troca de senha implementada:** `Player.tokenValidFrom` (setado no reset) + check no `JwtAuthFilter` (projeção leve `findTokenValidFrom`, compara com o `iat` do token em granularidade de segundo) → tokens emitidos antes do reset viram 401, sem rejeitar o novo login. Teste em `JwtInvalidationTest`. |
| M7 | ✅ | Política de senha fraca (min 6, sem complexidade) | Segurança | `AuthController` | **RESOLVIDO:** mínimo 6 → 8 no registro e no reset. |
| M8 | ✅ | Falta `@Valid`/Bean Validation em vários `@RequestBody` | Segurança | Smithing/Zone/Mail/Guild DTOs | **RESOLVIDO (BL-5):** `@Valid` + `@NotNull/@NotBlank/@Min/@Max/@Size` nos DTOs de Smithing/Zone/Mail/Guild → 400 via handler. Coberto por `DtoValidationTest`. |
| M9 | ✅ | Testes mascaram timers (todos rodam instant-complete) | 🔁 Economia + Arquitetura | `TimerPathIntegrationTest` | **RESOLVIDO:** novo teste com `@TestPropertySource(instant-complete=false)` cobre o caminho de timer real (quest não coletável na hora + stamina consumida). |
| M10 | ✅ | Kingdom × Territory representam a mesma coisa (nomes duplicados) | Arquitetura | `enums/Kingdom.java` ↔ `enums/Territory.java` | **RESOLVIDO (BL-2, Reinos V2):** `Territory` removido e fundido em `Kingdom` (território == reino) + flag de guild-war. |
| M11 | ✅ | CORS `*` em produção | 🔁 Segurança + Arquitetura | `application-prod.properties` | **RESOLVIDO:** prod restrito à origem do web app (overridable via `APP_CORS_ALLOWED_ORIGINS`); Godot é nativo, não afetado. |
| M12 | ⬜ | `ddl-auto=update` + SchemaMigrator caseiro com catch-warn | Arquitetura | `application-prod.properties`, `SchemaMigrator.java` | Migrar p/ Flyway/Liquibase; enquanto isso, abortar boot em falha de patch crítico + pular migrator em dev. |
| M13 | ✅ | Resultado de batalha por parsing de string `WINNER:` | Arquitetura | `BattleSimulator` + consumidores | **RESOLVIDO:** Arena/Torre/Território usam `simulateDetailed().firstWon()` (vencedor explícito) em vez de `contains("WINNER:"+nome)` — robusto a nomes que se contêm. |
| M14 | ✅ | Arena sem matchmaking; `findOpponent` carrega todos os players | Persistência + Economia | `ArenaService.java:192` | **RESOLVIDO:** `findOpponentsByRank` (10 mais próximos em rank, limitado no banco, sorteio entre eles); ranking de Arena e Torre com `LIMIT` no banco (`Pageable`/`findTop20…`). |
| M15 | ✅ | `getOrCreateSkill`/`getProfession` read-then-insert sem unique nem transação | Persistência | `GatheringService.java:32`, `WorkService.java:38` | **RESOLVIDO:** os unique `(player,skill)`/`(player,work)` já garantiam sem duplicatas; o resíduo (500 raro na 1ª criação concorrente) foi fechado: o INSERT roda em `ConcurrentEntityCreator` (REQUIRES_NEW) e o `getOrCreate` captura `DataIntegrityViolationException` e relê a linha que a outra tx gravou. Testes em `ConcurrentCreateTest`. |
| M16 | ✅ | EmailService engole exceções (falha de email invisível) | Arquitetura | `EmailService.java:83` | **RESOLVIDO:** loga com stacktrace completo (`log.error(..., e)`). Não relança (falha de email não deve quebrar registro/reset). |

---

## 🟢 BAIXO

| # | Status | Achado | Local | Nota |
|---|--------|--------|-------|------|
| B1 | ✅ | Headers de segurança ausentes (CSP, nosniff, HSTS); `frameOptions` desabilitado | `SecurityConfig.java` | **RESOLVIDO (parcial):** `frameOptions` disable → **SAMEORIGIN** (anti-clickjacking, H2 console segue ok), + `nosniff`, `Referrer-Policy: same-origin`, `HSTS`. **CSP estrita deixada de fora** (risco de quebrar inline do frontend) — junto do B6. |
| B2 | ✅ | `GET /api/smithing/gems/{itemId}` não valida ownership | `SmithingController.java` | **RESOLVIDO:** novo `SmithingService.gemsForOwnedItem` valida dono (404/409 se não for seu) e elimina o anti-pattern `new InventoryItem(){{…}}` no controller. |
| B3 | 🕓 | Campo legado `evasionChance` carrega o Armor Class (nome engana) | `Warrior.getEvasionChance`, `app.js` | **Adiado p/ Bucket D:** os campos `evasionChance`/`armorClass`/`totalEvasion` se sobrepõem e mexer no display arrisca regressão cosmética (bônus de evasão de buff). Vai junto da limpeza de frontend (B6). |
| B4 | ✅ | `new Random()` por chamada (fairness/testabilidade) | vários services | **RESOLVIDO:** `ThreadLocalRandom.current()` em Arena, Zone, Gathering, Quest e BattleSimulator. |
| B5 | ✅ | Reset de senha usa UUIDv4 (não `SecureRandom`); não invalida tokens anteriores | `AuthController` | **RESOLVIDO:** token `SecureRandom` 256-bit base64url + invalida os pendentes do mesmo player ao emitir novo. |
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

### Progresso (continuação)

- **2026-06-03 — Backlog + fechamento de parciais:** ✅ **BL-2** (Reinos V2), **BL-5** (`@Valid` nos DTOs), **BL-6** (drop genérico de enum check), **BL-1** (retry no servidor / fecha A8), **A9** (N+1 do `/api/inventory`). Sincronizados os status defasados (A8/A9/M2/M8/M10 → ✅). Também: fix de flake do `tc095` (`ZoneActivity.isReadyToCollect` passou de `>` estrito para `>=`, consistente com o resto). **Restam adiados de propósito:** BL-3 (Flyway), BL-4 (front + `/api/v1`), e deferrals menores (M6, M15, A4). 429 testes verdes.
- **2026-06-03 — Médios/Baixos (Buckets A+B+C):** ✅ M1, M3, M16, B2, B4 (seguros); ✅ M6, M7, B5, M11 (segurança); ✅ M14 (matchmaking+LIMIT), M15 (data-safe), M5 (banner boot), M9 (teste timer real).
- **2026-06-03 — Bucket D (refactors):** ✅ M13 (vencedor explícito), M4 (@Getter/@Setter nas entidades), B1 (headers de segurança). Registrados como backlog (grandes/baixo valor): **BL-2** M10, **BL-3** M12, **BL-4** B6+B3+CSP, **BL-5** M8, **BL-6** M2. 401 testes verdes. **401 testes verdes.**

### Progresso

- **2026-06-03 — Tranche 1 (parcial):** C1, C2, C4 ✅ resolvidos (389 testes, 6 de regressão novos em `ExploitRegressionTest`). Falta A1 (gemas em combate) para fechar a Tranche 1.
- **2026-06-03 — Tranche 2:** C3 ✅ resolvido + A8 🔧 parcialmente mitigado (392 testes, 3 novos em `OptimisticLockingTest`). **Todos os 4 críticos fechados.**
- **2026-06-03 — Todos os ALTOS (A1-A10):** A1, A2, A3, A4, A5, A6, A7, A10 ✅; A8 🔧 (data-safe); A9 🔧 (`/api/warrior` ok, `/api/inventory` pendente). 399 testes verdes. Novos testes: `OptimisticLockingTest`, `TerritoryCatchUpIntegrationTest`, `AuthRateLimitTest`. **Restam apenas itens MÉDIOS/BAIXOS + os parciais A8/A9.**
  - *Deploy prod:* a coluna `version` é adicionada automaticamente (Hibernate `ddl-auto=update` via `columnDefinition default 0` + `SchemaMigrator`); linhas existentes recebem 0. **Sem SQL manual.**
  - *Comportamento novo:* em duplo-clique/retry no mesmo collect, o cliente recebe **409** "Ação concorrente detectada. Tente novamente." (o front pode tratar reabrindo o estado atual).

---

## Backlog — Tarefas dedicadas (decididas, fora da rodada atual)

### BL-1 — Redesenho da resolução PvP de emboscada (origem: A8) — ✅ RESOLVIDO (2026-06-03, Opção 2)

**Resolução:** implementada a **Opção 2 (retry transparente no servidor)**. `ZoneCollectCoordinator`
(bean separado → transação nova por tentativa, padrão A7) refaz o `collect` sob
`OptimisticLockingFailureException` (até 3×, backoff 50ms, **recarregando o Player fresco a cada
tentativa** pra não repetir o conflito com versão velha). Esgotando, relança → 409 (retry do cliente é a
rede final). O resultado da emboscada continua **imediato** (modal de hoje); a Opção 3 (por mail) fica
para o futuro, se quisermos resolução assíncrona. Coberto por `ZoneCollectCoordinatorTest`.

**Histórico — decisão (2026-06-03):** adiado como tarefa dedicada. A emboscada **já é segura** (C3 garante
que não há corrupção; um conflito vira 409 + rollback, recuperável por retry). O que falta é
**não falhar o request do jogador sob concorrência**.

**Raiz do problema:** a emboscada transfere bronze **entre dois jogadores**
(`ZoneService.applyDefeatPenalty(perdedor, vencedor)`), tocando 2 linhas. Resolver isso dentro
da transação do collect do atacante escreve nas linhas do alvo → conflito possível. Lock pessimista
gera deadlock AB-BA; transação aninhada conflita na própria linha do atacante (no ramo "atacante perde").

**Opções (do mais barato ao mais completo):**
1. **Retry no cliente** ✅ **APLICADO (2026-06-03):** o helper `api()` do `app.js` faz 1 retry automático em 409 (após 150ms). Como toda regra de negócio virou 400, 409 é sempre seguro de repetir. Esconde o conflito raro do jogador. Resta decidir se Opção 2/3 ainda vale.
2. **Retry transparente no servidor**: laço de retry no collect (transação nova por tentativa, ~3x). Jogador nunca vê 409. Precisa quebrar self-invocation (padrão usado no A7).
3. **Resolução assíncrona (outbox + job)**: emboscada vira evento, processado por job serializado com retry (como o scheduler de território). Nenhum request falha. Encaixa no design de notificação por **mail** que já existe. *Refactor maior; muda o "feeling" (resultado chega depois, não no modal do collect).*

**Pergunta de design em aberto (decide a direção):** o resultado da emboscada deve continuar
aparecendo **na hora do collect** (modal) ou pode chegar **por mail / no próximo acesso**?
- "Na hora" → Opção 1 ou 2.
- "Por mail" → Opção 3 (a mais limpa de verdade).

### BL-2 — Unificar Kingdom × Territory (origem: M10) — ✅ RESOLVIDO (2026-06-03)
Feito na **Fase 1 do Reinos V2**: o enum `Territory` foi **removido** e fundido em `Kingdom`
(território == reino). `TerritoryService`/controle/declaração/scheduler/controller passaram a operar
em `Kingdom` + flag `app.kingdoms.war-territories`. Commit `3179c37`.

### BL-3 — Migração de schema com Flyway/Liquibase (origem: M12)
Hoje: `ddl-auto=update` + `SchemaMigrator` caseiro (já robusto, por-coluna). Flyway daria migrações
versionadas + fail-fast. **Mas:** introduzir Flyway num banco de prod existente exige *baseline* — se o
baseline não casar exatamente com o schema atual, a app não sobe. **Opinião:** com o `SchemaMigrator`
já robusto, o ganho não justifica o risco agora; reavaliar quando o schema estabilizar (ou fazer baseline
limpo aproveitando que o banco é descartável). Decisão do dono.

### BL-4 — Modularizar frontend + versionar API (origem: B6, inclui B3 e CSP do B1)
`app.js` tem ~3.466 linhas num arquivo só; API sem versionamento (`/api/...` sem `/v1`). **Importante
ANTES do cliente Godot** (contrato estável). Mas é grande e o versionamento quebra o frontend atual se
não for coordenado. Inclui: limpar campo legado `evasionChance`→`armorClass` (B3) e endurecer CSP (B1).
**Tarefa dedicada, idealmente junto do início do trabalho do cliente Godot.**

### BL-5 — `@Valid` nos DTOs restantes (origem: M8) — ✅ RESOLVIDO (2026-06-03)
Bean Validation aplicado nos DTOs de Smithing/Zone/Mail/Guild:
- `RefineRequest` `@NotNull oreType` + `@Min(1) @Max(100000) quantity`; `CraftRequest` `@NotBlank recipeId`; `GemRequest` `@NotNull fragmentType`
- `EnterRequest` `@NotNull zone/role` + `@Min(30) @Max(720) durationMinutes` (skillType segue nullable — exigido só p/ GATHERING no service)
- `SendRequest` `@NotBlank recipientWarriorName` + `@NotBlank @Size(max=500) message` + `@Min(0) goldAmount`
- `CreateRequest` `@NotBlank @Size(3..30) name` + `@Size(max=200) description`; `DonateRequest` `@Min(1) amount`

Erros viram 400 via `GlobalExceptionHandler.handleValidation`. Coberto por `DtoValidationTest` (8 casos). Defesa em profundidade; o pior caso (refino negativo) já era blindado no C2 no nível do service.

### BL-6 — Generalizar check-constraints de enum (origem: M2) — ✅ RESOLVIDO (2026-06-03)
Resolvido via `SchemaMigrator.dropStaleEnumCheckConstraints()` (commit `59db855`): no boot, dropa
genericamente os `*_check` das colunas de enum que ganharam valores (skill_type, resource_type,
quest_type, kingdom, territory) — o app (JPA) valida o enum. Surgiu do fix do GARIMPO em prod.
*Obs.:* cobre as tabelas conhecidas; um enum `STRING` novo em outra tabela ainda precisaria entrar na lista.

---

*Documento vivo — atualizar a coluna Status conforme cada item for resolvido. Auditoria somente-leitura; nenhum arquivo de produção foi alterado durante a auditoria em si.*
