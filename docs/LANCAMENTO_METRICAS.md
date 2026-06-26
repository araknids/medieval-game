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

### c) Retenção / "número D2"
`last_seen_at` = a última vez que o jogador ABRIU o app (todo boot faz `GET /api/warrior`, que toca
isso 1×/dia). "Voltou" = `last_seen_at` caiu DEPOIS do dia do cadastro.
```sql
SELECT count(*)                                                                       AS cadastros,
       count(*) FILTER (WHERE p.last_seen_at::date > p.created_at::date)               AS voltou_apos_d0,
       count(*) FILTER (WHERE p.last_seen_at::date > (p.created_at::date + 1))         AS voltou_apos_d1,
       round(100.0*count(*) FILTER (WHERE p.last_seen_at::date > p.created_at::date)     /nullif(count(*),0),1) AS pct_d1,
       round(100.0*count(*) FILTER (WHERE p.last_seen_at::date > (p.created_at::date+1)) /nullif(count(*),0),1) AS pct_d2
FROM players p
WHERE p.created_at::date <= (CURRENT_DATE - 1);   -- só quem já TEVE a chance de voltar
```
> Nota: `last_seen_at` é o ÚLTIMO dia ativo (1 timestamp), então mede "voltou ao menos 1× além do dia
> 0/1", não "ativo no dia EXATO N". Pra coortes por-dia-exato (D1/D7 reais) precisaria de uma tabela de
> dias-ativos (futuro). Pro launch, esse **`pct_d2` é o teu número D2**.

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

1. **Teve problema pra BAIXAR ou ABRIR o jogo?** (aviso "Windows protegeu seu PC", antivírus, demora) —
   *crítico: quem trava aqui some sem jogar; precisa saber se o atrito do .exe não-assinado matou gente.*
2. **Em 1 frase, o que é esse jogo?** (1ª impressão — pergunte ANTES de explicar o que é)
3. **Nos primeiros 2-3 min, você entendeu o que fazer?** Onde travou ou se perdeu?
4. **O que te fez querer continuar — ou parar?**
5. **O ritmo** (estamina/progressão): rápido demais, ok, ou lento demais?
6. **Combate e missões**: divertido, ok, ou chato? Por quê?
7. **O clima/tom** te marcou? (sombrio e com personalidade, ou genérico/indiferente?) — *testa o diferencial.*
8. **Visual e UI**: claro? bonito? confuso/feio em algum ponto?
9. **Bug/crash/coisa quebrada?** O quê e onde.
10. **0–10:** quão provável você jogar de novo amanhã? E indicar pra um amigo?

> **Se a pessoa só responder 3-4**, as de ouro são: **#1** (conseguiu abrir?), **#3** (onde travou nos
> primeiros minutos), **#4** (o que fez continuar/parar) e **#10** (volta amanhã?). O resto é bônus.

**Como aplicar (não estrague o sinal):**
- **Deixa a pessoa falar primeiro** ("conta o que achou") ANTES das perguntas específicas — não induz.
- **#2 pergunta ANTES** de você explicar o jogo (senão ela repete tua descrição, não a impressão dela).
- Manda **depois** da 1ª sessão, não no meio do jogo.
- O survey explica o **porquê**; o sinal de verdade é comportamento: **o `pct_d2` da §2c** (voltaram amanhã?).
  Pergunta dá a história, o número dá a verdade.

## 4) Retenção de VERDADE — `last_seen_at` ✅ IMPLEMENTADO
Coluna `players.last_seen_at`, atualizada **1×/dia** no `GET /api/warrior` (UPDATE direto throttle, sem
bater no `@Version`). É o que a §c usa — sinal real de "abriu o app no dia N". `ddl-auto=update` cria a
coluna no boot. (Próximo nível, se um dia precisar de coortes por-dia-exato: tabela de dias-ativos.)
