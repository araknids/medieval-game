# Auditoria — Dupe / Corrida em Fluxos de Item + Estratégia Anti-Bot

> **Status:** auditoria de leitura + **correções P0/P2/P3 aplicadas em 2026-06-07** (ver §5).
> Documento de achados + recomendações para revisar **antes** de abrir uma demo pública (itch.io e afins).
> **Escopo:** Leilão, Correio (Mail), Stash, Venda/Equipar (Inventory). + Estratégia anti-bot.
> **Data:** 2026-06-07.
> **Método:** leitura adversária de `AuctionService`, `MailService`, `StashService`,
> `InventoryService`, `PlayerService`, `GlobalExceptionHandler`, models e repositórios.

> ### ✅ Implementado em 2026-06-07 (dupe). Anti-bot (§6) fica para depois.
> - **P0a** — `@Version` em `InventoryItem`, `AuctionListing`, `ResourceInventory`
>   (+ as 3 tabelas no `SchemaMigrator.patchOptimisticLockVersionColumns`). **Fecha F-1, F-3, F-4 e a matriz inteira.**
> - **P2** — `@DynamicUpdate` no `InventoryItem` (defesa extra contra clobber de `player_id`).
> - **P3a** — `browse`/`mine` do `AuctionService` viraram `@Transactional(readOnly = true)`.
> - **P1** — **já estava feito**: `ResourceInventory` já declarava o `@UniqueConstraint(player_id, resource_type, stashed)`.
> - **P0b** (lock pessimista) — **dispensado**: com `@Version` no `AuctionListing`, buy+cancel já serializa (→409).
> - **Verificação:** build limpo + AuctionTest/StashTest/MailIntegrationTest/InventoryShopIntegrationTest/
>   OptimisticLockingTest/ExploitRegressionTest todos verdes (0 falhas).
> - **Falta (P3b):** suíte de testes de concorrência dedicada (§5) — recomendada antes do launch real.

---

## 1. Veredito executivo

**A arquitetura está certa e a maioria dos dupes está bloqueada — mas por acidente, não por design.**

- ✅ Identidade vem sempre do JWT (`auth.getPrincipal()`), nunca do cliente. Não dá pra agir como outro player.
- ✅ Economia é server-authoritative: cliente manda **ID/intenção**, servidor calcula preço/ouro/drop.
- ✅ `spendBronze` barra valor **negativo** (`[AUDITORIA C2]`) — sem criação de dinheiro por valor negativo.
- ✅ `OptimisticLockingFailureException` é tratado → **409 "tente de novo"** (não vira 500). Bom.
- ⚠️ **`@Version` só existe em `Player`, `Mail`, `ArenaMatch`, `WorkSession`, `ZoneActivity`.**
  **NÃO existe em `InventoryItem`, `AuctionListing`, `ResourceInventory`, `SocketedGem`, `ItemAffix`.**
- ⚠️ **Nenhum lock pessimista** (`@Lock`/`SELECT FOR UPDATE`) em nenhum repositório.
- 🔴 Um furo real de **reversão de posse de item** no `cancel` do leilão (detalhe em §4, F-1).

**Por que os dupes "não acontecem" hoje:** toda operação que move valor **também grava uma entidade
versionada** — `Player` (via taxa ou crédito de bronze) ou `Mail` (que tem `@Version`). Então a 2ª
transação concorrente colide na versão e cai em 409. As linhas de `InventoryItem`/`AuctionListing`/
`ResourceInventory` em si ficam **sem trava** — a proteção é uma consequência feliz de "sempre tem uma
taxa" e "mail é versionado". Tire a taxa de um fluxo (ou crie um fluxo de item que não grave Player) e
o dupe reaparece. **Isso é frágil e deve virar invariante explícita.**

---

## 2. Como funciona a proteção hoje (modelo mental)

```
Controller (sem @Transactional)
  └─ playerService.findById(principalId)   → carrega Player DESTACADO (cada request o seu, version=V)
       └─ service.metodo(@Transactional)
            ├─ re-fetch do Player (Auction) OU usa o destacado (sell/stash)
            ├─ muta item/listing/resource  ← SEM @Version
            └─ grava Player (taxa/bronze)  ← COM @Version  → serializa concorrência
```

Duas requisições paralelas (ex.: duas abas, ou script disparando em rajada) carregam cada uma seu
`Player` com `version=V`. As duas mutam e dão `save()` (merge). A 1ª faz commit (`V→V+1`); a 2ª tem
versão velha → `OptimisticLockingFailureException` → **rollback inteiro** (incluindo a escrita no item).
Resultado: a operação acontece **uma vez só**. É isso que mata o double-sell, sell+list, etc.

**A exceção perigosa:** operações de item que **não gravam Player** não têm essa rede de proteção.
São duas: **`cancel` do leilão** e **`equip`/`unequip`**.

---

## 3. Matriz de corridas (race matrix)

Legenda de veredito: ✅ seguro · 🟡 seguro mas frágil/incidental · 🔴 furo real.

| # | Corrida (2 requests simultâneos) | Protegido por | Veredito |
|---|----------------------------------|---------------|----------|
| 1 | `sell` + `sell` (mesmo item) | `Player.@Version` (ambos gravam bronze) | 🟡 incidental |
| 2 | `sell` + `list` (leilão) | ambos gravam Player (bronze / taxa) | 🟡 incidental |
| 3 | `sell` + `depositItem` (stash) | ambos gravam Player (bronze / taxa 50) | 🟡 incidental |
| 4 | `claimItem` (mail) ×2 | `Mail.@Version` | ✅ por design |
| 5 | `collectGold` (mail) ×2 | `Mail.@Version` | ✅ por design |
| 6 | `buy` (leilão) ×2 — 2 compradores, 1 listagem | `Player.@Version` do **vendedor** (ambos creditam) | 🟡 incidental |
| 7 | **`buy` + `cancel`** (mesma listagem) | **nada serializa** (cancel não grava Player) | 🔴 **F-1** |
| 8 | `withdrawResource` (stash) ×2 | `Player.@Version` (taxa 50) | 🟡 incidental |
| 9 | `depositResource` tipo novo ×2 | — (cria 2 linhas de stash) | 🟡 **F-2** (split, não dupe) |
| 10 | `equip` + `sell` (mesmo item) | sell grava Player; equip não | 🟡 sem dupe (perde equip, não duplica) |
| 11 | `equip` + `equip` | — (nenhum grava Player) | 🟡 sem valor movido (estado estranho) |

> **Observação sobre #2:** a taxa do leilão é `round(price × 0.05)`. Para `price` 1–9 a taxa
> arredonda pra **0** — mas `spendBronze(player, 0)` **ainda grava o Player** (line 115 do
> `PlayerService` salva incondicionalmente), então a versão ainda sobe e a proteção #2 segura.
> É mais sorte: depende de o `save` ser incondicional.

---

## 4. Achados ordenados por severidade

### 🔴 F-1 — `cancel` do leilão pode reverter a posse do item (HIGH / probabilidade média)

**Onde:** `AuctionService.buy()` (L92) vs `AuctionService.cancel()` (L128).

Nenhum dos dois trava a `AuctionListing` (sem `@Version`, sem `SELECT FOR UPDATE`). `cancel` é a **única
operação de item que não grava nenhuma entidade versionada** — só mexe em `InventoryItem` e
`AuctionListing`, ambas sem versão. Cenário:

```
T0: listagem L = ACTIVE, item.player = vendedor, item.listed = true
[comprador] lê L (ACTIVE), lê item            (snapshot: player=vendedor)
[vendedor ] lê L (ACTIVE), lê item (cancel)   (snapshot: player=vendedor)
[comprador] spendBronze(comprador); seller += payout (save seller, version++);
            item.setPlayer(comprador) + listed=false (save item); L=SOLD. COMMIT.
[vendedor ] item.setListed(false) na SUA cópia (player ainda = vendedor!);
            save item → UPDATE inventory_item SET player_id=VENDEDOR..., listed=false WHERE id=?
            (Hibernate sem dynamic-update reescreve a linha INTEIRA, revertendo a posse)
            L=CANCELLED. COMMIT.
```

**Resultado:** comprador pagou e o `seller` recebeu o payout, mas o **item voltou pro vendedor**.
O vendedor fica com **item + dinheiro**; o comprador é roubado. Não é dupe de item, mas é
**criação líquida de valor + roubo** — e o vendedor controla o `cancel`, então pode ficar
spammando cancel enquanto observa pra acertar a janela.

> Janela é apertada (cancel tem que ler o item *antes* do commit do buy), mas é forçável disparando
> as duas chamadas juntas. Em demo pública isso é exatamente o tipo de coisa que alguém cutuca.

**Correção recomendada (pós-demo, quando for codar):**
- `@Version` em `AuctionListing` **e** em `InventoryItem`; **ou**
- lock pessimista na listagem nos dois caminhos:
  `@Lock(PESSIMISTIC_WRITE) findById` no `buy` e no `cancel` (serializa de verdade); **ou**
- no mínimo, no `cancel`, recarregar a listagem e **rejeitar se `status != ACTIVE`** dentro do mesmo
  lock, e **não** reescrever `player_id` (usar `@DynamicUpdate` no `InventoryItem` pra só gravar a
  coluna `listed`).

### 🟡 F-2 — `depositResource`/`withdrawResource` podem criar linhas duplicadas de recurso (MEDIUM)

**Onde:** `StashService.stashRow`/`bagRow` (L124–131) usam `orElseGet(newRow)`.

Dois `depositResource` simultâneos de um **tipo de recurso ainda inexistente no stash** podem inserir
**duas linhas** `(player, type, stashed=true)`. Não duplica valor (a quantidade fica dividida), mas
quebra a invariante "1 linha por (player,type,stashed)" e pode confundir contagem/`stashSize`.

**Correção recomendada:** **unique constraint** em `(player_id, resource_type, stashed)` na tabela
`resource_inventory` + tratar violação como "recarrega e soma". Idem pra `bag` (stashed=false).

### 🟡 F-3 — Proteção contra dupe é incidental, não invariante (MEDIUM, dívida estrutural)

Linhas 1, 2, 3, 6, 8 da matriz só são seguras porque **toda** operação grava o `Player`. Não há nada
que **garanta** isso pra fluxos futuros. Hoje OK; é uma armadilha pra evoluções.

**Correção recomendada:** adicionar `@Version` em **`InventoryItem`**, **`AuctionListing`** e
**`ResourceInventory`** — torna a serialização propriedade da **linha que está sendo disputada**, não
de um Player que por acaso é gravado junto. É a mudança de maior alavancagem do documento e fecha 1, 2,
3, 6, 7, 8, 9, 10 de uma vez. Custo: uma coluna `version integer default 0` por tabela (+ migração
`SchemaMigrator`, padrão já usado no projeto).

### 🟢 F-4 — `equip`/`unequip` sem proteção de versão (LOW)

Não movem valor; o pior caso é um estado de "equipado" perdido numa corrida com `sell`. Sem dupe.
Resolvido de brinde se `InventoryItem` ganhar `@Version` (F-3).

### 🟢 F-5 — Notas menores (LOW / cosmético)

- `AuctionService.browse`/`mine` são `@Transactional` de leitura → poderiam ser
  `@Transactional(readOnly = true)` (perf, não segurança).
- `expire`/`expireDueAuctions` mutam sem proteção de versão, mas rodam num **scheduler single-thread**
  (+ lazy-on-read no `buy`, que já chama `expire` antes de vender) → risco baixo. Se F-3 for feito, herdam a trava.
- `MailService.send` valida `goldAmount < 0`, `message` vazio/>500, e destinatário ≠ você. ✅ Sólido.

---

## 5. Plano de correção priorizado (quando for codar — NÃO agora)

| Prioridade | Ação | Fecha |
|-----------|------|-------|
| **P0** | `@Version` em `InventoryItem`, `AuctionListing`, `ResourceInventory` (+ migração) | F-1, F-3, F-4, #1-#11 |
| **P0** | Lock pessimista (ou re-check de status sob lock) no `buy` **e** `cancel` do leilão | F-1 (reforço) |
| **P1** | Unique constraint `(player, resource_type, stashed)` em `resource_inventory` | F-2 |
| **P2** | `@DynamicUpdate` em `InventoryItem` (só grava colunas sujas; evita clobber de `player_id`) | F-1 (defesa extra) |
| **P3** | `readOnly=true` nas consultas; teste de concorrência (abaixo) | qualidade |

### Testes de concorrência sugeridos (a criar junto)
Um teste por corrida 🔴/🟡 que dispara **N threads** na mesma ação e afirma a invariante:
- `buy`+`cancel` paralelo → item tem **exatamente um** dono e o livro-razão de bronze fecha.
- `sell`×2 → item some 1×, bronze credita 1×.
- `claimItem`×2 → 1 item criado, 2ª chamada = 409/erro.
- `depositResource` tipo novo ×2 → **1** linha de stash, quantidade somada certa.
- Asserção transversal: **soma de bronze do sistema constante** antes/depois (nenhuma rodada cria valor).

> Rodar no perfil **Postgres/Testcontainers** (`mvn test -Ppostgres`) — H2 não reproduz isolamento real. [TESTE_POSTGRES]

---

## 6. Anti-bot — estratégia em camadas

> **Right-sizing:** pra **demo no itch** o risco de bot é BAIXO (público pequeno, sem economia real,
> soft-wipe ligado). O mínimo da §6.5 já basta. A estratégia completa é **pós-launch**, quando ranking
> e economia tiverem valor de verdade.

### 6.0 Premissa que mudaria tudo: o bot **não usa o seu JS**
O cliente JS é só **um** consumidor da API. Um bot sério fala direto com os endpoints REST e **pula o
front inteiro** — então **qualquer desafio precisa ser validado no servidor**, não só desenhado na tela.
Captcha que o front mostra mas o backend não exige = inútil. O padrão certo: o servidor **emite um token**
de "humano verificado" e a **ação sensível exige esse token** server-side.

### 6.1 Camada 1 — Rate limiting por conta/endpoint (fundação, maior ROI)
O jogo é instantâneo, então o abuso é **spam de ações**. Um token-bucket por `playerId` (e por IP no
pré-auth) cortando ações/min resolve 80% do problema sem atrito pra humano. Você já tem o
`LoginRateLimiter` — generalizar o mesmo padrão pros endpoints de ação (zona/arena/torre/leilão/mail).
**Bônus:** rate limit também é a sua rede contra os 409 de corrida virarem rajada.

### 6.2 Camada 2 — A estamina já é meio-gate (mas não basta)
Regen 100%/1h limita o **volume** por conta. Mas o bot pode rodar **24/7** pra nunca desperdiçar regen,
e multi-contas escalam. Estamina ajuda, não resolve.

### 6.3 Camada 3 — Detecção comportamental (o mais eficaz pro gênero)
Humano é irregular; bot é metronômico. Marcar (server-side) e somar num **score de risco**:
- **Regularidade do intervalo** entre requests (variância baixíssima = robô).
- **Ações/hora** acima do teto humano plausível.
- **Sessão contínua** sem pausa (ex.: 18h direto).
- **Cadência idêntica dia após dia.**

Score alto → escalona pra desafio interativo / shadow-limit (reduz drop silenciosamente) / revisão manual
do topo do ranking. Detecção passiva > captcha: zero atrito pro humano.

### 6.4 Camada 4 — Prova de humanidade (o que você perguntou)
Sobre o popup de **conta/clicar na imagem certa**:

- **Sincero:** desafio caseiro de **matemática é trivial** pra um script, e **clicar-na-imagem** cai pra
  OCR/ML. Como **única** defesa, atrapalha humano e mal segura bot. Use só como **escalonamento**
  secundário (quando o score de risco §6.3 estiver alto), nunca como porta principal.
- **Recomendado:** **Cloudflare Turnstile** (gratuito, *invisível* na maioria dos casos — sem clicar em
  bueiro), ou hCaptcha/reCAPTCHA. Eles já resolvem o braço-de-ferro anti-bot por você e validam via token
  server-side. Coloque em: **registro**, **login após N falhas**, e **escalonamento** quando o risco subir.
- **Anti-AFK-bot clássico de idle game:** um modal **"você está aí?"** disparado por padrão suspeito
  (cadência regular + sessão longa) que precisa ser resolvido em X segundos, senão **pausa a sessão**.
  Esse é o desafio que vale a pena fazer interativo — porque é raro (só dispara sob suspeita) e mata o
  farmer automático. A "continha/imagem" funciona AQUI (escalonamento raro), não na porta de entrada.

### 6.5 Camada 5 — Atrito de conta + honeypot
- **Verificação de email** no registro (você já tem Brevo) → 1 conta por email; corta multi-conta barata.
- **Honeypot:** um endpoint/ação que **nenhuma UI humana expõe**; se for chamado, é script lendo a API
  crua → flag/ban. Detector barato e preciso.
- **Integridade de PvP/ranking:** auditar contas no topo; considerar separar "verificado" de "não".

### 6.6 O que NÃO fazer
- ❌ Confiar em ofuscar/minificar o JS — o bot ignora o JS e fala com a API.
- ❌ Captcha caseiro como única barreira — alto atrito humano, baixa proteção.
- ❌ Captcha em **toda** ação — destrói o fluxo instantâneo (o ponto forte do jogo). Use por **risco**.

---

## 7. Checklist

### Pré-demo (mínimo viável pra abrir ao público)
- [ ] **P0 dupe:** decidir se entra agora ou se a demo aceita o risco (público pequeno + soft-wipe). Se entrar: `@Version` em `InventoryItem`/`AuctionListing`/`ResourceInventory` + lock no `buy`/`cancel`.
- [ ] **Rate limit por conta** nos endpoints de ação (§6.1) — barato e alto valor mesmo na demo.
- [ ] **Turnstile** no registro e login (§6.4) — invisível, sem atrito.
- [ ] Verificação de email ligada (§6.5).

### Pós-launch (economia/ranking com valor real)
- [ ] Suite de **testes de concorrência** no perfil Postgres (§5).
- [ ] **Detecção comportamental** + score de risco (§6.3).
- [ ] **Modal anti-AFK** sob suspeita (§6.4) — aqui a sua ideia de continha/imagem se encaixa.
- [ ] Honeypot + auditoria do topo do ranking (§6.5).
- [ ] Unique constraint dos recursos (F-2) + `@DynamicUpdate` (F-1 defesa extra).

---

## 8. Referência rápida — arquivos tocados nesta auditoria
- `service/AuctionService.java` — `list` (L55), `buy` (L92), `cancel` (L128), `expire` (L143).
- `service/MailService.java` — `send` (L37), `collectGold` (L98), `claimItem` (L181).
- `service/StashService.java` — `depositItem`/`withdrawItem` (L54/68), `*Resource` (L80/99), `stashRow`/`bagRow` (L124/128).
- `service/InventoryService.java` — `sell` (L207), `equip` (L110), `unequip` (L170), `make` (L272).
- `service/PlayerService.java` — `spendBronze` (L102, barra negativo), `addBronze` (L80).
- `config/GlobalExceptionHandler.java` — `OptimisticLockingFailureException` → 409 (L66).
- Models com `@Version`: `Player`, `Mail`, `ArenaMatch`, `WorkSession`, `ZoneActivity`.
- Models **sem** `@Version` (foco do risco): `InventoryItem`, `AuctionListing`, `ResourceInventory`, `SocketedGem`, `ItemAffix`.
</content>
</invoke>
