# Segurança — Follow-ups (não-bloqueantes)

Itens levantados na auditoria do Fable 5 (2026-06-10), feita **antes de tornar o repo público**.
A auditoria não achou nenhum **CRITICAL/HIGH**; o hardering bloqueante já foi aplicado (commit `243c593`:
rate-limit no `/register`, `@Version` em `TrainingSession`, `.gitignore`). Os itens abaixo são
**LOW/MEDIUM** e ficaram para depois — sem urgência, mas bom não esquecer.

## Pendentes

- [ ] **`escapeHtml` no histórico de território** — `app.js:3460` (`territoryHistory`): `l.attacker`,
  `l.defender`, `l.winner` e `name` entram no `innerHTML` sem escape, diferente do resto do arquivo.
  Mitigado hoje pelo `@Pattern` nos nomes (bloqueia `< > " & '`), mas é defesa única. Envolver cada
  um em `escapeHtml(...)` para defesa-em-profundidade. *(Estava reservado pra outra aba do front.)*

- [ ] **CSP header (backlog B1)** — o front é quase todo `innerHTML` (127 ocorrências em `app.js`) sem
  Content-Security-Policy de backstop. A defesa de XSS hoje depende 100% do `escapeHtml` por render +
  `@Pattern` nos nomes. Se um único render futuro esquecer o escape num campo não-pattern-restrito,
  vira XSS armazenado com roubo de token (JWT mora no `localStorage`). Adicionar um CSP (mesmo
  moderado, `default-src 'self'`) em `SecurityConfig.java` quando der pra nonce/externalizar os inline scripts.

- [ ] **`/h2-console/**` `permitAll` incondicional** — `SecurityConfig.java:42`. Inofensivo hoje (console
  só habilita no profile `dev`, em prod o servlet nem registra → 404), mas é um foot-gun latente.
  Gatear o `permitAll` atrás do profile dev.

- [ ] **Enumeração de usuário/email no `/register`** — mensagens distintas ("Username already exists" vs
  "Email already registered"). Já mitigado com rate-limit por IP (commit `243c593`). Fechar de vez =
  mensagem genérica única (tradeoff de UX — adiado).

- [ ] **Steam `/blue-merchant/link` aceita SteamID arbitrário** — `BlueMerchantService.java:107-114`.
  Inerte hoje (`app.steam.enabled=false`). **Obrigatório** validar um Steam auth ticket antes de
  ligar a integração Steam (F1).

## Resolvido

- [x] Rate-limit no `/register` (conta só falhas) — commit `243c593`
- [x] `@Version` em `TrainingSession` (anti double-collect de XP) — commit `243c593`
- [x] `.gitignore`: `.env*`, `target/`, `application-local.properties`, `assets externos/` — commit `243c593`
- [x] Verificação: nenhum segredo no histórico do git → sem necessidade de reescrever histórico
- [x] Verificação Railway: `JWT_SECRET` setado (boot guard derruba prod se ausente); sem conta `adm` em prod
