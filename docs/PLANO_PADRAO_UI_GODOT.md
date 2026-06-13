# Padrão de UI do cliente Godot — kit "Stone & Ember" [PADRAO_UI_GODOT]

> Fonte: direção de arte do modelo **Fable**. Objetivo: toda tela interna parecer parte do
> **Hub** (menu) e melhorar a usabilidade pensando que é um RPG. Toda UI é montada em código
> (os `.tscn` são só um `Control` + script), então o padrão vive em helpers reutilizáveis.

## Arquivo central: `ui/UiKit.gd`
`class_name UiKit extends RefCounted`, tudo `static` (mesmo padrão do `StoneStyle`, com caches
estáticos). Ao migrar uma tela, **deletar** os helpers locais dela (`_card/_act/_section/_dim/
_spacer/_show_error` e `RARITY_COL`) — a tela passa a ter só lógica de domínio + chamadas ao kit.

## 1. Fundo
**Sem 3D nas telas internas** (`MenuFx.bg_3d` = SubViewport + Scenery + tween, refeito a cada
`App._open` → trava a navegação ×25, e cena 3D atrás de texto denso atrapalha leitura). 3D fica
só no **Login + Hub**. Telas internas: **1 `ColorRect` + 1 shader cacheado** (gradiente + grão de
pedra estático + vinheta), zero textura, zero animação por frame.

`static func bg(screen, tint := Color(0.10,0.085,0.105))` — ColorRect no índice 0, FULL_RECT,
mouse IGNORE. Shader (cachear o `Shader` num `static var`): banda mais clara no 1/3 superior +
grão por hash + vinheta (mesma curva do MenuFx).

**Tints por categoria** (cada tela passa o seu):
| Categoria | tint |
|---|---|
| Personagem (Character, Inventory, Abilities, Achievements) | `Color(0.095,0.09,0.115)` |
| Aventura (World, Work, Temple) | `Color(0.085,0.10,0.09)` |
| Batalha (Tower, Arena) | `Color(0.115,0.08,0.075)` |
| Comércio (Shop, Forge, Auction, Stash, Tavern, Vip) | `Color(0.115,0.095,0.07)` |
| Social (Guild, Mail, Daily) | `Color(0.08,0.09,0.11)` |

## 2. Scaffold
`static func scaffold(screen, title, on_back, on_refresh, tint) -> Dictionary`
→ `{content, status, header, back, refresh, wallet, scroll}`.

Monta: `bg()` → root VBox → **header** (Margin L16/R16/T12 → HBox sep 10): back stone `←` (48×40),
título Label font **24** dourado `(0.96,0.66,0.26)` + outline 6, **wallet** Label font 13 à direita,
refresh stone `↻` (44×40) → **régua dourada** 1px `(0.78,0.65,0.36,0.35)` → **status** font 13 altura
reservada 22 → **scroll** (`follow_focus=true`) → Margin (L/R 16, T10, B20) → **content** VBox sep 10.
Coluna máx **920 px** centrada (via `screen.resized`).

`set_wallet(wallet, w)` → `"❤%d%%  ⚡%d  ·  🥇%d 🥈%d 🥉%d"`; ⚡ vermelho se <25, ❤ vermelho se KO.
`flash(status, text, kind)` (0 info / 1 ok ✅ verde / 2 err ❌ vermelho). `err_text(r)` (corpo do
antigo `_show_error`). `confirm(host, text, confirm_label, on_yes, danger)` — modal procedural.

## 3. Component kit (valores exatos)
Paleta (constantes no UiKit; mata os 4 `RARITY_COL` duplicados):
```
GOLD=(0.96,0.66,0.26) GOLD_SOFT=(0.78,0.65,0.36) BRONZE=(0.40,0.32,0.20)
TEXT=(0.87,0.83,0.74) TEXT_DIM=(0.62,0.58,0.52) OK=(0.55,0.80,0.50) ERR=(0.94,0.42,0.38) WARN=(1.0,0.76,0.0)
RARITY=[(.72,.72,.75),(.45,.85,.45),(.4,.6,1),(.78,.45,.95),(1,.8,.35)]
```
Regra: cor de texto via `add_theme_color_override("font_color",…)` — **nunca `modulate`** (empilha
com o modulate de card desabilitado e lava a cor).

- **card(border=BRONZE, enabled=true) → [PanelContainer, VBox]**: bg `(0.115,0.10,0.12,0.92)`, border 1
  (2 se rarity≥4) cor border alpha .65, radius 4, margin 12, shadow `(0,0,0,.45)` size 4 off (0,2); VBox sep 4.
  Disabled: modulate `(1,1,1,.55)` + border `(0.3,0.3,0.3,.5)`.
- **section(text)**: igual `Hub._section_header` (CAPS font 15 GOLD_SOFT + régua), com spacer 8 embutido.
- **action / action_big / action_danger / icon_btn**: tudo `StoneStyle.apply`. action 130×40 f15;
  big 160×48 f18; danger + font `(0.92,0.55,0.48)`; icon 44×40 f18 (atributo `+` 36×36).
- **kv(key, value, value_col=TEXT)**: key min-w 170 f14 DIM; value f14.
- **body(text)** f14 TEXT autowrap · **dim(text)** f12 DIM autowrap.
- **item_row(it, actions[[label,cb,danger?]])**: card border=rarity; nome f16 cor rarity; sub f12 DIM;
  stats f12 `(0.62,0.75,0.58)`; botões 120×36 f13; rarity≥4 border 2.
- **bar(label, value, maxv, fill, suffix)**: ProgressBar h16 sem %; bg `(0.05,0.045,0.06)` border1; fill cor.
  HP `(0.70,0.22,0.20)` · Stamina `(0.36,0.65,0.38)` · XP `(0.42,0.50,0.85)`.
- **empty(text, hint)**: card centrado, linha1 f14 DIM, linha2 (dica) f12 GOLD_SOFT.
- **spacer(h=8)**, **rarity_color(r)**.

## 4. Tipografia/espaço
Título 24 · Seção 15 CAPS · Nome card/item 16 · Corpo 14 · Meta/sub 12 · Status 13.
Margens 16 (header T12) · coluna máx 920 · separações: content 10, dentro do card 4, seção +8 embutido.

## 5. Usabilidade RPG — princípios
1. **Carteira sempre no header** (❤⚡🥇🥈🥉) — jogo é gateado por estamina/bronze.
2. **Custo no botão, motivo no disabled** (`"⚔ Entrar (25⚡)"`, disabled `"Requer Nv 10"`/`"Sem estamina"`).
3. **Confirmar o irreversível** (`confirm`): vender rarity≥3/equipado, reforjar, Arka, cancelar leilão.
4. **Feedback consistente** via `flash()` (✅/❌); nunca limpar status em silêncio.
5. **Empty states que ensinam** (`empty()` com onde conseguir).
6. **Controller**: `follow_focus` + `grab_focus` no 1º botão de ação após render.

### Fixes priorizados
**P0** — Inventory: `"Vender (%d🥇)"` usa OURO mas `sellPrice` é **bronze** → 🥉 + confirm rarity≥3.
Tower "Entrar" luta sem avisar → `"⚔ Entrar e lutar (25⚡)"` + colapsar log atrás de botão.
World: custo ⚡ no rótulo + gate de nível no disabled + chevron ▸/▾ nos cards de reino.
Character: `valor/cap` da classe + efeito por ponto (`"❤ CON 14/60 · +8 HP/pt"`); `+` 36×36 stone.
Shop: preço em ERR quando não pode pagar (vem do wallet).
**P1** — Forge: tem/precisa colorido por ingrediente + `successPct` colorido (≥80 verde/50–79 WARN/<50 ERR);
var morta `bar_name` em `_refine_card`. Tavern: Timer 4s pollando o feed. Tower: destacar a própria
linha do ranking com bg. Shop: comprados vão pro fim. Varrer todas pra `flash()`/`err_text()`.
**P2** — lendário: shadow dourado glow. Fade-in do content (0.15s). Timer da Loja WARN <10min.
Achievements bloqueados com barra de progresso.

## 6. Ordem de rollout
1. `UiKit.gd` + **Character** (piloto, valida o visual). 2. **Inventory + Shop** (item_row/confirm/rarity/wallet + fix 🥇). 3. **World** (maior tráfego). 4. **Forge + Tower + Arena** (P0/P1). 5. Lote mecânico: Temple, Work, Tavern(+Timer), Auction, Stash, Guild, Mail, Daily, Abilities, Achievements, Vip. 6. **Hub**: trocar `_section_header` local por `UiKit.section`.
