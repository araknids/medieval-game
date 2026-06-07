# Auditoria 2 — Conselho (segurança + escalabilidade + doc-drift) [AUDITORIA_2]

> 2026-06-06. Painel de 3 auditores (segurança, escalabilidade, doc-drift) + verificação adversarial
> dos achados críticos/altos. **Manchete: zero buraco crítico de segurança, zero bug de corrupção de
> dados** (procuraram IDOR/dup/double-spend e não acharam — ownership checks + `@Version` seguram).
> Os temas reais foram **índices de banco** e **documentação desatualizada**.

## Feito (commitado)

| # | Área | O quê |
|---|------|-------|
| A1 | doc | `PLANO_SEM_TIMER_PVP.md`: Status "NÃO implementado" → IMPLEMENTADO (era a fonte da verdade) |
| A2 | doc | Casa de Leilão documentada: CLAUDE.md `[LEILAO]` + `docs/PLANO_LEILAO.md` + FEATURES |
| A3 | perf | 14 índices de FK/lookup quentes (`SchemaMigrator.patchHotIndexes`, `CREATE INDEX IF NOT EXISTS`) |
| A4 | doc | CLAUDE.md: remove `QuestService`/`QuestController`; nota `[VIP]`; FEATURES (raid removido, regen 1h, conquistas, abandon path) |
| A5 | perf | N+1 → batch (`findByPlayerIn`): roster de guilda, ranking de arena, leilão (vendedores) + browse capado em 200, guerra (inimigos), `/guild/list` usa `countByGuild` |
| A6 | perf | matchmaking de arena: 2 buscas indexáveis (abaixo/acima do rank) em vez de `ORDER BY ABS(...)` (full scan+sort por luta) |
| A7 | seg | rate-limiter usa o **último** IP do `X-Forwarded-For` (anexado pelo proxy confiável), não o primeiro (spoofável) |
| A8 | seg/infra | CORS `*` **falha o boot em prod** (força origens explícitas); validade do JWT configurável (`JWT_EXPIRATION_HOURS`, default 7d); pool do Hikari configurável (`DB_POOL_SIZE`) |

## Deferido (pré-launch / quando tiver jogadores reais)

Não são urgentes no teste solo; ficam registrados:

- **Flyway / migrações versionadas** (A8): trocar `ddl-auto=update` + `SchemaMigrator` por Flyway com
  `ddl-auto=validate`. É uma migração **deliberada e arriscada** (baseline do schema de prod existente,
  escrever os scripts) — feature de pré-launch própria, **não** um quick-fix. O `SchemaMigrator` atual
  funciona; manter até lá.
- **Refresh token / revogação de sessão** (seg, low): hoje o JWT é stateless 7d, só revogado por troca
  de senha. Pré-launch público: access token curto + refresh, ou um `tokenVersion`/jti que o filtro checa.
- **CSP** (seg, low): frontend serve JS inline (CSP omitida de propósito). Reavaliar p/ reduzir XSS→token.
- **Hikari pool** (perf, low): subir `DB_POOL_SIZE` junto com o plano do Postgres no Railway antes do launch.
- **Cap global no forgot-password** (seg, low): além do per-IP, um teto global p/ limitar email-bomb.
- **Paginação no browse do leilão** (perf): hoje capado em 200; paginação de verdade quando o livro crescer.
- **`GuildWarService.eligibleTargets`** (perf, low): `findAll()` + `currentWar` por guild — ok com poucas
  guildas; trocar por um `NOT EXISTS` quando escalar (tela rara).
- **`softWipe` em páginas** (perf, low): admin-only; paginar quando a base de jogadores crescer.
- **Docs legados** (`GDD.md`/`USE_CASES.md`/`TEST_PLAN.md`): têm header de "parcialmente desatualizado";
  manter como referência (não deletar). O número factual errado (regen) já foi corrigido na FEATURES.

## Nota dos verificadores

- A3 (índices): real, mas o finder **inflou** — ~metade das tabelas citadas já tinha índice via unique
  composto (`resource_inventory`, `player_achievements`, `warrior_abilities`, `socketed_gems`). Os
  índices criados são só os realmente descobertos.
- "combatStats repete queries": real, mas é o **cron de guerra (6h, offline, capado em 15)**, não hot path
  — severidade "alta" do finder é generosa. Tratado parcialmente pelos índices; cache por-ciclo fica de futuro.
