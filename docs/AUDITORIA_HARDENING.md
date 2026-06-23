# Auditoria de Hardening — Medieval Game

**Data:** 2026-06-23

Documento de síntese das vulnerabilidades e fragilidades confirmadas por verificadores céticos durante a auditoria de hardening. Cada achado foi validado contra o código real (arquivo:linha + trecho de evidência + cenário de exploração).

---

## Resumo Executivo

### Contagem por severidade

| Severidade | Quantidade |
|------------|------------|
| **P0 — Crítico** | 0 |
| **P1 — Alto** | 1 |
| **P2 — Médio** | 4 |
| **Total** | 5 |

### Contagem por frente

| Frente | Quantidade |
|--------|------------|
| `race` (corrida na mesma conta) | 2 |
| `exploit` (economia) | 1 |
| `migration` (schema/CI) | 2 |

### Visão rápida

- **Nenhum P0.** Não há dupe de moeda/item, falha de autenticação, nem perda de dados confirmada.
- O único **P1** é um inflacionador de economia (~3.6x) acessível a qualquer jogador: a recompensa de zona escala por duração enquanto a estamina satura em 100 — reabre a classe do `[ECON_EXPLOIT]` na direção invertida (duração longa).
- Os dois achados de **race** são violações de invariante na mesma conta (double-click / 2 abas), com janela apertada e impacto limitado (sem dupe), classificados P2.
- Os dois achados de **migration** são lacunas de robustez/cobertura de CI latentes, gateadas por um deploy FUTURO que adicione valor a um enum de coluna pré-existente. Sem defeito presente, mas defesa única e não testada na condição real.

---

## P1 — Alto

### 1. Recompensa de zona escala por duração mas estamina satura em 100 → eficiência 3.6x na duração máxima (reabre o `[ECON_EXPLOIT]`)

- **Frente:** exploit
- **Arquivo:linha:** `backend/src/main/java/com/medieval/game/service/ZoneService.java:152-157`, `285-299`, `573-606`

**Descrição:**
O fix do `[ECON_EXPLOIT]` desacoplou a recompensa da duração escalando bronze/XP/materiais por `rounds = durationMinutes / 10`, sob a premissa de que a eficiência por estamina ficaria CONSTANTE porque a estamina também cresceria (~`dur/2`). Porém `staminaCostFor` aplica `Math.min(100, ...)`: a estamina **satura em 100** (atingido já em `dur=200`), enquanto `rounds` continua crescendo linearmente até **72** (em 720min). O descasamento quebra a premissa.

Curva de eficiência (rounds por estamina):
- **5–200min:** estamina = `dur/2`, rounds = `dur/10` → eficiência = **0.20** (constante).
- **200–720min:** estamina travada em **100**, rounds cresce 20→72 → eficiência sobe de **0.20** para **0.72**.
- **Ganho = 0.72 / 0.20 = 3.6x.**

Vale tanto para **COMBAT** (`resolveCombatHunt`, XP/bronze nas linhas 298-299, SEM cap por-collect) quanto para **coleta normal** (`resolveGathering`, `rounds` na linha 585, drops escalam linearmente). `applyDropsAndRewards` (333-361) credita o total integral sem cap por-collect. Crucialmente, `resolveEncounters` (633/675) faz **UMA única rolagem** de PvP ou NPC por collect — o risco de morte NÃO escala com a duração, então o trade-off risco/recompensa também colapsa a favor do jogador.

É a MESMA classe de exploit que o comentário das linhas 282-284 afirma ter fechado, reaberta na direção invertida (duração LONGA em vez de curta), porque o comentário assume que `staminaCostFor` cresce ~`dur/2` mas ignora a saturação em 100.

**Cenário de exploração:**
Sem `instant-complete`, o jogador sempre entra numa zona COMBAT/coleta com `durationMinutes=720` no `/api/zones/enter`. Paga 100 de estamina (o teto), mas recebe 72 rodadas de bronze/XP/Monster Core/essência — **3.6x mais por estamina** do que uma expedição de 20min (10 estamina por 2 rodadas). Como a estamina é o ÚNICO gate do jogo sem-timer e o risco de encontro é 1 rolagem fixa por collect, basta escolher a duração máxima para multiplicar o income honesto por ~3.6x.

**Evidência:**
```java
// staminaCostFor (152-157) — estamina SATURA em 100
int cost = ... : Math.max(5, durationMinutes / 2);
return Math.min(100, Math.max(5, cost));

// resolveCombatHunt (285, 298-299) — rounds SEM cap, até 72
int rounds = Math.max(1, activity.getDurationMinutes() / 10);
activity.setBronzeGained(Math.round(level * COMBAT_BRONZE_PER_KILL * mult) * rounds);
```

**Correção recomendada:**
Escolher uma das duas (não ambas):
1. **Capar `rounds` proporcionalmente ao teto de estamina:** limitar `rounds` a ~20 (espelhando o cap de 100 da estamina → `min(rounds, 100/5)`), restaurando a eficiência constante de 0.20 acima de 200min. Aplicar tanto em `resolveCombatHunt` quanto em `resolveGathering`.
2. **Remover o `Math.min(100, ...)` de `staminaCostFor`:** deixar a estamina crescer linearmente com a duração (`dur/2` até 360 em 720min), mantendo a razão recompensa/estamina constante por construção. (Avaliar impacto no pool máximo de estamina do jogador.)

A opção 1 é a mais segura por não tocar no teto de estamina percebido pelo jogador.

---

## P2 — Médio

### 2. Equip concorrente deixa dois itens no mesmo slot (stat stacking) e fura a exclusividade arco/escudo

- **Frente:** race
- **Arquivo:linha:** `backend/src/main/java/com/medieval/game/service/InventoryService.java:147-214` (e `WarriorStatsService.equippedGear:62-65`)

**Descrição:**
`equip()` é um read-modify-write **cross-ROW** que o lock otimista `@Version` do `InventoryItem` NÃO protege. O `@Version` só serializa duas transações que escrevem a MESMA linha. Em `equip()`, o item a equipar é carregado por id (ex.: arma A), mas o "item atualmente equipado a desequipar" e os alvos do auto-desequipar arco/escudo são encontrados por queries SEPARADAS (`findByPlayerAndTypeAndEquippedTrue(player, type)`, linhas 186/195/204).

Duas requisições concorrentes de `equip` para dois itens DIFERENTES do mesmo `ItemType` (sem nada equipado no slot) cada uma escreve só a própria linha. Sob `READ_COMMITTED`, cada query "find currently equipped" retorna vazio (o commit da outra ainda não é visível), então cada uma marca seu item como `equipped=true` e salva. Não há constraint de unicidade no banco em `(player, type, equipped)` — nada captura. Resultado: **dois itens WEAPON (ou ARMOR/HELMET/etc.) ficam `equipped=true` ao mesmo tempo.**

A jusante, `WarriorStatsService.equippedGear()` (62-65) soma atk/def/hp/str/dex/luk + gems + afixos sobre TODO item com `isEquipped()` true, **sem dedupe por slot** → ambos os itens empilham stats de um único slot. A mesma lacuna derrota a exclusividade `[ARCO_SEM_ESCUDO]`: equipar escudo e arma ranged concorrentemente → cada um roda seu auto-desequipar antes do commit do outro → jogador termina com **arco E escudo equipados**, invariante explicitamente proibida pelo design.

**Cenário de exploração:**
De duas abas / script de double-click, disparar `POST /api/inventory/{A}/equip` e `POST /api/inventory/{B}/equip` no mesmo instante, onde A e B são duas armas não-equipadas (ou duas peças de armadura do mesmo `ItemType`). Ambos retornam sucesso (sem 409); o próximo `/api/inventory` mostra ambos `equipped=true`; os stats de combate agora incluem os bônus das DUAS armas de um slot. Repetir com as 3 melhores armas para empilhá-las. Equivalente: disparar `equip(arco)` e `equip(escudo)` concorrentemente para usar arco E escudo juntos, ganhando a DEF do escudo sobre o kit ranged.

**Evidência:**
```java
// equip(): alvo do unequip é OUTRA linha, achada por query, não ligada por @Version ao 'item'
inventoryRepository.findByPlayerAndTypeAndEquippedTrue(player, item.getType())
    .ifPresent(current -> { current.setEquipped(false); inventoryRepository.save(current); });
item.setEquipped(true);

// WarriorStatsService.equippedGear: soma TODOS os equipados, sem dedupe por slot
List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player).stream()
    .filter(InventoryItem::isEquipped)...;
for (InventoryItem i : equipped) { atk += scaleStat(i.getEffectiveAttack(), p); ... }
```

**Por que P2 (e não P1):** a janela de corrida é apertada (ambos os SELECTs "find currently equipped" precisam rodar antes de qualquer COMMIT, ~ms) — exige double-fire scriptado, não clique casual. O impacto é limitado e não-multiplicante: não duplica moeda nem item, só infla stats empilhando gear que o jogador JÁ possui (precisa estar na bag, atender ao nível, não estar quebrado) num slot, e é parcialmente auto-curável (o próximo equip/unequip legítimo no slot resolve para um único Optional). É uma violação de invariante persistente com vantagem de stats em PvP/arena/torre, mas o timing preciso + payoff capado a colocam em P2.

**Correção recomendada:**
Serializar o equip por jogador. Opções:
1. **Constraint parcial única no banco** (Postgres): `CREATE UNIQUE INDEX ON inventory_items (player_id, type) WHERE equipped = true;` — falha o segundo INSERT/UPDATE concorrente, transformando a corrida num erro 409 limpo. (Validar em H2/pgtest.)
2. **Lock pessimista no Player** no início de `equip()` (`SELECT ... FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)` no carregamento do player), serializando todas as mutações de equip da mesma conta.
3. **Defesa a jusante** (complementar, não substituta): em `equippedGear()`, deduplicar por `ItemType` mantendo só um item por slot (ex.: o de id maior), neutralizando o stacking mesmo que o invariante seja violado.

Recomendado: opção 1 (corrige a causa raiz no nível do banco) + opção 3 (defesa em profundidade contra dados já corrompidos).

---

### 3. `make()` checa capacidade da bag com read-then-insert sem guard → drops concorrentes estouram o cap

- **Frente:** race
- **Arquivo:linha:** `backend/src/main/java/com/medieval/game/service/InventoryService.java:332-342` (insert em `buildItem:357-388`)

**Descrição:**
`make()` protege a criação com `if (bagSize(player) >= max) throw inventory_full;` e então insere uma InventoryItem NOVA. Como a linha inserida é NOVA, não há `@Version` existente para colidir, e `bagSize()` é computado contando linhas (`bagFifths` → `findAllByPlayer(player).count()`). O cap é puramente aplicacional — **não há constraint de unicidade/check no banco** enforçando-o.

O caminho de exploração limpo é o **mail claim-item**: `MailService.claimItem` passa o `Player` para `make()` mas só muta a linha `Mail`; **não** chama `spendBronze`/`addBronze` nem toca campo do `Player`, então `Player.version` NÃO é incrementado. Duas requisições paralelas que cada uma cria um item para a mesma conta (ex.: reivindicar dois mail-items diferentes) leem `bagSize` quando a bag tem exatamente 1 slot livre, ambas passam o check `>= max`, e ambas inserem → a bag termina com `bagSize > maxInventorySlots`.

> **Nota de escopo (refutação parcial):** o achado original generalizava para `AuctionService.buy`, `ShopService`, `KingdomService`, `SmithingService`. Esses caminhos que **gastam ou concedem moeda no Player** ESTÃO protegidos: `AuctionService.buy` chama `playerService.spendBronze(buyer, ...)` que suja o `Player` → incrementa `@Version` → dois buys concorrentes do mesmo comprador colidem no lock otimista e um faz rollback. Só os caminhos de concessão que **inserem item sem mutar o Player** (mail claim-item, e `claimAll` correndo contra um claim-item separado) estão genuinamente expostos.

**Cenário de exploração:**
Com a bag em exatamente N-1 de N slots, disparar dois `POST /api/mail/{id}/claim-item` paralelos de dois mail-items diferentes da mesma conta (double-tap / 2 abas / retry de rede). Ambos passam o check de bag-cheia antes de qualquer commit, ambos itens são inseridos, e a bag fica com N+1 itens — excedendo o cap que a expansão de bag por SoulStone deveria enforçar.

**Evidência:**
```java
int max = player.getMaxInventorySlots();
if (bagSize(player) >= max) { ... throw error.inventory_full; }
return buildItem(...);
// bagSize -> bagFifths -> inventoryRepository.findAllByPlayer(player).stream().filter(...).count();
// o check conta linhas existentes; o insert adiciona linha nova sem token de lock compartilhado entre as duas transações
```

**Por que P2:** violação de invariante SOFT (bag holds `maxSlots+1`), não dupe de item/moeda — os itens são concessões legítimas que o jogador já possuía no mail. A jusante, `bagSpaceLeft()` faz `floor`/clamp `>=0`, então degrada graciosamente (sem espaço negativo, sem crash). Acumular mais de +1 exige re-racing preciso na fronteira a cada vez. O gate de monetização (SoulStone 30→50) é burlado em ~1 slot por corrida bem-sucedida.

**Correção recomendada:**
1. **Mutar o `Player` (mesmo que trivialmente) nos caminhos de concessão de item**, ou carregar o `Player` com `@Lock(PESSIMISTIC_WRITE)` no `claimItem`/`make()`, para que duas concessões concorrentes da mesma conta colidam no `@Version`/lock e uma faça rollback — espelhando a proteção que `spendBronze` já dá aos caminhos de moeda.
2. **Alternativa mais barata:** recheckar `bagSize(player) < max` imediatamente após o insert dentro da mesma transação e abortar (rollback) se estourou — transforma a corrida num erro recuperável em vez de um estado persistente inválido.

Recomendado: opção 1 (consistente com a proteção já existente nos caminhos de moeda).

---

### 4. `migrate()` roda no `ApplicationReadyEvent` — janela de boot servindo tráfego antes de derrubar checks de enum defasados

- **Frente:** migration
- **Arquivo:linha:** `backend/src/main/java/com/medieval/game/config/SchemaMigrator.java:24-60`, `219-243`

**Descrição:**
Todo o `migrate()` — inclusive `dropStaleEnumCheckConstraints`, a ÚNICA defesa contra um check-constraint de enum defasado em prod — está amarrado a `@EventListener(ApplicationReadyEvent.class)`. Esse evento dispara DEPOIS do refresh do contexto E do start do servidor web: o Tomcat já aceita requisições HTTP enquanto `migrate()` ainda não rodou. O Hibernate `ddl-auto=update` (que roda no refresh, ANTES do ready) NÃO recria check de coluna já existente (admitido no comentário das linhas 216-218). Logo, num deploy que adicione um valor novo a um enum de coluna PRÉ-EXISTENTE (ex.: futuro valor em `ZoneActivityStatus`/`QuestStatus`/`Kingdom`/`WarriorClass`/`CombatPosture`), existe uma janela entre "servidor no ar" e "`dropStaleEnumCheckConstraints` executou" em que o check antigo ainda barra o INSERT/UPDATE com o valor novo (Postgres 23514 → 500).

**Cenário de exploração:**
Deploy de versão que adiciona valor a um enum já persistido. App sobe, Tomcat aceita request, jogador dispara a ação que grava o valor novo ANTES do `ApplicationReadyEvent` terminar o sweep → check antigo barra o INSERT → 500. Mesmo sem corrida humana: se o boot do `migrate()` falhar/atrasar, o check defasado fica para sempre e TODO INSERT com o valor novo quebra só em prod — H2/pgtest do CI nunca reproduz (ambos `create-drop`, nascem com o check completo).

**Evidência:**
```java
@EventListener(ApplicationReadyEvent.class)
public void migrate() { ... dropStaleEnumCheckConstraints(); ... }
// comentário (216-218): "o update-mode não recria check em coluna existente, então o drop persiste entre restarts"
// application-prod.properties:8 -> spring.jpa.hibernate.ddl-auto=update (DB persistente)
```

**Por que P2:** a janela de boot é sub-segundo e auto-curável (sequência síncrona de ~40 DDLs; o gap Tomcat-up → sweep-done é de ms a poucos segundos a cada boot, depois fecha permanentemente). Não é exploit repetível/disparável por atacante — é coincidência de timing uma-vez-por-deploy. Não envolve corrida na mesma conta. É concern latente/hardening gateado por um deploy FUTURO que adicione valor de enum; não há enum quebrado hoje (o sweep roda proativamente e derruba todos os checks a cada boot). O cenário "se `migrate()` falhar, fica para sempre" é, na verdade, dependente de OUTRO achado (try/catch global do boot).

**Correção recomendada:**
1. **Mover o sweep de enum-checks para ANTES de o servidor web aceitar tráfego** — ex.: `@EventListener(ContextRefreshedEvent.class)` ou um `ApplicationRunner`/`InitializingBean` que rode no refresh, antes do start do Tomcat. (Validar que o DataSource já está disponível nesse ponto.)
2. **Estratégico:** adotar **Flyway/Liquibase**, que rodam migrações no startup ANTES do contexto ficar pronto e dão histórico versionado idempotente, eliminando a dependência de `ddl-auto=update` + sweep manual.

Recomendado: opção 1 como fix barato imediato; opção 2 como direção de médio prazo.

---

### 5. CI (H2 e pgtest) usa `ddl-auto=create-drop` — nunca reproduz o cenário real de prod (`update` sobre banco persistente com check/coluna pré-existente)

- **Frente:** migration
- **Arquivo:linha:** `backend/src/test/resources/application-pgtest.properties:9-11` (e `application-dev.properties:20`)

**Descrição:**
O job de Postgres do CI (criado justamente para pegar `SchemaMigrator` com `DO $$`, check-constraints de enum e índices) sobe container fresco com `ddl-auto=create-drop`. O `mvn test` default roda profile `dev`, também `create-drop`. O esquema nasce do zero a cada teste, então Hibernate sempre gera os check-constraints com o conjunto de valores ATUAL do enum. Resultado: o pgtest **nunca** tem um check "antigo" faltando um valor — exatamente a condição que `dropStaleEnumCheckConstraints` existe para consertar, e que só ocorre em prod (`ddl-auto=update` sobre banco criado num deploy anterior).

A suíte valida que o sweep **RODA** (o bloco `DO $$` executa sem erro no Postgres), mas **não** valida que ele **CONSERTA** a condição real, porque a condição nunca é criada. Nenhum teste em `backend/src/test` semeia constraint defasada, override de schema (`schema.sql`/`@Sql`), ou check antigo via `pg_constraint`. Migrações não-idempotentes ou drift de check de enum entre versões passam verdes no CI e só estouram no Railway.

**Cenário de exploração:**
Qualquer mudança futura que adicione valor a um enum de coluna existente passa nos dois jobs do CI (H2 + pgtest, ambos `create-drop`) porque ambos nascem com o check completo. Em prod, o banco persistente ainda tem o check da versão anterior (sem o valor novo) e o sweep é a única defesa — se ele atrasar/falhar (achados 4 e o try/catch global), o INSERT do valor novo quebra só em produção, sem nenhum teste vermelho avisando.

**Evidência:**
```
# application-pgtest.properties:9-11
spring.datasource.url=jdbc:tc:postgresql:16:///medieval
spring.jpa.hibernate.ddl-auto=create-drop

# application-dev.properties:20 (herdado pelo profile de teste default)
spring.jpa.hibernate.ddl-auto=create-drop

# application-prod.properties:8 (contraste)
spring.jpa.hibernate.ddl-auto=update   # PostgreSQL persistente (Railway)
```

**Por que P2:** lacuna de cobertura de CI / defesa-em-profundidade, não exploit runtime ou player-facing. Não há jeito concreto de quebrar prod hoje — o "exploit" é uma regressão hipotética futura de valor de enum que escaparia do CI verde. A defesa de prod real é robusta: o sweep roda a cada boot, derruba TODOS os checks de enum genericamente (não whack-a-mole por enum), é idempotente, e o `update` não recria check em coluna existente. O blast radius só se materializa se o próprio sweep genérico regredir — e nada no CI pegaria isso.

> **Nota:** o achado lumpeia ligeiramente o H2 ("nasce com o check completo") — o tratamento de check do H2 difere e é exatamente por isso que o job pgtest existe; mas o ponto central (nenhum dos dois jobs recria constraint defasada) se sustenta.

**Correção recomendada:**
Adicionar um teste Testcontainers (perfil pgtest) que:
1. Crie manualmente um check-constraint "estilo antigo" faltando um valor de enum (ex.: `ALTER TABLE ... ADD CONSTRAINT ..._check CHECK (col IN ('VAL1','VAL2'))` — sem o valor novo), via `@Sql` antes do contexto ou JDBC direto no setup.
2. Rode/dispare `dropStaleEnumCheckConstraints` (ou o boot completo do `SchemaMigrator`).
3. **Asserte** que o sweep removeu o check defasado E que um `INSERT`/`UPDATE` gravando o valor novo agora **sucede** (sem 23514).

Isso transforma a defesa atualmente não-testada-na-condição-real num teste vermelho que avisaria caso o sweep genérico regredisse.

---

## Notas de Dedup

- **Achados 2 e 3** ambos residem em `InventoryService.java`, mas têm **causas distintas** (cross-row equip invariant vs. read-then-insert de bag-cap) e linhas distintas (147-214 vs. 332-342) → mantidos como itens separados.
- **Achados 4 e 5** são complementares e referenciados cruzadamente (o 5 cita o 4 como agravante), mas têm **arquivos e causas distintas** (timing do `ApplicationReadyEvent` no `SchemaMigrator` vs. `ddl-auto=create-drop` na config de teste) → mantidos separados. Juntos descrevem o mesmo risco latente de drift de enum em prod sob dois ângulos (defesa única tardia + ausência de teste da condição real).
- Nenhum par com mesmo arquivo+causa foi encontrado para fusão.