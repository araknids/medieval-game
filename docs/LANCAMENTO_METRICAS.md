# Lançamento — Métricas, Observability e Survey [LAUNCH_METRICS]

Para o teste com amigos (pré-itch) e o launch. Backend = PostgreSQL (Railway).

## 1) Observability — você JÁ tem (não precisa construir nada)
O backend loga as ações com tag, ex.: `[KingdomService] player=4 action=startQuest kingdom=FISHING ...`,
`[InventoryService] ... action=equip ...`, `[KingdomService] ... action=collectQuest OK ...`.

**Como assistir os testadores ao vivo:** Railway → o serviço → **Deployments → View Logs**. Filtra por
`action=` pra ver o que estão fazendo, ou por `REJECTED`/`ERROR` pra ver onde batem em parede.

> Para um teste de poucos amigos, isso + as queries abaixo bastam. NÃO vale montar infra de
> observability (eventos/dashboard) pra um punhado de gente — é trabalho de escala, não de validação.

## 2) Queries SQL (rode no Railway → Postgres → Query, ou `psql`)

### a) Cadastros + atividade (1 linha por jogador)
```sql
SELECT p.username,
       p.created_at::date                                   AS cadastro,
       w.level, w.experience,
       p.stamina_updated_at                                 AS ultima_acao,   -- proxy de "ativo" (gastou estamina)
       p.bronze, p.rank_points, p.tower_best_floor,
       p.onboarding_seen,
       (p.starter_guard_done::int + p.starter_priest_done::int + p.starter_shop_done::int) AS deveres_feitos
FROM players p
LEFT JOIN warriors w ON w.player_id = p.id
ORDER BY p.created_at;
```

### b) Funil de onboarding (onde a galera para)
```sql
SELECT count(*)                                        AS total,
       count(*) FILTER (WHERE onboarding_seen)         AS viram_briefing,
       count(*) FILTER (WHERE starter_guard_done)      AS dever_guarda,
       count(*) FILTER (WHERE starter_priest_done)     AS dever_padre,
       count(*) FILTER (WHERE starter_shop_done)       AS dever_loja
FROM players;
```

### c) Retenção / "número D2" (proxy — ver nota)
"Voltou" = a última ação (estamina) caiu DEPOIS do dia do cadastro.
```sql
SELECT count(*)                                                                          AS cadastros,
       count(*) FILTER (WHERE p.stamina_updated_at::date > p.created_at::date)            AS voltou_d1,
       count(*) FILTER (WHERE p.stamina_updated_at::date > (p.created_at::date + 1))      AS voltou_d2,
       round(100.0*count(*) FILTER (WHERE p.stamina_updated_at::date > p.created_at::date)     /nullif(count(*),0),1) AS pct_d1,
       round(100.0*count(*) FILTER (WHERE p.stamina_updated_at::date > (p.created_at::date+1)) /nullif(count(*),0),1) AS pct_d2
FROM players p
WHERE p.created_at::date <= (CURRENT_DATE - 1);   -- só quem já TEVE a chance de voltar
```
> ⚠️ **Proxy, não retenção real.** `stamina_updated_at` é só o ÚLTIMO timestamp, não um histórico por dia.
> Mede "voltou ao menos 1× depois do dia 0", não "ativo NO dia N". Pra D1/D2 de verdade precisa de um
> **log de login/atividade** (ver §4). Pro teste com amigos, esse proxy + perguntar a eles basta.

### d) Distribuição de nível (mostra se travam cedo)
```sql
SELECT w.level, count(*) AS jogadores
FROM warriors w GROUP BY w.level ORDER BY w.level;
```

### e) Engajamento
```sql
SELECT (SELECT count(*) FROM kingdom_active_quests WHERE status='COLLECTED') AS quests_concluidas,
       (SELECT count(*) FROM players WHERE arena_wins+arena_losses > 0)      AS jogaram_arena,
       (SELECT count(*) FROM players WHERE tower_best_floor > 0)             AS subiram_torre,
       (SELECT count(*) FROM players WHERE daily_streak > 1)                 AS streak_2plus;
```

## 3) Survey pros testadores (manda no fim da sessão)
Curto de propósito — gente cansa. Cola num form (Google Forms) ou pede no chat:

1. **Em 1 frase, o que é esse jogo?** (testa se a 1ª impressão comunica)
2. **Nos primeiros 2-3 min, você entendeu o que fazer?** Onde travou ou se perdeu?
3. **O que te fez querer continuar — ou parar?**
4. **O ritmo** (estamina/progressão): rápido demais, ok, ou lento demais?
5. **Combate e missões**: divertido, ok, ou chato? Por quê?
6. **Bug/crash/coisa quebrada?** O quê e onde.
7. **Visual e UI**: claro? bonito? confuso/feio em algum ponto?
8. **0–10:** quão provável você jogar de novo amanhã? E indicar pra um amigo?

## 4) (Opcional) Retenção de VERDADE — `last_login_at`
Se quiser medir D1/D2 direito no itch (não só o proxy), o caminho barato é **1 coluna** `last_login_at`
no `players`, atualizada no login/refresh de token. Aí dá pra "logou no dia N", "ativo nas últimas 24h".
É uma migração pequena — decida se vale antes do itch (pro teste com amigos, NÃO precisa).
