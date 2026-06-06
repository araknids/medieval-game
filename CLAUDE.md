# Medieval Game — Contexto do Projeto para Agentes Claude

## Visão Geral

Jogo RPG idle/browser no estilo de jogos medievais antigos. O jogador cria um guerreiro, gasta **estamina** em ações **instantâneas** (missões, coleta, trabalho, zona, arena), pega a recompensa na hora e progride; **sem timers** — a estamina (regen 100% em 1h) é o gate. O projeto visa lançamento na Steam via cliente Godot (futuro), mas atualmente roda como web app.

**URL de produção:** `https://medieval-game-production.up.railway.app`

---

## Tech Stack

| Camada | Tecnologia |
|--------|-----------|
| Backend | Java 17, Spring Boot 3.2.5 |
| Segurança | Spring Security + JWT (Auth0 java-jwt 4.4.0) |
| Banco (dev) | H2 in-memory |
| Banco (prod) | PostgreSQL (Railway) |
| ORM | Spring Data JPA / Hibernate |
| Frontend | Vanilla JS + HTML + CSS (servido pelo Spring Boot) |
| Email | Brevo HTTP API |
| Hosting | Railway |
| Source Control | GitHub (araknids/medieval-game) |

---

## Estrutura do Projeto

```
backend/
├── src/main/java/com/medieval/game/
│   ├── config/           # JWT, Security, CORS, GlobalExceptionHandler, DataSeeder
│   ├── controller/       # REST controllers (um por domínio)
│   ├── enums/            # Todos os enums do sistema
│   ├── model/            # Entidades JPA
│   ├── repository/       # Spring Data JPA repositories
│   └── service/          # Lógica de negócio
├── src/main/resources/
│   ├── static/           # Frontend (index.html, app.js, style.css)
│   ├── application.properties
│   ├── application-dev.properties   # H2, instant-complete=true
│   └── application-prod.properties  # PostgreSQL, Railway env vars
```

---

## Decisões Arquiteturais Importantes

### open-in-view=false
`spring.jpa.open-in-view=false` está ativado em produção. Isso significa que entidades lazy-loaded NÃO podem ser acessadas fora de um `@Transactional`. Use `@EntityGraph` nos repositórios ou carregue os dados dentro da transação.

### Sistema de Moedas
Três moedas separadas no banco: `bronze`, `silver`, `gold`. 100 bronze = 1 prata, 100 prata = 1 ouro. Nunca use `player.setGold()` diretamente — use `player.addBronzeAmount(n)` ou `playerService.spendBronze(player, n)`.

### Sem Timer (estamina é o gate) — [SEM_TIMER]
O jogo é **instantâneo**: missão/coleta/trabalho/zona/treino/arena resolvem na hora (sem `finishesAt` futuro; `=agora-1s` p/ evitar corrida de sub-segundo no collect). O custo é **estamina** (não tempo). `isReadyToCollect()` usa `>=` (`!isBefore`). **Sem `onMission`/"busy"** (removido 2026-06-05): não há bloqueio cruzado entre atividades — cada uma tem só seu guard de sessão única (work/torre/treino: `findByPlayerAndStatus(IN_PROGRESS)`; quest: `existsByPlayerAndStatus(IN_PROGRESS)`; zona auto-cancela pendurada). KO/HP é guard à parte. Não existe `/api/warrior/free`. Vários docs/PLANO_SEM_TIMER_PVP.md descrevem o modelo.

### Instant-Complete (flag de teste)
`app.dev.instant-complete=true` agora controla só o **bypass de estamina** (teste) — NÃO há mais timers p/ zerar. Em prod o flag pode estar ligado de propósito (teste solo). Em dev/teste o default é `true`.

### PvP de Zona com Flag + Tiers — [PVP_FLAG]
Farmar uma zona 🟡PVP/🔴HIGH_RISK = instantâneo + **flagga o player 1h** (`Player.pvpFlaggedZone/Until`). Outro player farmando a mesma zona (±10 níveis) pode **cruzar e saquear** o flagged (matchmaking `PlayerRepository.findFlaggedInZone`). Tiers: 🟢SAFE (só PvE NPC), 🟡 (−50% recursos +10% bronze, recursos travados), 🔴 (+ item travado `InventoryItem.pvpLocked` + XP pro killer). Item/recurso travado não vende/stasha/guarda enquanto flagged. **Toda coleta** (Pesca/Mineração/Mar Abençoado/Grutas) passa pelo ZoneService (drops por reino via `ZoneActivity.kingdom`). `/api/gathering` ficou só p/ skills, inventário de recursos e consumo de peixe (skills/resources/consume) — o antigo fluxo de **sessão de coleta** (`GatheringSession`, `/api/gathering/start|collect|cancel|current`) foi **removido** junto com o legado de quest (`/api/quests`, `ActiveQuest`).

### Classes (Recruit → Trial → Warrior/Archer) — [CLASSES]
Todo personagem **nasce `RECRUIT`** (neutro). No **Lv10** destrava a **Path Trial** (`/api/class`, `ClassChangeService`): escolhe o caminho e enfrenta o Guardião dele num combate instantâneo (reusa `BattleSimulator`). Vencer = vira `WARRIOR` (tank) ou `ARCHER` (crit/esquiva), **permanente**, com **respec grátis** (devolve todos os pontos de atributo). A diferença entre classes é **só stats base + caps de atributo por classe** (`WarriorClass.baseAttack/…/capFor()`) — o motor de combate **não muda**. `WarriorService.spendPoint` usa o cap da classe. `INT` fica reservado p/ uma futura classe Mage (sem magia ainda). Soft-wipe volta todos pra `RECRUIT`. Números das classes + Guardião são **placeholders p/ tuning no playtest**. Desenho: `docs/PLANO_CLASSES.md`.

**Armas por classe** ([CLASSES_ARMAS]): Warrior/Recruit só equipam arma **corpo-a-corpo** (espada/machado), Archer só **arco**. Trava no `InventoryService.equip()`. A categoria (`WeaponCategory MELEE/RANGED`) é guardada em `InventoryItem.weaponCategory` e **derivada do nome** da arma no `make()` (nome com `bow`/`arco` → RANGED); `null` (item legado) = MELEE. Loja (`ShopService.buildSlot`) e Forja (`SmithingService.craftRecipesFor`) mostram arco p/ Archer e espada p/ os demais; loot (`KingdomService.itemName`) idem. Virar Archer na Trial desequipa a espada e dá um "Hunting Bow".

**Tipos de arma** ([CLASSES_ARMAS]): 7 tipos (`WeaponType`) com **mesmo budget, distribuição diferente** — Sword(ATK+DEF), Greatsword(ATK puro), Axe(ATK+LUK), Spear(ATK+STR), Short Bow(ATK+DEX), Long Bow(ATK puro), Crossbow(ATK+LUK). O tipo é inferido do NOME; `make()` **sobrescreve** os stats da arma com `WeaponType.stats(itemLevel, rarity)` (atk/def/str/dex/luk; arma não dá HP). `InventoryItem` ganhou `strBonus/dexBonus/lukBonus` base (somados no `equippedGear`). Forja gera recipes por tier×tipo (`CraftRecipe.itemLevel` separado do `smithingLevel`); mail preserva `item_level` (arma recalcula stats no claim). Combate inalterado (STR=ATK+acerto, DEX=AC, LUK=crit). Números são placeholders p/ tuning.

### HP do Guerreiro
HP é armazenado como porcentagem (0-100) em `warrior.currentHpSnapshot`. Usa o mesmo padrão da stamina (snapshot + tempo decorrido). Regen: 100% em 1 hora. Guerreiro com HP=0 está inconsciente e não pode entrar em combate.

### Currency Safety
`InventoryService.sell()`, `WorkService.collectWork()` e `WorkService.cancelWork()` usam `player.addBronzeAmount()` para evitar somar no campo gold diretamente.

---

## Padrões de Código

### Controller → Service → Repository
Nunca pule camadas. Controllers chamam Services, Services chamam Repositories.

### Autenticação
Todos os endpoints (exceto `/api/auth/**`, arquivos estáticos) requerem `Authorization: Bearer <JWT>`. O `auth.getPrincipal()` retorna o `Long playerId`.

### Respostas de Moeda
O `WarriorController.buildResponse()` normaliza bronze/silver/gold automaticamente antes de retornar (trata casos de silver>100 por migration).

### Battle Log
O `BattleSimulator.simulate()` retorna uma lista de strings. A última linha contém uma tag `WINNER:NomeDoBoss|NomeDoGuerreiro` que deve ser removida antes de exibir ao usuário.

---

## Como Rodar Localmente

```bash
# Pré-requisitos: Java 17+, Maven
cd backend
mvn spring-boot:run
# Acessa em: http://localhost:8080
# H2 Console: http://localhost:8080/h2-console
# Login: adm / adm123
```

---

## Variáveis de Ambiente (Produção Railway)

| Variável | Descrição |
|----------|-----------|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `JWT_SECRET` | String longa aleatória |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | PostgreSQL |
| `MAIL_ENABLED` | `true` para ativar email |
| `BREVO_API_KEY` | Chave da API Brevo |
| `BREVO_FROM_EMAIL` | Email remetente verificado |
| `APP_BASE_URL` | URL base do app |

---

## Migrações Recorrentes

Ao adicionar campos `NOT NULL` em tabelas existentes no PostgreSQL, sempre adicionar `DEFAULT`:
```java
@Column(columnDefinition = "integer default 0")
private int myField = 0;
```
Ou rodar SQL manual antes do deploy:
```sql
ALTER TABLE tabela ADD COLUMN IF NOT EXISTS campo tipo NOT NULL DEFAULT valor;
```

Check constraints do PostgreSQL precisam ser atualizados ao adicionar valores em enums:
```sql
ALTER TABLE tabela DROP CONSTRAINT tabela_coluna_check;
ALTER TABLE tabela ADD CONSTRAINT tabela_coluna_check CHECK (coluna IN ('VAL1','VAL2','NOVO'));
```

---

## Arquivos-Chave por Domínio

| Domínio | Service | Controller | Notas |
|---------|---------|-----------|-------|
| Autenticação | `PlayerService` | `AuthController` | JWT, registro, reset de senha |
| Guerreiro | `WarriorService` | `WarriorController` | Stats, atributos, HP, buff |
| Classes | `ClassChangeService` | `ClassController` | Recruit→Trial(Lv10)→Warrior/Archer; caps por classe; respec. [CLASSES] |
| Missões | `QuestService` (legado) / `KingdomService` | `QuestController` / `KingdomController` | Instantâneo (gate=estamina), drops, narrativa. As missões vivas são as do reino (`/api/world/{kingdom}/quests`). |
| Arena PvP | `ArenaService` | `ArenaController` | Duelo instantâneo por ranking (1 chamada resolve tudo) |
| Torre | `TowerService` | `TowerController` | Andares, chefes escalonados |
| Trabalho | `WorkService` | `WorkController` | Por profissão, level separado |
| Inventário | `InventoryService` | `InventoryController` | Equip, sell, sockets, guarded |
| Loja | `ShopService` | `ShopController` | Rotação 6h, raridade, compra única |
| Coleta | `GatheringService` | `GatheringController` | Roller de drops por reino + consumo de peixe. A coleta em si roda pelo ZoneService (unificada). |
| Forja | `SmithingService` | `SmithingController` | Refino, craft, joias, sockets |
| Zonas | `ZoneService` | `ZoneController` | Coleta + combate instantâneos; PvP por flag/tiers, raid, item-lock; drops por reino (`kingdom`) |
| Templo | `TempleService` | `TempleController` | Cura HP, buffs, proteção de itens |
| Email | `EmailService` | — | Brevo HTTP API |
| Batalha | `BattleSimulator` | — | Reutilizado por Arena, Torre, Zona |
| Lore de Itens | `ItemLoreGenerator` | — | Textos gerados em memória |

---

## Documentos Relacionados

- `docs/PLANO_SEM_TIMER_PVP.md` — **fonte da verdade atual** do modelo sem-timer + PvP de zona (flag, tiers, item-lock, coleta unificada). Mantido atualizado.
- `docs/FEATURES.md`, `docs/GDD.md`, `docs/USE_CASES.md`, `docs/TEST_PLAN.md` — ⚠️ **parcialmente desatualizados** (escritos antes do sem-timer/PvP de zona/coleta unificada; descrevem timers e zonas "coming soon"). Use o código + o PLANO acima como verdade.
