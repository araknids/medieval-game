# Medieval Game — Contexto do Projeto para Agentes Claude

## Visão Geral

Jogo RPG idle/browser no estilo de jogos medievais antigos. O jogador cria um guerreiro, envia para missões com timer, coleta recompensas e progride. O projeto visa lançamento na Steam via cliente Godot (futuro), mas atualmente roda como web app.

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

### Instant-Complete (Dev)
`app.dev.instant-complete=true` em dev zera todos os timers de missão, arena, trabalho, torre e coleta. Em produção é `false`.

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
| Missões | `QuestService` | `QuestController` | Timer, drops, narrativa |
| Arena PvP | `ArenaService` | `ArenaController` | Assíncrono, ranking |
| Torre | `TowerService` | `TowerController` | Andares, chefes escalonados |
| Trabalho | `WorkService` | `WorkController` | Por profissão, level separado |
| Inventário | `InventoryService` | `InventoryController` | Equip, sell, sockets, guarded |
| Loja | `ShopService` | `ShopController` | Rotação 6h, raridade, compra única |
| Habilidades | `GatheringService` | `GatheringController` | Pesca, Mineração |
| Forja | `SmithingService` | `SmithingController` | Refino, craft, joias, sockets |
| Zonas | `ZoneService` | `ZoneController` | Expedições, PvP, NPCs |
| Templo | `TempleService` | `TempleController` | Cura HP, buffs, proteção de itens |
| Email | `EmailService` | — | Brevo HTTP API |
| Batalha | `BattleSimulator` | — | Reutilizado por Arena, Torre, Zona |
| Lore de Itens | `ItemLoreGenerator` | — | Textos gerados em memória |

---

## Documentos Relacionados

- `docs/FEATURES.md` — Lista completa de funcionalidades implementadas
- `docs/USE_CASES.md` — Casos de uso (gerado por agente)
- `docs/TEST_PLAN.md` — Plano de testes (gerado por agente)
