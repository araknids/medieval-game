# Plano — Reformular a Tela de Início (home/dashboard) — [HOME_REDESIGN]

## Objetivo
Transformar a home de um menu de atalhos estático numa **central de continuidade**: o jogador
abre e já vê **o que retomar** (atividades em andamento) e **o que aconteceu** (avisos). Tirar o
botão "Lutar" (mock).

## Onde mora (achado importante)
A home viva **NÃO** é `ui/Hub.gd` (morto na rota do Shell) — é **`Shell._build_dashboard()`**
(`Shell.gd:992`), mostrada por `_show_dashboard()` como `_dash`. Roteamento = `Shell._open(scr)`
direto (a dashboard é construída dentro do Shell). Telas válidas: `World, Work, Tower, Arena,
Delve`(=Incursão)`, Daily, Mail, Character, Temple, Guild, Leaderboards, Forge, Shop, Auction,
Stash, Tavern, Vip`. Zona/Missão de reino → `World` (Zona usa `_open_world_at(kingdom)`).

## Decisões (com o dono)
- **CTA principal = "Continuar inteligente"**: 1 botão grande context-aware que retoma a coisa
  MAIS urgente agora. Tira o "Lutar".
- **Todas as atividades ativas** viram card "continuar/coletar".
- **Painel de Avisos** com cards clicáveis (atacado / aviso da adm / correio).
- **Tira o grid de atalhos** (a sidebar do Shell já cobre toda a navegação — é redundante).

## Dados (já fiados no `BackendClient`/`Api`)
`work_current`, `zone_current`, `tower_current`, `expedition_current`(Incursão), `daily_status`,
`/api/world/active-quests`, `mail_inbox`(`unread` + raid mails via `hasReplay`/`senderPlayerId`),
`tavern_feed`(anúncio = `system==true`), `GET /api/zones/pvp-status`. **Nada precisa de backend
novo** (o "atacado há Xh" sai do `sentAt` do mail de raid).

## CTA "Continuar inteligente" — prioridade (1º match vence)
1. Foi atacado (raid mail c/ replay nas últimas 24h, não visto) → `Você foi atacado` → Mail(replays)
2. Trabalho pronto (`work.readyToCollect`) → `Coletar trabalho` → Work
3. Zona pronta/chefe (`zone.readyToCollect|bossPending`) → `Enfrentar chefe`/`Coletar` → World
4. Missão pronta/Luna (`quest.readyToCollect|lunaPending`) → `Resolver missão` → World(reino)
5. Daily disponível (`daily.canClaim`) → `Resgatar diária` → Daily
6. Incursão ativa (`expedition.active`) → `Continuar a Incursão` → Delve
7. Trabalho rodando → `Ver trabalho (faltam …)` → Work
8. Zona rodando → `Ver expedição` → World
9. Missão rodando → `Ver missão` → World
10. Torre ativa → `Subir a Torre (andar N)` → Tower
- **Vazio** (nada): `Partir em aventura` → World.

## Cards de ATIVIDADE (só os ativos; `clickable_card`)
Trabalho / Missão(reino) / Expedição(zona) / Torre / Incursão / Diário. Cada um: ícone (PixelLab,
`Icons`) + título (ouro) + linha de status (pronto=verde / faltam {tempo}=dim / CHEFE=warn) +
ponto-ouro se houver algo a coletar/decidir + ação (`Coletar`/`Resolver`/`Continuar`/`Ver`). Quest:
**1 card** (a mais urgente), não 1 por quest. Rota via `_open`.

## Painel de AVISOS (máx 3; `clickable_card`)
1. **Atacado há Xh** (raid mail mais recente: `hasReplay && senderPlayerId!=0`; Xh do `sentAt`
   via `Time.get_unix_time_from_datetime_string`) → Mail(replays). Borda carmim.
2. **Anúncio do Reino** (`tavern_feed` item `system==true` mais recente, ~60 chars) → Tavern.
3. **N cartas não-lidas** (`mail_inbox.unread>0`) → Mail.
Vazio → `UiKit.empty("Sem avisos", …)`. Não duplicar o "atacado" se já é o CTA.

## Layout (UiKit; cabe em 1280×720 sem rolar)
`_build_dashboard` vira: greeting (ouro 24) → **CTA** (`action_big`, full, h≈64) → HBox de 2 colunas:
- **Esquerda (~62%)**: `section("⚒ Atividades")` + `grid(host, ativos, _activity_card, compact=true)` (2 col).
- **Direita (~340px)**: `section("📜 Avisos")` + VBox de avisos em `capped_scroll(…, 360)`.
Fora isso, manter o `ScrollContainer` externo como fallback. **Remover** o "Lutar" e o grid de atalhos.
Refresh: `_show_dashboard()` chama um `_refresh_dashboard()` async (1 batch dos GETs) que (re)popula
CTA + colunas. Tempo via helper `_fmt_dur(secs)`.

## Ordem de build
**v1:** greeting + CTA(resolver de prioridade) + grid de atividades ativas + coluna de avisos +
remover Lutar/atalhos + `_refresh_dashboard` (batch). **Polish:** countdown ao vivo (1s timer
pausado por visibilidade), "abrir no item exato" (mail no replay, world no reino), ícones próprios
de zona/incursão, "visto" do alerta de ataque, pulse nos cards prontos.

Desenho UX completo: gerado pelo agente de design (este plano é o destilado).
