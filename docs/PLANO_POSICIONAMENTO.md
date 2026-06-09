# Plano de Posicionamento & Crescimento — "O S&F Sombrio que paga"

> **Status:** backlog estratégico (não-código). Implementar **mais pra frente**, depois da demo.
> **Origem:** discussão sobre o nicho (browser/idle RPG) após comparação com Shakes & Fidget.
> **Tese central:** o nicho é economicamente saudável e provado, mas não se vence sendo "um S&F com
> features melhores". Vence-se com **identidade + um gancho estrutural que ninguém no nicho tem**.
> Temos **dois** diferenciais, em ordem de força:
> 1. **(nº 1, estrutural) Cash-out de itens pra Steam Wallet** — o jogador dropa, vende no Community
>    Market e recebe saldo Steam. Nenhum browser/idle RPG do nicho faz isso. É o que traz o público
>    de **economia de item** (estilo CS:GO/Dota). Já existe o scaffold ([MERCADO_STEAM], Mercador Azul).
> 2. **(nº 2, de marca) Batalha animada com sangue explícito** (ataques por região, gore, fundo por
>    local) — chama atenção e é clipável; é o motor de aquisição viral.
>
> ⚠️ **Consequência crítica:** ligar o cash-out **inverte as prioridades** — anti-bot e design de
> economia deixam de ser "mais pra frente" e viram **pré-requisitos obrigatórios de lançamento**
> (ver §2.5). Sem isso, a economia quebra e a Valve delista.

---

## 1. Contexto de mercado (dados reais)

Shakes & Fidget é da **Playa Games** (alemã, fundada 2009 por Jan Beuck e Martin Jässing), comprada
pela **Stillfront** em dez/2018.

| Métrica | Valor | Observação |
|---|---|---|
| Players registrados (S&F) | **50M+** | ⚠️ acumulado em ~15 anos, **NÃO** ativos |
| Registrados nos 5 jogos da Playa | ~70M | na época da compra |
| Faturamento líquido (jan–set 2018) | **~€7,7M** | — |
| Margem EBIT | **~55%** | a verdadeira lição: custo operacional mínimo |
| Faturamento recorde anual | **€17,5M** | jogo de 2009 ainda gerando isso |
| Preço da aquisição | **até €45M** | Stillfront |

**Fontes:** Stillfront (comunicado da aquisição), EU-Startups, Playa Games (About us), Steam stats.

**Leitura crítica:**
- "50M players" = número de **marketing** (registrados, não ativos). Não é a meta.
- A **margem de 55%** é a história real: browser/idle RPG é baratíssimo de operar → **não precisa de
  milhões de players pra ser lucrativo**. Viável pra indie/solo.
- **Longevidade**: retenção vem de **guilda + temporada + comunidade**, não de gráfico.
- **Comparável indie:** Melvor Idle (1 dev) foi adquirido pela Jagex (RuneScape). Prova de que dá
  pra um solo viver bem no nicho.

**Riscos do nicho (honestos):**
- Lotado, com incumbentes de 15 anos (S&F, Gladiatus, Torn, Melvor, Idle Champions).
- O gargalo real é **aquisição de usuário**, não o jogo. Sem orçamento de ads → precisamos de
  **gancho viral/orgânico** (itch.io, Reddit r/incremental_games, TikTok).
- Baixa barreira = muita concorrência genérica → **identidade** é o que salva, não features.

---

## 2. Posicionamento

> **"O Shakes & Fidget sombrio e brutal — onde seu loot vira saldo na Steam."**

S&F é cartoon, fofo, humorístico. O nosso é **medieval sangrento e sério** *e* tem **economia de item
de verdade**. Os incumbentes não podem copiar o tom (trairia a marca) nem o cash-out (não desenharam
a economia pra isso). Toda decisão de arte, copy e marketing reforça os dois eixos.

---

## 2.5. O diferencial nº 1 — Economia / cash-out pra Steam (detalhe)

### Por que é forte de verdade
- **Quase ninguém no nicho faz.** S&F, Gladiatus, Melvor — nenhum deixa sacar item pra Steam Wallet.
  Oferecemos o que CS:GO/Dota/TF2 oferecem (economia real de itens), num gênero onde isso não existe.
  O público de "item economy" é grande e fiel.
- **Já temos a fundação.** Mercador Azul ([MERCADO_STEAM]) + Casa de Leilão ([LEILAO]) são a base.
  Não é feature nova — é ativar e endurecer o que já foi desenhado.
- **Loop de retenção brutal.** "Joguei → dropei → vendi → comprei um jogo na Steam" gruda muito mais
  que "subi de nível".

### A real (ajuste de expectativa — comunicar certo no marketing)
- **Steam Wallet ≠ dinheiro.** O jogador **não saca pra conta bancária** — só gasta dentro da Steam.
  Isso é **bom** (afasta RMT criminoso e risco legal), mas atrai quem quer **bancar compras na Steam**,
  não quem quer "ganhar a vida". Vender errado = frustração + review-bomba.
- **A Valve é o porteiro.** Pós-2016 (loot box) ela analisa economia de item/"gambling" com lupa.
  Exige appid, aprovação Steamworks, e o item **precisa ter uso no jogo** (não pode ser só "comprou
  pra revender"). Pode barrar → **validar com a Valve cedo**, não com tudo pronto.

### As 3 decisões que precisam ser travadas ANTES de ligar
1. **Cosmético × Combate no mercado.** O que vai pro Community Market é **cosmético** (seguro, a Valve
   adora, evita P2W) ou **gear de combate** (mais atraente, mas é o P2W que o nicho odeia e gera
   review-bomba)? → *Recomendação inicial: começar cosmético/QoL; reavaliar combate depois com dados.*
2. **Sumidouros (anti-inflação).** Se item dropa infinito, o preço de mercado vai a zero. Precisa de
   dreno desde o dia 1: item quebra com uso? taxa de venda queima valor? craft consome itens?
   (Já há taxas de leilão 5%+15% [LEILAO] — base, mas insuficiente sozinha.)
3. **Validação Valve / requisito de "uso no jogo".** Confirmar com a Steam que o modelo do Mercador
   Azul (consignação → itemdef no inventário Steam → Community Market) passa nas regras **antes** de
   investir no `WebApiSteamMarketProvider` real.

### Pré-requisitos que sobem de prioridade (de "depois" pra "obrigatório")
- 🔴 **Anti-bot vira P0.** Economia de dinheiro real é **ímã de bot/farmer/multi-conta**. Sem defesa,
  o floor do mercado desaba, o player honesto sai e a Valve delista. A `docs/AUDITORIA_DUPE_BOT.md`
  sai do backlog e vira **fundação de lançamento**.
- 🔴 **Design de economia (sumidouros + faucets) vira pré-requisito**, não polimento tardio.

---

## 3. Backlog priorizado

### Tier 0 — Pré-requisitos do cash-out (fundação; **antes** de ligar o mercado Steam)

- **0a. Anti-bot / anti-multi-conta.**
  Promovido de backlog a P0 por causa do cash-out. Base: `docs/AUDITORIA_DUPE_BOT.md`.
  *Esforço: alto. Impacto: crítico (sem isso a economia e a relação com a Valve quebram).*

- **0b. Design de economia — sumidouros + faucets.**
  Drenos pra segurar inflação (quebra de item, taxa que queima, craft que consome). Mapear faucets
  (drops) × sinks pra o floor não ir a zero.
  *Esforço: médio/alto. Impacto: crítico.*

- **0c. Travar as 3 decisões (§2.5) + validar com a Valve.**
  Cosmético×combate, modelo de sumidouro, e confirmar regras Steamworks **antes** do provider real.
  *Esforço: baixo (decisão) + dependência externa (Valve). Impacto: crítico (gate de tudo).*

- **0d. `WebApiSteamMarketProvider` real + auth ticket.**
  Substituir o stub ([MERCADO_STEAM]); validar Steam auth no `linkSteam`; catálogo de itemdefs;
  venda→SOLD via poll/webhook. Só depois de 0a–0c.
  *Esforço: alto. Impacto: alto (é o diferencial nº 1 indo ao ar).*

### Tier A — Identidade & aquisição viral (faz primeiro; é o que traz player)

- **A1. Marketing "Dark Souls do browser idle".**
  Toda comunicação em cima da battle replay com gore. Clipável pra TikTok/Reddit.
  *Esforço: baixo (copy/posicionamento). Custo: zero. Impacto: alto.*

- **A2. Battle replay compartilhável.**
  Botão "compartilhar luta" → gera link/gif da replay (cabeçada, sangue, crit). Guerra de guilda
  épica vira conteúdo. Loop de aquisição grátis.
  *Esforço: médio (export de gif/link a partir dos `battleEvents` já existentes). Impacto: alto.*

- **A3. Killfeed global / kill log.**
  Reusar os avisos globais da Taverna (`TavernService.announce`): "☠ Fulano decapitou Beltrano na
  Zona Vermelha". Rivalidade pública = engajamento PvP.
  *Esforço: baixo (gatilho já existe na Taverna). Impacto: médio.*

### Tier B — Retenção / meta de longo prazo (segura quem já entrou)

- **B1. Temporadas / Battle Pass.**
  Reset competitivo de ranking a cada X semanas + trilha de recompensas (cosmético + SoulStone).
  Motivo recorrente pra voltar + ponto de monetização justo.
  *Esforço: alto. Impacto: alto (é o motor de retenção do S&F).*

- **B2. Álbum de coleção / Bestiário.**
  Matou cada monstro/chefe → desbloqueia entrada com lore. Casa com drops por reino.
  *Esforço: médio. Impacto: médio. Colecionismo é vício barato.*

- **B3. Fortaleza/base da guilda.**
  Guilda investe recursos numa fortaleza com buffs passivos + vira objetivo da guerra de território
  (3×5 já existe). Dá propósito ao acúmulo de recurso no late game.
  *Esforço: alto. Impacto: alto.*

### Tier C — Monetização ética (a margem só funciona se não espantar player)

- **C1. Catálogo cosmético no SoulStone.**
  SoulStone **só** pra conveniência + cosmético + temporada. **Nunca** poder de combate direto (o
  nicho review-bomba P2W na Steam). Skins de armadura sangrenta, efeitos de morte cosméticos na
  replay, slots extras, VIP de QoL. A moeda já existe ([VIP]) — falta o catálogo.
  *Esforço: médio. Impacto: alto (receita sem trair a base).*

---

## 4. Conselho de uma frase

O diferencial estrutural é o **cash-out pra Steam** (economia de item, que ninguém no nicho tem); o
gore é o **gancho de marca/viral** que traz gente pra ver. Mas ligar o cash-out **exige fundação**:
anti-bot e design de economia viram pré-requisitos, não polimento. Caminho realista = endurecer a
economia primeiro (Tier 0), depois usar o gore pra atrair (Tier A) e a meta pra reter (Tier B/C).

---

## 5. Dependências / o que já existe

- ✅ Mercador Azul + escrow/consignação ([MERCADO_STEAM]) + Casa de Leilão ([LEILAO]) → base do
  cash-out Steam (Tier 0).
- ✅ Taxas de leilão 5%+15% ([LEILAO]) → primeiro sumidouro (insuficiente sozinho — ver 0b).
- ✅ `AUDITORIA_DUPE_BOT.md` → base do anti-bot (0a).
- ✅ Battle replay 2D com gore + ataques por região + fundo por local ([BATALHA_ANIMADA]).
- ✅ Avisos globais (`TavernService.announce`) → base do killfeed (A3).
- ✅ SoulStone / VIP ([VIP]) → base do catálogo cosmético (C1).
- ✅ Guerra de território 3×5 ([GUERRA_FORMACAO]) → base da fortaleza de guilda (B3).
- ✅ Ranking de arena + achievements/títulos ([TITULOS]) → base das temporadas (B1).
