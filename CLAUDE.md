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
O jogo é **instantâneo**: missão/coleta/trabalho/zona/treino/arena resolvem na hora (sem `finishesAt` futuro; `=agora`). O custo é **estamina** (não tempo). `isReadyToCollect()` usa `>=` (`!isBefore`) p/ evitar corrida de mesmo-instante. Vários docs/PLANO_SEM_TIMER_PVP.md descrevem o modelo.

### Instant-Complete (flag de teste)
`app.dev.instant-complete=true` agora controla só o **bypass de estamina** (teste) — NÃO há mais timers p/ zerar. Em prod o flag pode estar ligado de propósito (teste solo). Em dev/teste o default é `true`.

### PvP de Zona com Flag + Tiers — [PVP_FLAG]
Farmar uma zona 🟡PVP/🔴HIGH_RISK = instantâneo + **flagga o player 1h** (`Player.pvpFlaggedZone/Until`). Outro player farmando a mesma zona (±10 níveis) pode **cruzar e saquear** o flagged (matchmaking `PlayerRepository.findFlaggedInZone`). Tiers: 🟢SAFE (só PvE NPC), 🟡 (−50% recursos +10% bronze, recursos travados), 🔴 (+ item travado `InventoryItem.pvpLocked` + XP pro killer). Item/recurso travado não vende/stasha/guarda enquanto flagged. **Toda coleta** (Pesca/Mineração/Mar Abençoado/Grutas) passa pelo ZoneService (drops por reino via `ZoneActivity.kingdom`). `/api/gathering` só p/ consumo de peixe.

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
