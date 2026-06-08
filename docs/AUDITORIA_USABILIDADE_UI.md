# Auditoria de Usabilidade & Sistema de UI — Demo (Browser → Steam)

> **Papel:** Senior UI/UX Designer (MMORPG / browser RPG — Tanoth, Gladiatus, The Crims, RuneScape, Albion).
> **Entrega:** wireframes em **escala de cinza** (layout/UX, **sem arte final**), fluxos, biblioteca de
> componentes, paleta, tipografia, layouts responsivos. **Toda decisão vem com o PORQUÊ** (retenção,
> usabilidade ou engajamento).
> **Princípio-mestre:** **economia de cliques** — cada ação do loop central tem que custar o mínimo de cliques.
> **Data:** 2026-06-08. **Status:** design (não implementa código).

---

## 0. Premissa que muda o brief: este jogo é INSTANTÂNEO, não tem timer

Seu brief genérico pede "Fishing/Mining com **progress timer**". **Não faça isso.** O diferencial do seu
jogo (CLAUDE.md, `[SEM_TIMER]`) é: gasta estamina → **resolve na hora** → pega o loot. Um timer falso
mataria justamente a dopamina de clique-recompensa que é a sua vantagem competitiva contra Gladiatus
(que faz você esperar). **Exceção:** o **Trabalho** (`[WORK_IDLE]`) é a única atividade idle/timer — e por
isso ela merece um tratamento de UI diferente (card "em andamento" + coletar quando pronto).

> **✔ Verificado no código (2026-06-08):** pesca/mineração/coleta já são **instantâneas, sem timer** —
> `ZoneService.enter` grava `endsAt = agora − 1s` (pronto pra coletar na hora; [ZoneService.java:135]). O
> **único** timer real é o do Trabalho — `WorkService.startWork` grava `finishesAt = agora + horas`
> ([WorkService.java:116-118]). Ou seja: o jogo atual **já está certo**; este documento só rejeita a
> sugestão de timer do brief genérico — **não** pede mudança no comportamento atual da coleta.

**Por quê (retenção):** feedback imediato é o maior preditor de sessão-a-sessão em idle/RPG de browser.
Cada ação que devolve recompensa em <1s reforça o loop. Esconder isso atrás de timer aumenta o abandono no D1.

---

## 1. Diagnóstico do estado atual (com lente de UX)

| # | Problema real (na sua UI hoje) | Impacto | Severidade |
|---|--------------------------------|---------|------------|
| D1 | **9 abas** de navegação + **8 sub-abas** só no Commerce | Paralisia de escolha; novato não sabe por onde começar | 🔴 Alta |
| D2 | Fonte **Courier New (monospace)** em tudo | Parece ferramenta de dev/planilha, não jogo → bounce em 10s | 🔴 Alta |
| D3 | **Zero hierarquia visual** — tudo no mesmo peso/cor | Olho não sabe onde pousar; ação principal não se destaca | 🔴 Alta |
| D4 | **HP/Estamina** moram na sidebar esquerda, fáceis de ignorar | O recurso-gate do jogo não está sempre visível | 🟡 Média |
| D5 | **Sem onboarding** — cai direto no World "Loading..." | Novato não entende o loop em 60s | 🔴 Alta |
| D6 | **Sem feedback de ação** (juice) — número muda sem animar | Recompensa não "sente"; cliques parecem inertes | 🟡 Média |
| D7 | Equipar loot exige ir na aba Character e caçar na bag | Loop "loot → vestir" custa cliques demais | 🟡 Média |
| D8 | **Tooltip/hover** como única forma de ver stats | Quebra em mobile e em controle (Steam) | 🟡 Média (🔴 p/ Steam) |
| D9 | Features de end-game expostas pro novato (Blue Merchant, VIP, Auction, Guerra) | Ruído; dilui o loop central da demo | 🔴 Alta (demo) |

**Veredito:** a base funciona, mas a **apresentação** condena a primeira impressão. Nada disso exige Godot
(ver `AUDITORIA_DUPE_BOT`/discussão) — é CSS + reorganização de IA + onboarding + juice.

---

## 2. Arquitetura de Informação — de 9 abas para **5** (regra: máx. 5)

### 2.1 Mapeamento: o que vira o quê

```
HOJE (9 + 8 sub)                          PROPOSTA (5 + barra persistente)
─────────────────────────                ─────────────────────────────────
World ───────────────────────────────►   🗺  ADVENTURE   (zonas: coleta/mina/pesca/caça + missões)
Character ───────────────────────────►   🛡  CHARACTER   (stats, atributos, habilidades, equip, BAG)
Commerce[Buy/Sell] ──────────────────►   🏪  MARKET      (comprar/vender — acesso óbvio, regra de design)
Commerce[Smithing/Cooking] ──────────►   ⚒  CRAFT        (forja + cozinha)
Tower + Arena ───────────────────────►   ⚔  BATTLE       (Arena PvP + Torre PvE, sub-abas)
─────────────────────────  SECUNDÁRIO (fora da nav, em "⋯ More" / barra) ──────────────────────────
Temple (cura) ───────────────────────►   ⚡ vira QUICK ACTION na barra persistente (botão "Heal")
Work (idle) ─────────────────────────►   card "Em andamento" no Adventure + badge global
Mail ────────────────────────────────►   ícone 📬 com badge na barra de topo
Guild / VIP / Auction / Blue Merchant ►  ⋯ More (acessíveis; curadoria ADIADA — mantidas visíveis, §3)
```

**Por quê (usabilidade — Lei de Hick):** o tempo de decisão cresce com o nº de opções. Cortar de 9→5
itens reduz a carga cognitiva da navegação e deixa o caminho do loop central inequívoco. Agrupar por
**intenção do jogador** ("quero aventurar", "quero me equipar", "quero comprar", "quero criar", "quero
lutar") em vez de por sistema técnico é o que RuneScape/Albion fazem.

### 2.2 A barra persistente (sempre visível, todas as telas)

A informação crítica **nunca** entra na rotação de abas. Vive no topo (desktop) / topo+rodapé (mobile):

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ (◐)Kael Lv12  ❤▓▓▓▓▓▓▓░░ 78/100   ⚡▓▓▓▓▓▓▓▓▓░ 92/100   🪙 1,240   [＋Heal] [🎒Bag] 📬²│
└──────────────────────────────────────────────────────────────────────────────────────┘
```

**Por quê (regra de hierarquia):** HP e Estamina são o *gate* de todo o jogo — têm que estar sempre à
vista (regra de design 1). `[Heal]` e `[Bag]` como **quick actions** globais resolvem D4+D7: curar e abrir
inventário viram **1 clique** de qualquer tela (regra "inventário ≤ 2 cliques" → entregue em **1**).

---

## 3. Curadoria da demo — o que MOSTRAR e o que ESCONDER

> **🟦 DECISÃO (2026-06-08): manter TUDO visível por enquanto.** Nada é escondido na demo neste momento —
> todas as features continuam acessíveis. A tabela abaixo fica como **recomendação para o futuro** (quando
> quiser apertar a primeira sessão), **não como ação atual**. O loop central abaixo segue sendo o eixo do
> design (onboarding, hierarquia e juice se organizam em torno dele), mas sem ocultar o resto.

> **Loop central (eixo do design):** `criar guerreiro → gastar estamina (aventurar) → pegar loot → equipar → subir`.

> **Recomendação futura (NÃO aplicar agora):** se um dia quiser focar a 1ª sessão, esconder por flag o que
> não serve ao loop em <30 min. Mantida aqui só como referência:

| Mostrar (essencial ao loop) | Candidato a esconder (FUTURO) | Porquê esconder |
|---|---|---|
| Adventure (1 reino, 2-3 zonas) | Outros 2 reinos | Menos é mais; foca a 1ª sessão |
| Character (stats/equip/bag) | Habilidades avançadas até Lv5 | Revela progressão aos poucos |
| Market (Buy/Sell NPC) | **Auction (player market)** | Sem base de players na demo = vazio/confuso |
| Craft (Smithing básico) | Cooking, Estábulo | Secundário ao loop |
| Battle (Arena OU Torre, não os 2) | **Guerra de Guilda / Formação** | End-game; complexo demais p/ demo |
| Heal (quick action) | **VIP / SoulStone / Blue Merchant** | Monetização/Steam = pós-demo |
| Trabalho (1 card idle) | — | Bom gancho de retorno (idle) |

**Por quê (engajamento) — para quando/se for aplicar:** demo no itch tem ~60s pra fisgar. Cada sistema
extra na tela é um custo de atenção sem payoff imediato. Revelar features por **progressão** (level-gating
a UI) cria a sensação de "o jogo está crescendo comigo" — um dos motores de retenção mais fortes do gênero
(RuneScape faz isso com tabs que destravam). Implementação futura: um flag `app.demo.mode=true` que oculta
nav-items/sub-abas. **Por ora, decisão é manter tudo** — então este parágrafo é só fundamentação guardada.

---

## 4. Sistema de cores (tokens)

Dark fantasy, alto contraste, semântica consistente. Reaproveita seus valores atuais, organizados em tokens:

```
── SUPERFÍCIES ───────────────────────        ── SEMÂNTICA (ações/estados) ──────────────
--bg-0      #0e0e16  (fundo app)              --gold     #c9a84c  ação importante / destaque
--bg-1      #15151f  (cards/painéis)          --gold-hi  #e6c45f  hover do gold
--bg-2      #1e1e2d  (card aninhado/hover)    --danger   #e0556b  PvP / perigo / perda
--line      #2a2a3d  (bordas/divisórias)      --success  #4caf82  ganho / sucesso / coletar
                                              --info     #5b9bd5  informação / neutro
── TEXTO ─────────────────────────────        ── RARIDADE (itens) ───────────────────────
--ink-0     #ECE6DA  (texto primário)         r1 Comum    #9aa0aa   r4 Épico     #c97ddb
--ink-1     #A9A294  (secundário)             r2 Incomum  #4caf82   r5 Lendário  #e6a23c (glow)
--ink-2     #6E6A5E  (terciário/disabled)     r3 Raro     #5b9bd5
```

**Regras de uso (e o porquê):**
- **Gold = só a ação principal de cada tela** (1 botão dourado por contexto). Se tudo é dourado, nada é. Isso
  resolve D3: o olho vai direto pro "que fazer agora". *(Lei de hierarquia → reduz tempo até a 1ª ação.)*
- **Vermelho reservado a perigo/PvP** — nunca decorativo. Quando o jogador vê vermelho, é risco real
  (zona PvP, perda de item). Consistência cromática = o jogador "lê" risco sem ler texto. *(Reconhecimento > leitura.)*
- **Verde = ganho** (coletar, vender, sucesso de craft). Cria um vocabulário de cor que treina o jogador.
- **Contraste mínimo 4.5:1** texto/fundo (WCAG AA) → legibilidade em telas baratas e à luz do dia (mobile).

---

## 5. Sistema tipográfico

```
DISPLAY (só títulos de tela / nome de herói):  "Cinzel" ou "Marcellus"  — serifa romana, ar medieval
                                               USO PARCIMONIOSO (nunca em corpo)
CORPO / UI / números:                          "Inter" / system-ui sans  — alta legibilidade
NÚMEROS tabulares (stats, preços, dano):       Inter com `font-variant-numeric: tabular-nums`

Escala (rem):  H1 1.6 · H2 1.25 · H3 1.05 · body 0.95 · small 0.8 · micro 0.7
Peso:          títulos 600 · corpo 400 · ênfase/valor 600
Altura linha:  corpo 1.5 · densa (stats) 1.35
```

**Por quê (regra: usabilidade > imersão):** Courier New (D2) é o maior ofensor de "cara de jogo". Trocar o
**corpo** por um sans legível resolve leitura instantaneamente; deixar a fonte medieval **só nos títulos**
dá clima sem custar legibilidade. `tabular-nums` evita que os números "pulem" quando mudam (HP 9→10) — um
detalhe que faz a UI parecer profissional. Fonte decorativa em corpo aumenta tempo de leitura em ~15-25%
em telas pequenas → péssimo p/ mobile.

---

## 6. Biblioteca de componentes (grayscale)

```
BOTÃO PRIMÁRIO (gold)        BOTÃO SECUNDÁRIO (outline)     BOTÃO PERIGO (red, PvP)
┌──────────────────┐         ┌──────────────────┐           ┌──────────────────┐
│   ▓ ADVENTURE ▓  │         │     Cancel        │          │   ⚔ Enter (PvP)  │
└──────────────────┘         └──────────────────┘           └──────────────────┘
 fill sólido, 1/tela          borda --line, texto ink-0      borda+texto --danger

CARD DE AÇÃO (zona/quest)                       BADGE / CHIP
┌────────────────────────────────────────┐     ( Lv 12 )  (🟢 SAFE)  (🔴 PVP)  (NEW •)
│ ▢  Whispering Woods            🟢 SAFE  │
│    Gather herbs & hunt beasts           │     BARRA DE RECURSO
│    ⚡ 8   ·   ~lvl 10-14                 │     ❤ ▓▓▓▓▓▓▓░░░  (cor por tipo: hp=red, stam=gold)
│                          ┌────────────┐ │
│                          │ ▓ Explore ▓│ │     TOOLTIP / DETALHE (tap & hold OU painel lateral)
│                          └────────────┘ │     ┌───────────────┐
└────────────────────────────────────────┘     │ Iron Sword     │  ← NÃO só hover (D8/Steam)
 1 ação dourada por card                        │ +6 ATK  Lv10   │     abre por clique/foco também
                                                └───────────────┘
SLOT DE ITEM (grid)         TOAST DE FEEDBACK (juice)        MODAL
┌────┐ borda = raridade     ┌───────────────────────┐       overlay escurece o fundo,
│ ⚔  │ canto: qtd/Lv        │ +12 🪙   +35 XP        │       1 ação primária + fechar
│ ⁵  │ vazio = tracejado    │ ✦ Epic Helmet dropped! │
└────┘                      └───────────────────────┘
```

**Por quê:** um kit pequeno e consistente (mesmo botão, card, badge em todas as telas — regra de navegação
"consistente entre telas") reduz a curva de aprendizado: o jogador aprende **uma vez** o que cada forma/cor
faz. Tooltip que abre por **clique/foco** (não só hover) é pré-requisito p/ mobile e controle de Steam (D8).

---

## 7. Wireframes das telas-núcleo (cinza, layout-only)

> Convenção: `▓` ação primária · `▢/▣` imagem/portrait placeholder · `▓▓░░` barra · sem cor/arte final.

### 7.1 ADVENTURE (dashboard + hub do loop) — a tela que abre o jogo

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ (◐)Kael Lv12  ❤▓▓▓▓▓▓▓░░ 78   ⚡▓▓▓▓▓▓▓▓▓░ 92   🪙1,240        [＋Heal] [🎒Bag] 📬² │  ← barra persistente
├────────────┬─────────────────────────────────────────────────────────────────────────┤
│ 🗺 ADVENTURE│  WHISPERING WOODS — Greenhold Kingdom                                    │
│ 🛡 Character│  ┌─────────────────────────────┐  ┌─────────────────────────────┐        │
│ 🏪 Market   │  │ ▢ Gather Herbs      🟢 SAFE │  │ ▢ Hunt Beasts       🟡 PVP  │        │
│ ⚒ Craft    │  │ ⚡8  ~lvl10-14    ┌─────────┐│  │ ⚡10 ~lvl12-18  ┌──────────┐│        │
│ ⚔ Battle   │  │                  │▓Explore▓││  │                │ ⚔ Hunt   ││        │
│            │  │                  └─────────┘│  │                └──────────┘│        │
│            │  └─────────────────────────────┘  └─────────────────────────────┘        │
│            │  ┌─────────────────────────────┐  ┌─────────────────────────────┐        │
│  ⋯ More    │  │ ▢ Mine Ore         🟢 SAFE  │  │ ▢ Quest: Lost Caravan       │        │
│            │  │ ⚡8  ~lvl10-14    ┌─────────┐│  │ Story · ⚡12   ┌──────────┐ │        │
│            │  │                  │▓ Mine  ▓││  │               │ ▓Begin▓  │ │        │
│            │  └─────────────────────────────┘  └──────────────└──────────┘─┘        │
│            │  ────────────────────────────────────────────────────────────────────── │
│            │  ⚒ Working: Blacksmith Helper  ▓▓▓▓▓▓░░░ 2h12m left      [Collect when ready]│ ← card idle (Work)
└────────────┴─────────────────────────────────────────────────────────────────────────┘
```
**Por quê:** abre **já no que fazer** (cards de ação), não num menu. A ação dourada de cada card é o convite.
Tier de risco (🟢/🟡/🔴) no card = o jogador decide risco/recompensa **antes** de clicar (regra PvP). O card
idle do Trabalho fica no rodapé como "gancho de volta" sem competir com o loop ativo. **Cliques p/ aventurar: 1.**

### 7.2 COMBAT (resolução INSTANTÂNEA — adaptado ao seu modelo)

```
PRÉ-LUTA (escolha)                              PÓS-RESOLUÇÃO (1 clique resolveu tudo)
┌──────────────────────────────────┐           ┌──────────────────────────────────────┐
│  ▢ Dire Wolf        Lv14  🔥Fire │           │  ✦ VICTORY                            │
│  ❤▓▓▓▓▓▓▓▓ 120   ATK 22  DEF 8   │           │  ┌── Battle Log ──────────────────┐  │
│                                  │           │  │ You hit 14 · Wolf hits 6        │  │
│  Your odds: ~64%   ⚡10           │           │  │ Crit! 21 · Wolf evades          │  │
│  ┌──────────┐   ┌──────────┐     │           │  │ Wolf falls.                     │  │
│  │ ▓ Fight ▓│   │   Flee   │     │           │  └─────────────────────────────────┘  │
│  └──────────┘   └──────────┘     │           │  LOOT:  +45🪙  +60XP  ✦ Rare Gauntlets │
└──────────────────────────────────┘           │  [Equip ✓ better]  [▓ Fight again ▓]   │
                                                └──────────────────────────────────────┘
```
**Por quê:** seu combate é instantâneo (`BattleSimulator` devolve log + loot). Então a UI não precisa de
"action buttons" turno-a-turno — precisa de **decisão antes** (Fight/Flee com odds + custo) e **payoff
depois** (log + loot animado). **`[Fight again]`** é o pilar da economia de cliques: repetir a ação mais
comum sem voltar ao hub. **`[Equip ✓ better]`** aparece só se o drop for upgrade → resolve D7 (loot→vestir
em 1 clique). *(Engajamento: o "again" mantém o jogador no flow; é o que segura sessão longa.)*

### 7.3 CHARACTER (stats + equip + bag, numa tela)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  ◐ Kael — Warrior Lv12          XP ▓▓▓▓▓▓░░░░ 3,200/5,000                       │
│  ┌─ Equipped ────────────┐   ┌─ Attributes ──────── (+2 pts) ─┐                │
│  │ [Helm][Armor][Weapon] │   │ STR 18 [+]   CON 22 [+]        │                │
│  │ [Shld][Boot][Glove]   │   │ DEX 14 [+]   AGI 09 [+]        │                │
│  │ [Pants][Ring]         │   │ LUK 11 [+]   (respec free)     │                │
│  └───────────────────────┘   └────────────────────────────────┘                │
│  ┌─ Bag  (18/30) ───────────────────────────────[ Sell Junk ▾ ]──────────────┐ │
│  │ ⚔  🛡  👢  💍  🧪  ◇  ⚔  🪨  …   ← grid, borda=raridade, badge=qtd        │ │
│  │ ▢  ▢  ▢  ▢  (slots vazios tracejados)                                      │ │
│  └─────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
   clique no slot → painel de detalhe + [Equip]/[Sell]  (não só hover → mobile/Steam)
```
**Por quê:** equip + stats + bag na **mesma** tela (Albion-style "paper doll") elimina a navegação entre
sub-telas. Grid com cor de raridade = leitura instantânea do que é valioso (reconhecimento > ler nome).
**`[Sell Junk]`** (vende tudo Comum num clique, com confirmação) ataca o maior ladrão de cliques do gênero:
limpar inventário. *(Usabilidade: bag a 1 clique pela barra; vender lixo a 1 clique.)*

### 7.4 GATHERING — Fishing / Mining / Hunt (instantâneo, SEM timer)

```
┌──────────────────────────────────────────────────────────────────────┐
│  ⛏ IRON HILLS — Mining            🟢 SAFE        ⚡ cost 8   You have ⚡92 │
│  Possible finds:  🪨 Iron (common) · 💎 Crystal (rare) · 🔥 Fire Essence │
│  Mining skill ▓▓▓▓▓░░░ Lv6                                              │
│                                                  ┌────────────────────┐ │
│                                                  │   ▓  Mine  (⚡8)  ▓ │ │ ← 1 clique resolve
│                                                  └────────────────────┘ │
│  ── last haul ──  +3 🪨 Iron   +1 💎 Crystal   +20 XP   (faded toast)   │
└──────────────────────────────────────────────────────────────────────┘
```
**Por quê:** **sem barra de progresso/timer** — mostra custo de estamina + o que pode cair + um botão. O
"last haul" reforça o ganho sem modal interruptivo. Repetir = clicar de novo (estamina é o único limite).
*(Retenção: o ritmo rápido de clique→recompensa é o vício do idle; um timer aqui o destruiria.)*

### 7.5 SMITHING (craft)

```
┌───────────────────────────────────────────────────────────────────┐
│  ⚒ FORGE                                       Smithing ▓▓▓▓░░ Lv4  │
│  ┌─ Recipes ───────────┐  ┌─ Iron Sword (Lv10) ───────────────────┐ │
│  │ ▸ Iron Sword     ✓  │  │ Needs:  🪨 Iron x5 (have 7) ✓         │ │
│  │ ▸ Steel Helm    🔒  │  │         🔥 Coal x2 (have 0) ✗         │ │
│  │ ▸ Hunter Bow     ✓  │  │ Result: +6 ATK · success ~85%        │ │
│  │ …                   │  │              ┌──────────────────────┐  │ │
│  │                     │  │              │  ▓ Craft (need Coal) │  │ │
│  └─────────────────────┘  └──────────────└──────────────────────┘─┘ │
└───────────────────────────────────────────────────────────────────┘
```
**Por quê:** lista → detalhe → 1 botão. Materiais com **✓/✗ e "have X"** dizem na hora se dá pra criar (sem
fazer o jogador ir conferir a bag). Botão desabilita e **diz o que falta** ("need Coal") em vez de só ficar
cinza — reduz frustração. Só mostra recipes da classe (Archer vê arco, etc.), evitando ruído.

### 7.6 MARKET (Buy/Sell NPC) — acesso óbvio (regra de design)

```
┌──────────────────────────────────────────────────────────────────────┐
│  🏪 MARKET     [ Buy ]  [ Sell ]            🔎 search…    rotates in 4h │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │ ⚔ Steel Sword   +9 ATK   Lv12   🟦Rare    220🪙   ┌──────────┐    │ │
│  │ 🛡 Tower Shield  +7 DEF   Lv12   ⬜Common   90🪙   │ ▓ Buy  ▓ │    │ │
│  │ 🧪 Stamina Fish  +20⚡           ⬜Common   15🪙   └──────────┘    │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```
**Por quê:** Market é item **fixo na nav** (regra "acesso óbvio") — não enterrado em sub-aba como hoje.
Search + tabs Buy/Sell (regra core screen). Na demo, é o **NPC shop** (não o Auction de player, que fica
vazio sem base de jogadores → escondido). *(Engajamento: gastar ouro é um sink que dá meta de curto prazo.)*

### 7.7 PVP / ENTRADA EM ZONA DE RISCO (tela de decisão)

```
┌────────────────────────────────────────────────────────────┐
│  🔴 BLOODfen MARSH — HIGH RISK ZONE                          │
│  ┌─ You stand to GAIN ──────┐   ┌─ You RISK losing ────────┐ │
│  │ ++ resources (×2)        │   │ 50% resources            │ │
│  │ + rare essence chance    │   │ 15% bronze               │ │
│  │ + Monster Core           │   │ 35% chance: 1 item locked│ │
│  └──────────────────────────┘   └──────────────────────────┘ │
│  Flagged 1h · other players can raid you here                │
│            ┌────────────┐        ┌────────────┐              │
│            │   Cancel   │        │ ⚔ Enter (PvP)│ ← red       │
│            └────────────┘        └────────────┘              │
└────────────────────────────────────────────────────────────┘
```
**Por quê (regra PvP + retenção):** mostrar **ganho vs perda lado a lado, em cores** (verde vs vermelho)
ANTES de entrar é consentimento informado — reduz a raiva de "perdi item sem saber". Risco transparente é o
que faz PvP opcional funcionar (Albion/RuneScape Wilderness): quem entra **escolheu** o risco. Botão vermelho
sela a semântica. *(Retenção: perda inesperada = churn; perda consentida = tensão saudável.)*

---

## 8. Fluxos de usuário (user flows)

### 8.1 Onboarding — fisgar em 60 segundos

```
[Register/Login] → [Create Warrior: nome + 1 escolha simples (face/cor)] 
      │ (sem escolher classe ainda — nasce RECRUIT; classe é uma meta no Lv10)
      ▼
[Welcome overlay 1 tela]  "Spend stamina → fight & gather → loot → grow. Your path awaits at Lv10."
      │  [ ▓ Start your first quest ▓ ]   ← 1 botão, dourado
      ▼
[Adventure já com 1 quest DESTACADA (glow/coachmark "Start here")]
      ▼  (1 clique)  → COMBAT resolve → LOOT cai com juice → toast "+XP! +Loot!"
[Coachmark: "✦ You got an item — Equip it"]  → [Equip ✓] (1 clique, na própria tela de loot)
      ▼
[Level up! +2 attribute points]  → coachmark aponta o badge "(+2)" no Character
      ▼
[Loop aberto: "Adventure again" sempre visível]  → joga livre
```
**Por quê (retenção D1 — o número que mais importa):** o novato precisa **completar o loop inteiro
(ação→loot→equip→level)** guiado, **uma vez**, em <60s. Coachmarks (highlight + seta) em vez de um tutorial
de texto longo (ninguém lê). Adiar a escolha de classe pro Lv10 evita decisão pesada no minuto 1 e cria a
**primeira meta** ("chegar no Lv10 e escolher meu caminho"). *(Cada passo concluído é um micro-compromisso —
escada de comprometimento.)*

### 8.2 Loop central (o jogador "residente")
```
        ┌────────────────────────────────────────────────────┐
        ▼                                                    │
  ADVENTURE ──(1 clique)──► COMBAT/GATHER ──► LOOT+XP ──► [Equip se upgrade]
        ▲                          │                                   │
        │                          └──[Fight/Mine again]──┐            │
        │                                                 ▼            ▼
   (estamina acabou?) ──► MARKET (vender lixo / comprar) / FORGE / [Work idle p/ voltar depois]
```
**Por quê:** o loop tem que fechar em si mesmo com **mínimo de navegação**. "Again" mantém no flow; quando a
estamina zera (gate natural), o jogo **empurra** suavemente pros sinks (Market/Forge) e pro **gancho de
retorno** (Work idle → "volte em 2h"). Idle como ponte entre sessões = retorno D2.

---

## 9. Auditoria de cliques — antes × depois (a métrica que você pediu)

| Tarefa | Hoje (cliques) | Proposta | Como |
|---|---|---|---|
| Aventurar/lutar de novo | 4-5 (voltar World→reino→quest→send→collect) | **1** | `[Fight/Mine again]` na própria tela |
| Equipar item dropado | 4 (Character→bag→achar→equip) | **1** | `[Equip ✓ better]` na tela de loot |
| Abrir inventário | 2 (Character→rolar até bag) | **1** | `[🎒Bag]` na barra persistente |
| Curar HP | 3 (Temple→escolher→confirmar) | **1** | `[＋Heal]` quick action |
| Vender lixo (10 itens) | ~20 (1 por item) | **1** | `[Sell Junk]` (Comuns, c/ confirmação) |
| Ver stats de um item | hover (quebra no mobile) | **1 clique/foco** | painel de detalhe |

**Por quê:** cada clique economizado no loop mais repetido multiplica por centenas de repetições/sessão. É a
diferença entre "fluido" e "trabalhoso" — e fluidez percebida é o que separa um RPG de browser "bom" de um
"datado". *(Esta tabela é o critério de aceite da repaginada: se não baixou o nº de cliques, não terminou.)*

---

## 10. Layouts responsivos

```
DESKTOP / WIDESCREEN (≥1024px)                MOBILE (<768px)
┌──────────────────────────────────┐         ┌────────────────────┐
│ [barra persistente full]         │         │ ❤▓▓ ⚡▓▓ 🪙  [≡]   │ ← barra compacta
├──────┬───────────────────────────┤         ├────────────────────┤
│ nav  │  conteúdo (cards em 2 col) │         │  conteúdo          │
│ (5,  │                           │         │  (cards 1 coluna,  │
│ ícone│                           │         │   full-width)      │
│ +txt)│                           │         │                    │
└──────┴───────────────────────────┘         ├────────────────────┤
 nav lateral vertical                         │ 🗺  🛡  🏪  ⚒  ⚔ │ ← nav inferior (tab bar)
                                              └────────────────────┘
```
**Regras (e porquê):**
- **Mobile:** nav vai pro **rodapé** (tab bar) — alcance do polegar; cards em **1 coluna full-width**;
  alvos de toque ≥44px. *(A maior fatia de tráfego de itch/browser é mobile — se quebra no celular, perde
  metade do público antes de começar.)*
- **Desktop/Steam widescreen:** nav lateral; conteúdo em **2 colunas** (aproveita largura, evita linha de
  texto longa demais). Largura de leitura travada (~70ch).
- **Sem padrões só-de-browser** (regra Steam): nada de hover-only, nada de "abre em nova aba", nada de
  scroll infinito sem fim. Tudo navegável por **foco/Tab** (preparação p/ controle).

---

## 11. Prontidão para Steam (futuro)

| Requisito | Decisão de design | Porquê |
|---|---|---|
| Widescreen | Layout 2 colunas + max-width de leitura | Evita UI esticada/feia em 16:9/21:9 |
| Controle (futuro) | Tudo acessível por foco/`Tab`; nav 5-itens mapeável a D-pad/bumpers; sem hover-only (D8) | Hover não existe no controle; foco visível é obrigatório |
| Sem padrões de browser | Modais in-app (não popup do SO), sem "ctrl+F", tooltips por clique | Cliente Godot/Steam não tem o chrome do navegador |
| Estado sempre visível | Barra persistente HP/⚡/🪙 | Igual a HUD de console — o jogador nunca "perde" o status |

**Por quê:** desenhar agora com foco/teclado e sem hover-only significa que o port pro Godot **não exige
redesenho** — a mesma IA e os mesmos componentes migram. Custo zero hoje, economia grande amanhã.

---

## 12. Entregáveis — checklist e ordem de implementação

> Tudo abaixo é **CSS + reorganização de markup + JS de feedback** — **não** exige Godot. Ordem por impacto/esforço.

| Ordem | Pacote | Entregável | Impacto |
|---|---|---|---|
| 1 | **Tokens + tipografia** | Variáveis CSS (cores §4) + trocar Courier New (§5) | 🔴 Cara de jogo instantânea (D2/D3) |
| 2 | **Barra persistente** | HP/⚡/🪙 + `[Heal]`/`[Bag]` sempre visíveis (§2.2) | 🔴 D4 + cliques (heal/bag = 1) |
| 3 | **IA: 9→5 nav (regrouping, sem esconder)** | Reagrupar abas em 5 grupos (§2) — tudo continua acessível; curadoria/esconder ADIADO (§3) | 🔴 D1 |
| 4 | **Onboarding** | Welcome overlay + coachmarks no 1º loop (§8.1) | 🔴 D5 (retenção D1) |
| 5 | **Loop em 1 clique** | `[Fight again]`, `[Equip better]`, `[Sell Junk]` (§9) | 🟡 economia de cliques (D7) |
| 6 | **Juice** | Toast de loot, número subindo, glow em raridade, som curto (§6) | 🟡 D6 (sensação) |
| 7 | **Componentes + responsivo** | Card/badge/slot/tooltip-por-clique (§6) + mobile tab bar (§10) | 🟡 consistência + mobile/Steam |

**Critério de aceite global:** a tabela de cliques (§9) tem que cair para os números da coluna "Proposta".
Se os cliques não baixaram, a repaginada não cumpriu o objetivo — **bonito sem ser fluido não conta**.

---

## 13. Próximo passo sugerido
Aprovado o sistema, eu começo pelo **Pacote 1 (tokens + tipografia)** aplicado numa **tela-piloto** (sugiro
o **Adventure**, §7.1) — em cima do HTML/CSS que já existe, sem reescrever o `app.js`. Você vê a cara nova
numa tela, valida, e a gente propaga o sistema pro resto. *(Iterar em 1 tela antes de espalhar = corrige o
sistema barato, antes de pagar o custo de aplicá-lo 9 vezes.)*
