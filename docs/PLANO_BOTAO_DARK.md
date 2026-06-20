# PLANO — Fundo de botão dark realista (9-slice, sem esticar) [BOTAO_DARK]

## Problema
O dono quer um fundo de botão **mais realista/dark** (arte pixel no PixelLab). Mas vários botões são
**muito largos** (nav, `action_big`, botões que ocupam a largura de um card) → uma textura única
**esticada** distorce a arte.

## Solução: 9-slice (nine-patch)
A arte vira um **`StyleBoxTexture` 9-slice**: os **cantos/bordas ficam FIXOS** e só o **miolo** estica
(ou **tilea**). Assim a borda nunca distorce, em botão de qualquer tamanho. **Isso já é o mecanismo do
projeto**: o `StoneStyle.gd` atual usa exatamente isso (3-slice com bisel fixo + centro **TILE**), e o
tema pergaminho (`ScrollStyle`) usou 9-slice de PixelLab. Só trocamos a textura **procedural de granito**
por uma **textura dark do PixelLab**, aplicada do mesmo jeito.

## Como funciona hoje (reuso)
`UiKit._btn` chama `StoneStyle.apply(btn)` → `StyleBoxTexture` com:
- `texture_margin_*` = largura do bisel (borda fixa do 9-slice);
- `axis_stretch_horizontal/vertical = TILE` → o centro **repete** (não estica) → botão largo OK;
- estados via `modulate`: hover = mais quente/claro, pressed = mais escuro + texto afunda;
- cache estático (1 stylebox p/ TODOS os botões). Tamanhos de botão: 36–48px de altura, largura variável
  (alguns ocupam a largura toda → é o caso "muito grande").

## Pieces

### 1. Arte PixelLab (1 textura quadrada, ~192px)
Um **painel de botão dark** com **borda biselada** (metal/couro gasto) + **centro escuro** levemente
texturizado. Borda **moderada** (~16–22px no 192) pra botão fino (36px) ainda respirar (aprendizado
[PERGAMINHO_UI]: borda grossa demais aperta o 9-slice). Receita: `create_map_object`, quadrado,
`single outline`, `detailed shading`, `high detail`, prompt "dark grimdark UI button panel, beveled
border, deep shadows, rim light". Opcional: variante **pressed** (bisel invertido) — ou só `modulate`.

### 2. `DarkButtonStyle.gd` (espelha StoneStyle/ScrollStyle)
- `apply(btn)` → `StyleBoxTexture` normal/hover/pressed a partir do PNG; `texture_margin_*` = borda;
  centro **TILE** (se a textura tiver grão) ou **STRETCH** (se o centro for liso); hover/pressed por
  `modulate` (+ `content_margin` afunda no pressed). Cache estático.
- **Fallback**: `ResourceLoader.exists(png)` → se o PNG não foi importado pelo Godot ainda, cai no
  `StoneStyle.apply` (a UI **nunca quebra**; mesmo padrão do ScrollStyle).

### 3. Fiação (trocar StoneStyle→DarkButtonStyle nos chokepoints)
1 linha em cada: `UiKit._btn` (pega action/action_big/action_danger/small_btn/icon_btn — **todos**),
`UiKit.gd:527` (clickable_card), `MenuFx.gd` (login), `Shell.gd` (heal + nav), `BattleReplay.gd`.
Alternativa mais limpa: `StoneStyle.apply` continua o nome chamado, mas internamente tenta a dark e cai
no granito — aí **zero** mudança nos call sites. (Decidir na implementação.)

## Decisões (suas)
- **Material/vibe** do dark: ferro gasto · couro escuro · obsidiana/pedra escura · madeira escura
  (gero a opção escolhida; já gerei uma de **ferro** como ponto de partida pra você ver).
- Centro do 9-slice: **TILE** (texturizado) vs **STRETCH** (liso) — escolho pela textura.
- Substituir o StoneStyle **globalmente** (vira o novo visual padrão) — recomendado.

## Verificação
Reabrir o Godot (importa o PNG) → ver botões de vários tamanhos (nav largo, `action_big`, `small_btn`
fino, `icon_btn` quadrado): a **borda não distorce** em nenhum; hover/pressed reagem; fallback no
granito se o PNG não importou. Sem mudança de backend.

## Notas
- A arte tem que ter **borda simétrica** nos 4 lados (9-slice). Se a vista do PixelLab vier com leve
  perspectiva, compenso com `texture_margin` por-lado (left/right/top/bottom independentes).
- Performance: 1 textura + cache (igual StoneStyle) → custo zero por botão.
