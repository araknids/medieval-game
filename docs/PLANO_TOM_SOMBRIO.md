# PLANO — Tom Sombrio (Grimdark Coerente) [TOM_SOMBRIO]

> **Status:** 📝 PLANEJAMENTO (2026-06-23) — discutido, ainda **não** implementado.
>
> **Diferencial do jogo (norte):** *"Um Shakes & Fidget que quer te ver morto."* Mesmo esqueleto de RPG
> idle/browser (estamina, ranking, guilda, PvP/PvE), mas com **tom sombrio e cruel** — algo que o veterano
> cômico **estruturalmente não pode** ser (comédia é a marca dele). É a **única arena onde o concorrente de
> 15 anos não consegue te seguir**. Toda decisão de design responde a uma pergunta: *isso serve ao tom sombrio?*

---

## Referência visual (NORTE) — CRPG sombrio dos anos 90
O dono pensa no tom como **"aqueles RPGs antigos sombrios"** — referência concreta dada: **Betrayal at Krondor**
(1993, Dynamix; mundo Riftwar de Raymond E. Feist). Isso **refina** o sombrio: **NÃO é grimdark-gore**
(Darkest Dungeon / Diablo). É **alta fantasia sombria, pintada e melancólica**, no enquadramento de
**manuscrito iluminado / tomo antigo**. Marcadores da capa que viram direção de arte:

- **Moldura de tomo gravado** — bordas douradas ornamentadas + medalhões de canto (a capa é toda emoldurada
  em filigrana dourada). → **lever de UI barato e MUITO distintivo**: ornamentar cards/painéis (já há
  `StyleBoxFlat` com borda — dá pra somar canto/moldura ornamental).
- **Pintado, abafado, soturno** — castelo em chamas, figura encapuzada, vulto alado ao fundo. Cor muda, zero
  neon/alegre.
- **Tipografia de display serifada, gravada e pesada** — o logo "Betrayal at Krondor" **É exatamente** a fonte
  de display que o Tier 1 pede.
- **Narrativa madura** — traição, guerra, moral cinza; não heroísmo limpo.

> **⚠️ Correção de paleta (importante):** o **ouro NÃO é o inimigo.** O ouro de **gilt / manuscrito iluminado**
> é *central* nesse visual. **Não matar o dourado — ENVELHECER** (menos saturado/brilhante, gilt antigo) e deixar
> a **escuridão pintada + a moldura ornamentada + a fonte** carregarem o tom. A "Stone & Ember" atual já está
> perto. O **carmim-sangue** entra como o acento "vivo"/perigo; o **ouro fica** como ornamento/gilt/tesouro.

> **Síntese dos dois pedidos do dono:** o **enquadramento / UI / mundo** = tomo sombrio de alta fantasia (Krondor);
> o **combate** é onde o **sangue estilizado** (Tier 2) aparece. São compatíveis: ornamentado e soturno por fora,
> brutalidade no golpe. Casa com [[project_steam_page]] (anti-asset-flip via *mood* forte e coerente).

---

## Por que isto (e não mais features)

Auditoria honesta: o jogo tem **breadth demais** (PvP, guilda, torre, leilão, VIP, Mercador Azul…) para
**zero jogadores** e um loop central ainda não validado. O lançamento no **itch.io** (esta semana) julga a
**primeira impressão** — não a daily de 7 dias, não a guerra de território. Este plano **não adiciona sistemas**:
é uma **passada de coerência de tom** que reposiciona o que já existe e ataca exatamente o que o jogador vê nos
primeiros 10 minutos (fonte, cor, texto). **Reenquadrar e repintar — não reconstruir.**

---

## Decisões confirmadas ✅

1. **Intensidade = Sombrio ESTILIZADO.** Sangue, brutalidade implícita, atmosfera opressiva — **sem víscera
   explícita**. Mantém a Steam content survey tranquila, público amplo, arte mais barata. Porta aberta para
   escalar o gore depois, **se** o jogo validar.
2. **Aconchego = CONTRASTE QUENTE.** Não apagar toda a ternura: a **Luna continua adorável de propósito**.
   Num mundo que quer te matar, a única coisa que te ama vira a mais poderosa da tela — o contraste **aprofunda**
   o sombrio em vez de enfraquecê-lo. Regra: a ternura é **rara, frágil e ameaçada**, nunca o tom default.
3. **Escopo do itch (esta semana) = só o Tier 1** (fonte + paleta + cópia). O gore de combate (Tier 2) vem
   **depois** que o loop provar que segura alguém.

---

## Tier 1 — barato, ANTES do itch (a primeira impressão mora aqui)

### 1.1 Tipografia
Hoje o texto usa a **fonte padrão do Godot** (só há `assets/fonts/NotoEmoji-VariableFont_wght.ttf`). Não existe
fonte de display. Esse é o interruptor de tom mais barato que existe.

**Regra de ouro:** UMA fonte de display assustadora **só** para títulos/cabeçalhos; UMA fonte de corpo **muito
legível** para o resto. Grimdark ilegível é só UX ruim.

- **Display (títulos/seções):** serifa condensada brutal ou gótico erodido. Candidato: **Cinzel** (capitulares
  romanas gravadas — sombrio e imponente). Blackletter puro **evitar** (ilegível + costuma furar acentos PT).
- **Corpo:** serifa/slab limpa e legível. Candidato: **EB Garamond** (clássico, pesado) ou slab equivalente.
- ⚠️ **VERIFICAR antes de cravar:** cobertura de **acentos PT** (ã, ç, õ, á, ê…) e glifos EN da fonte escolhida —
  o jogo lança **bilíngue PT+EN** ([[project_godot_i18n]], `i18n/Lang.gd`).

**Onde mexe (centralizado):**
- Fonte de **corpo**: tema padrão do projeto (Project Settings → `gui/theme/custom_font`) **ou** um
  `Theme.tres` único. Pega todas as telas de uma vez.
- Fonte de **display**: `ui/UiKit.gd` aplica os títulos. Hoje o título do `scaffold()` faz só
  `add_theme_font_size_override("font_size", 24)` + cor `GOLD` ([UiKit.gd](../godot-client/ui/UiKit.gd) ~L160-169).
  Adicionar `add_theme_font_override("font", HEADER_FONT)` ali (+ nos cabeçalhos de seção) propaga o display por
  todo o app sem editar tela por tela.
- Arquivos novos em `godot-client/assets/fonts/` (licença open/SIL — checar antes de commitar).

### 1.2 Paleta (de "taverna quente" para "cruel e fria")
A paleta já é escura, mas **quente** — dourado de taverna acolhedor. O sombrio vem de **esfriar e sangrar** os
acentos. Tudo num arquivo: as constantes do [UiKit.gd](../godot-client/ui/UiKit.gd#L28-L44) + o tint do
`_BG_SHADER`.

| Constante (hoje) | Valor atual | Direção sombria |
|---|---|---|
| `GOLD` (títulos/gilt) | `0.96, 0.66, 0.26` (dourado quente, vivo) | **MANTER o ouro**, mas ENVELHECER (~`0.80, 0.62, 0.30` gilt antigo, menos brilho) — ver Referência visual (gilt de manuscrito) |
| acento de ação/perigo | usa `WARN`/`ERR` | **carmim-sangue** como único destaque vivo (`ERR` já é avermelhado — unificar a linguagem do "vivo" no sangue) |
| `TEXT` (corpo) | `0.87, 0.83, 0.74` pergaminho | **manter** (legibilidade > vibe; nunca branco puro, já está bom) |
| `TINT_*` (fundos) | quase-pretos levemente quentes | esfriar/escurecer; reduzir o calor (menos marrom, mais cinza-azulado de chumbo) |
| `_BG_SHADER` tint | `0.10, 0.085, 0.105` | escurecer + aumentar a vinheta (`vig`) → mais opressão nas bordas |

Sem mudança de estrutura — só os valores das constantes e do uniform `tint`. Impacto enorme, custo mínimo.
`StoneStyle.gd`/`DarkButtonStyle.gd` herdam o clima dos botões; revisar a borda dourada do CTA para casar.

### 1.3 Cópia & nomes (o maior mentiroso de tom)
Texto alegre desmente o sombrio mais que qualquer cor. Passada de reescrita em `i18n/Lang.gd` (front) **+** texto
do backend (i18n de mensagens). **Mecânica idêntica — só o invólucro muda.**

| Hoje (fofo) | Sombrio (exemplo) |
|---|---|
| "Daily Reward" / presente 🎁 | **"Espólios"** / **"Tributo"** (caixa → saco de butim) |
| "Taverna" (cerveja alegre) | covil sórdido; cerveja = "afogar a noite", "esquecer os mortos" |
| nomes de monstro/quest neutros | linguagem de carniça, ferrugem, presságio |
| flavor de vitória/derrota | derrota = real e custosa; vitória = suja, não heroica |

> **Contraste quente (decisão 2) na prática:** a **Luna** mantém cópia/arte ternas — é o ponto de luz. O resto
> do mundo é frio. Não "grimificar" a Luna; ela existe **para contrastar**.

### 1.4 Moldura & régua de PEDRA RÚNICA (identidade visual — NORTE: Krondor + tablete rúnico)

> **STATUS: ⏸️ PARADO (pós-itch).** Decisão 2026-06-24: faltando 5 dias pro lançamento (domingo), o sistema
> de moldura de pedra é **chrome** — não decide retenção. Mantém-se a **régua dourada (fio de 1px)** como está
> (já lê com o gilt envelhecido da nova paleta). Os 2 tiles-piloto ficam em `godot-client/assets/ui/frame/`
> como semente p/ retomar **depois** de validar o loop. O tempo dos 5 dias vai pro **onboarding** (Round 4).

O dono quer trocar a **régua dourada** (hoje `ColorRect` de 1px, `Color(0.78,0.65,0.36,0.35)` em
`UiKit.scaffold` ~[L188](../godot-client/ui/UiKit.gd#L188)) por uma **barra de PEDRA com runas gravadas** —
algo antigo, no espírito da **moldura de tomo** do Krondor + o **tablete rúnico** de referência (pedra cinza
rachada, musgo, runas futhark, **glow quente atrás das fendas** → casa com o gilt). Material = **pedra**
(referência do dono; lê bem em UI pequena).

**Princípio que resolve "as pedras têm que se parecer" (preocupação explícita do dono):**
- **MESMA pedra, runa diferente.** A variação aleatória mora **só na runa gravada**; o bloco de pedra
  (material, paleta, direção de luz, glow nas fendas) é **idêntico** em toda variante. A moldura vira
  **uma peça só**, não um patchwork de rochas que não combinam.
- **Tileável / seamless:** segmentos com mesma altura e bordas que casam → sem emenda visível.
- **Geração consistente (PixelLab):** mesmo prompt-base + paleta fixa + tamanho fixo + luz fixa, variando só
  a runa; usar a imagem do **tablete rúnico como referência de estilo** + o personagem-base do projeto como
  âncora de paleta ([[reference_pixellab_assets]]).

**Montagem (Godot):**
- **Régua (1º alvo):** beam horizontal de pedra — `NinePatchRect` (cantos gravados + meio que tila) **ou**
  TextureRect montando N segmentos rúnicos lado a lado, sorteando a variante por instância.
- **Moldura de painel (depois):** `NinePatchRect`/`StyleBoxTexture` (4 cantos + 4 bordas) envolvendo o
  `card()` atual. Helpers novos: `UiKit.rune_bar(w)` / `UiKit.ornate_frame(panel)`.
- **Tileset PixelLab:** corner (1–2) + edge_h (3–4 runas) + edge_v (3–4 runas) + beam da régua.

**Pildoto-primeiro (padrão comprovado do dono [[project_inventario_recursos_gif]]):** gerar a **régua + 1
corner + 1 edge**, fiar a régua na tela, **aprovar o estilo**, só então gerar o resto. Disciplina de deadline:
começar **só pela régua** (aparece em toda tela, barato, alto impacto). Moldura completa = se sobrar tempo.

---

## Tier 2 — o gore de verdade, DEPOIS de validar o loop

Não fazer antes do itch. É o ápice do tom, mas é **produção real**.

- **Feedback de combate = sangue (estilizado).** É onde "sangue e desmembramento" literalmente vivem. No combate
  3D do Godot ([Battle.gd](../godot-client/Battle.gd)/[BattleReplay.gd](../godot-client/BattleReplay.gd)):
  partícula de sangue no acerto, splatter na tela no crítico, morte com **ragdoll** (já há experimentos —
  [[project_battle_animation]]). **Estilizado**, conforme decisão 1: respingo e queda, sem víscera modelada.
- **Direção de arte (PixelLab):** monstros mais grotescos, ambientes opressivos, personagem sombrio. Coerente com
  o set de ícones pixel escuros já existente ([[reference_ui_icons]], `ui/Icons.gd`).

---

## Princípio de design — "Contraste Quente"
A ternura **não é proibida; é rara e ameaçada.** Use uma fresta de calor (a Luna, um NPC gentil que morre, uma
fogueira) para fazer a crueldade **doer mais**. Regra prática: se um elemento é fofo, ele tem que estar **sob
ameaça** ou ser **frágil** no mundo — nunca um tom default alegre e seguro.

---

## Ordem sugerida (Tier 1, commitável em pedaços)
1. **Paleta** — editar constantes do `UiKit.gd` + tint do `_BG_SHADER`. Menor diff, maior impacto imediato.
2. **Tipografia** — escolher fontes (verificar acentos PT), adicionar em `assets/fonts/`, ligar corpo (tema do
   projeto) + display (`UiKit.scaffold`).
3. **Cópia/nomes** — passada em `Lang.gd` + i18n do backend (mantendo PT+EN). Maior superfície, fazer por telas.

---

## Pendências / a verificar na implementação
- Licença + cobertura de glifos (PT+EN) das fontes candidatas **antes** de commitar.
- Confirmar onde a borda dourada do CTA (`DarkButtonStyle`) precisa mudar p/ casar com a paleta nova.
- A Luna: localizar a apresentação dela no front (interrupção é backend `KingdomService` [[project_medieval_game]]).
- Não tocar em mecânica/balance — este plano é **puramente** de apresentação/tom.

## Impacto em docs / CLAUDE.md
- CLAUDE.md: registrar a **direção de arte/tom** (sombrio estilizado + contraste quente) como identidade do jogo.
- Casa com [[project_demo_ui]] (repaginada de UI) e [[project_steam_page]] (anti-asset-flip via mood/curadoria —
  um tom forte e coerente É o argumento anti-asset-flip).
