# Plano — Busto animado do inimigo na tela da Torre [TORRE_PREVIEW]

## Objetivo
Mostrar um **busto pixel-art animado** (close-up, idle em loop) do inimigo na tela da Torre — no
**lobby** (quem te espera no próximo andar) e no **card do andar** (run ativa). Pedido do dono:
retrato em zoom com **pose característica** (ex.: o tesoureiro jogando uma moeda) e **um único por
andar** (50 inimigos distintos).

## Escopo da arte — 50 bustos (1 por andar)
Cada um dos 50 andares (ver `TowerFloors.java`) tem o **seu** busto, com uma pose que combina com o
inimigo (o tesoureiro joga moeda, o xamã ergue o cajado, o cultista levanta a adaga, o Rei Arka
ajoelhado com a coroa…). MVP a cada 10 (10/20/30/40/50) = chefes de história, com moldura dourada +
selo BOSS. As 50 descrições estão em `docs/previews/_busts.js` (floor, name, mvp, desc).

> **Histórico**: a v1 fez 10 sprites de **corpo inteiro** (5 arquétipos de zona + 5 MVPs) via
> `create_character`. O dono pediu **busto com zoom** + **um por andar**, então trocou-se por 50
> bustos animados (abaixo). Os 10 antigos foram removidos.

## Mapeamento andar → chave (i18n-proof)
Por **número do andar** (o nome do inimigo é localizado PT/EN): `_tower_art_key(floor, _is_mvp)` →
`"f%d" % floor`. Arte em `godot-client/assets/ui/tower/f<andar>/f0..f4.png`. A moldura dourada do MVP
é decidida em `_enemy_portrait` pelo `is_mvp`, não pela chave.

## Pipeline PixelLab (validado)
- **Busto**: `create_map_object` (ilustração única, estática), `view="side"` (eye-level), `132x140`,
  `single color outline`, `detailed shading`, `high detail`. Descrição: *"Pixel art bust portrait,
  head and chest close-up, of <pose característica>, grim dark fantasy"*. ~1-2 gen, ~30-90s.
- **Animação**: `animate_object` mode **v3**, `frame_count=4` (idle curto → guarda **5** quadros:
  ref + 4), `"subtle idle, gentle breathing, slight sway"`. O map object vira um "object" animável.
  ~1 gen, ~1-6 min. O MVP do tesoureiro tem anim própria (moeda girando).
- **Download**: zip `https://api.pixellab.ai/mcp/objects/<id>/download` → extrai
  `animations/*/unknown/frame_*.png` → `assets/ui/tower/f<andar>/f0..f4.png`. Script `docs/previews/_dl.sh`
  (idempotente, lê `docs/previews/_ids.txt` = floor↔object_id).
- **Limites da conta (Tier 2)**: **10 jobs concorrentes** + **rate-limit de burst** (~4-5/vez) →
  gerar em ondas. Custo total ~150 generations.

## Integração Godot
- **`ui/TowerPreview.gd`** (`class_name TowerPreview`, extends `TextureRect`): cicla os fN.png em loop
  (`_process`, 6 fps, `TEXTURE_FILTER_NEAREST`). Fallback: estático → ícone da torre.
- **`ui/Tower.gd`**: `_tower_art_key()` (→ `fN`) + `_enemy_portrait(key, is_mvp, border)` (alcova
  escura emoldurada; MVP dourado + selo BOSS). `_render_floor` e `_render_lobby` em 2 colunas
  (texto à esquerda + busto à direita; CTA full-width abaixo). Sem rolagem.
- **`TowerController.getCurrent`**: sem run ativa, devolve `nextFloor`/`isMvp`/`bossName`/`highestFloor`
  (o `enter()` começa em `towerBestFloor+1`) p/ o lobby mostrar o busto. `active:false` preservado.

## Verificação
- Backend: `TowerServiceTest` + `TowerIntegrationTest` verdes (payload aditivo).
- Galeria de revisão: `docs/previews/torre_bustos.html` (50 bustos animados; abre no navegador).
- Godot (reabrir p/ importar a arte): lobby + card do andar mostram o busto animado à direita;
  MVP com moldura dourada + BOSS; fallback de ícone antes da arte importar.
