# Plano de Posicionamento & Crescimento — "O S&F Sombrio"

> **Status:** backlog estratégico (não-código). Implementar **mais pra frente**, depois da demo.
> **Origem:** discussão sobre o nicho (browser/idle RPG) após comparação com Shakes & Fidget.
> **Tese central:** o nicho é economicamente saudável e provado, mas não se vence sendo "um S&F com
> features melhores". Vence-se com **identidade**. O nosso diferencial já construído é a **batalha
> animada com sangue explícito** (ataques em cabeça/corpo/perna, gore, fundo por local). Todo este
> plano gira em torno de transformar isso em **marca** e em **motor de aquisição**.

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

> **"O Shakes & Fidget sombrio e brutal."**

S&F é cartoon, fofo, humorístico. O nosso é **medieval sangrento e sério**. Esse é o espaço que os
incumbentes **não podem ocupar sem trair a própria marca**. Toda decisão de arte, copy e marketing
reforça isso.

---

## 3. Backlog priorizado

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

Os números provam que o nicho **paga bem e dura anos com margem alta** — mas não se ganha sendo "um
S&F com features melhores". Apostar tudo na **identidade brutal/sangrenta** (a battle replay já
construída) como diferenciador de marca e motor de marketing viral, e usar o SoulStone só pra
cosmético/conveniência. Esse é o caminho realista pra um indie entrar neste mercado.

---

## 5. Dependências / o que já existe

- ✅ Battle replay 2D com gore + ataques por região + fundo por local ([BATALHA_ANIMADA]).
- ✅ Avisos globais (`TavernService.announce`) → base do killfeed (A3).
- ✅ SoulStone / VIP ([VIP]) → base do catálogo cosmético (C1).
- ✅ Guerra de território 3×5 ([GUERRA_FORMACAO]) → base da fortaleza de guilda (B3).
- ✅ Ranking de arena + achievements/títulos ([TITULOS]) → base das temporadas (B1).
