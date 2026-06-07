# PLANO — Servidores / Realms (deploys separados) [SERVIDORES]

> Status: **CÓDIGO IMPLEMENTADO** (2026-06-06) — falta só a **infra no Railway** (criar os serviços/bancos
> e setar as env vars, ver passo a passo abaixo). 3 decisões de design aprovadas pelo dono.

## Conceito

Cada **servidor** (realm) é um **deploy independente**: 1 app Spring Boot + 1 banco Postgres próprios,
no Railway, com **env vars próprias**. `test`, `prod1`, `prod2`… são só deploys diferentes do **mesmo
jar**, apontando pra bancos diferentes. Mundos 100% isolados (contas, guildas, ranking, território,
mercado — tudo por servidor). O jogador **escolhe o servidor** na tela de login (botões → abre a URL
do servidor escolhido).

## Por que dá pra fazer com QUASE ZERO código

A auditoria mostrou que o app já está pronto pra isso:
- **Frontend usa caminho relativo** (`fetch('/api/...')`, sem URL fixa) → o front de cada deploy fala
  com o **próprio** backend automaticamente. Nenhuma mudança por servidor.
- **Toda config vem de env var**: DB (`PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD`), `JWT_SECRET`,
  `APP_CORS_ALLOWED_ORIGINS`, `APP_BASE_URL`, flags de teste. → servidor novo = env vars novas.
- **JWT assinado com `JWT_SECRET` por instância** → token de um servidor não vale no outro (isolado).
- **`localStorage` é por origem (URL)** → cada servidor guarda seu próprio login no navegador.
- **`SchemaMigrator` + `ddl-auto=update`** migram o schema sozinhos em cada banco no 1º boot.
- **`DataSeeder` é só dev e idempotente** → não atrapalha.

Ou seja: subir um `prod2` hoje já **funciona** criando o serviço + banco + env vars. O código só ganha
o **seletor de servidor** e o **nome do servidor** na tela (UX), nada estrutural.

## Decisões (aprovadas)

| Tema | Decisão |
|------|---------|
| Arquitetura | **Deploys separados** (1 app + 1 Postgres por servidor) |
| Contas | **Por servidor** (registra/loga separado em cada um; personagem não cruza) |
| Seleção | **Lista de botões no login** que abre a URL do servidor (sem CORS) + nome do servidor no topo |

## O que muda no CÓDIGO (pequeno)

### Backend
1. **Config de identidade do servidor** (`application.properties`):
   ```properties
   app.server.id=${SERVER_ID:local}
   app.server.name=${SERVER_NAME:Local Dev}
   app.server.env=${SERVER_ENV:dev}        # dev | test | prod (só p/ rótulo/cor na UI)
   ```
2. **Endpoint público** `GET /api/server-info` → `{ id, name, env }` (sem auth — a tela de login
   precisa antes de logar). Adicionar à allowlist do `SecurityConfig` (junto de `/api/auth/**`).
   Pequeno `ServerInfoController` lendo os `@Value` acima.

### Frontend
3. **`/servers.json`** (estático, servido por cada deploy — same-origin, sem CORS): a lista de TODOS
   os servidores. Igual em todos os deploys (atualizar = mexer no arquivo e redeployar).
   ```json
   [
     { "id": "test",  "name": "Test",   "env": "test", "url": "https://medieval-game-test.up.railway.app" },
     { "id": "prod1", "name": "Prod 1", "env": "prod", "url": "https://medieval-game-production.up.railway.app" },
     { "id": "prod2", "name": "Prod 2", "env": "prod", "url": "https://medieval-game-prod2.up.railway.app" }
   ]
   ```
4. **Seletor na tela de login**: carrega `/servers.json` + `/api/server-info`; mostra os botões dos
   servidores. O servidor atual fica destacado; clicar em outro faz `window.location = url` (navega
   pro outro deploy, onde o jogador loga com a conta daquele servidor).
5. **Nome do servidor no topo** (header): "🌐 Prod 1" (cor por `env`: test = amarelo, prod = verde),
   pra não confundir em qual mundo está.

> Tudo isso é aditivo e não toca em nenhuma regra de jogo. ~1 controller + ~1 endpoint + ~40 linhas de JS.

## Passo a passo no Railway (criar um servidor novo)

Pra cada servidor novo (ex.: `prod2`):
1. **Novo banco**: adicionar um **PostgreSQL** novo no projeto Railway (gera `PG*` próprios).
2. **Novo serviço de app**: apontando pro **mesmo repo/branch** (`main`) — mesmo build do jar.
3. **Env vars** do serviço (ver checklist abaixo) — com `JWT_SECRET` **único** e `PG*` do banco novo.
4. **Domínio**: pegar a URL pública gerada (ex.: `medieval-game-prod2.up.railway.app`).
5. Pôr essa URL em `APP_BASE_URL` e `APP_CORS_ALLOWED_ORIGINS` desse serviço, e **adicionar a entrada
   no `servers.json`** (e redeployar os outros pra todos verem o novo servidor na lista).
6. 1º boot: `SchemaMigrator`/`ddl-auto` criam o schema no banco novo sozinhos.

## Checklist de env vars por servidor

| Env var | test | prod1 / prod2 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | `prod` |
| `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD` | banco do test | banco do prodN |
| `JWT_SECRET` | **único** | **único por servidor** |
| `APP_BASE_URL` | URL do test | URL do prodN |
| `APP_CORS_ALLOWED_ORIGINS` | URL do test | URL do prodN |
| `SERVER_ID` / `SERVER_NAME` / `SERVER_ENV` | `test`/`Test`/`test` | `prod2`/`Prod 2`/`prod` |
| `APP_DEV_INSTANT_COMPLETE` | `true` (teste sem gate de estamina) | `false` (gameplay real) |
| `APP_ZONE_BOSS_ENABLED` | `true` | `true` |
| `APP_MAINTENANCE_SOFT_WIPE` | usar p/ zerar o test | **deixar fora/false** no prod |
| `MAIL_ENABLED` (+ `BREVO_*`) | `false` (ou test) | `true` |

## Test vs Prod (a diferença é só env var)

- **Test**: `instant-complete=true` (testar sem esperar estamina), soft-wipe à vontade, mail off.
  É onde você valida cada deploy antes de mandar pro prod.
- **Prod1/Prod2**: `instant-complete=false` (estamina é o gate de verdade), soft-wipe desligado,
  mail on. Mundos pros jogadores.

## Operação / manutenção

- **Deploy de versão nova**: como todos rodam o mesmo branch, o Railway pode **auto-deployar** cada
  serviço no push (ou você redeploya um a um). Recomendo: testar no `test` primeiro, depois prod.
- **Lista de servidores** (`servers.json`): bundlada no front → ao add/remover servidor, edita o
  arquivo e redeploya todos (pra todos mostrarem a lista atualizada). Simples p/ poucos servidores.
- **Migrações**: cada banco migra sozinho no boot (independente). Constraint de enum nova etc. roda
  por servidor (já é o padrão do projeto).

## Custos

Cada servidor = **+1 Postgres + +1 instância de app** no Railway (cobra por recurso). Comece com
**Test + Prod 1**; sobe `Prod 2` só quando o Prod 1 encher. Sem custo de código recorrente.

## Segurança

- `JWT_SECRET` **diferente por servidor** (token não vaza entre servidores) — já é exigido em prod.
- `APP_CORS_ALLOWED_ORIGINS` de cada servidor = só a própria URL (o seletor navega, não faz fetch
  cross-origin, então não precisa abrir CORS entre servidores).

## Fora de escopo (futuro)

- **Conta compartilhada** entre servidores (login único) → exige um **serviço de auth central**
  (deploy + banco extra + o app delegando login). Feature grande à parte.
- **Transferência de personagem** entre servidores (export/import via um formato comum).
- **Leaderboard/ranking global** agregando todos os servidores (precisa de um agregador central).
- **Launcher central** com status (online/pop) de cada servidor (precisa CORS + endpoint de status).
- **Cross-server** (guilda/mercado/mail entre servidores) — não existe e não é trivial.
