# Plano — Retrato animado do inimigo na tela da Torre [TORRE_PREVIEW]

## Objetivo
Mostrar um **retrato pixel-art animado** (idle em loop) do inimigo que te espera, na tela da
Torre — tanto no **lobby** (quem vem no próximo andar) quanto no **card do andar** (run ativa).
Pedido do dono: arte de qualidade no PixelLab, uma "gifzinha" de cada inimigo, no canto do card
(ele marcou "HERE" no topo-direito do card de entrar).

## Validação de UI/UX (designer)
- **Colocação primária = card do ANDAR** (`_render_floor`), à **direita** do texto: o card já sabe o
  inimigo exato (nome/stats/atmosfera) e tem espaço horizontal sobrando. O texto (número do andar →
  nome do chefe → stats) fica **flush-left** (hierarquia de leitura); o retrato é floreio → vai na
  borda de fuga. Inimigo à direita "encara" o bloco de stats à esquerda.
- **Lobby** (onde o dono marcou "HERE"): o endpoint só devolvia `{active:false}`, sem inimigo. Em vez
  de descartar, **estendi o payload** com o próximo andar → o lobby mostra o retrato de quem vem.
- **Sem rolagem**: o retrato (124–140px) é **≤ altura do bloco de texto** ao lado → não cresce o card.
- **Layout**: card vira `HBox[ VBox(texto, EXPAND) , moldura(retrato) ]`; o botão **⚔ Lutar / Entrar**
  continua **full-width abaixo** do HBox (nunca estreitado pelo retrato).
- **MVP** (andares 10/20/30/40/50): moldura **dourada** + selo **BOSS**; comuns = moldura cinza.

## Escopo da arte — 10 sprites (não 50)
As 50 fases = **5 zonas de 9 comuns + 1 MVP a cada 10** (ver `TowerFloors.java`). Dentro de uma zona,
os 9 comuns são variações de UM arquétipo (ex.: Zona 1 = "guarda caída"). O jogador vê **um andar por
vez** → 1 arquétipo por zona lê certo. Os 5 MVPs são marcos de história → arte própria. Total: **10**.
- `zone1` (1–9) — guarda morta da guarnição (Gate Sentry…)
- `zone2` (11–19) — nobre cortesão podre (Gilded Wretch…)
- `zone3` (21–29) — acólito do culto de sangue (Bleeding Acolyte…)
- `zone4` (31–39) — guarda transformado pela sombra do Rei (The Becoming…)
- `zone5` (41–49) — espectro quase-humano do Limiar (The Undecided…)
- `mvp10` The Fallen Captain · `mvp20` The Coin-Eaten · `mvp30` The Crowned Echo ·
  `mvp40` The Xamã (Oren) · `mvp50` Rei Arka

Promover um comum específico a sprite única depois é trivial (sem mexer no layout) — só se uma zona
parecer repetitiva no playtest.

## Mapeamento andar → chave (i18n-proof)
Por **número do andar**, não pelo nome (que é localizado PT/EN):
`_tower_art_key(floor, is_mvp)` → `"mvp%d"` se MVP (múltiplo de 10), senão `"zone%d"` com
`zone = (floor-1)/10 + 1`.

## Pipeline PixelLab
- **Personagem**: `create_character` mode **v3** (qualidade máxima), `view="side"`, `size=64`,
  `single color outline`, `high detail`, humanoid. ~2 gen cada.
- **Animação**: `animate_character` template `breathing-idle`, **só `south`** (1 gen) — idle sutil que
  preserva a pose. Re-roll barato com `mode="v3"` + `action_description` se a template ficar ruim.
- **Download**: quadros `south` do idle → `godot-client/assets/ui/tower/<key>/f0.png … fN.png`.
- Custo: ~10 sprites × (2 + 1) ≈ **30 generations** (orçamento da conta: ~4600). Folgado.

## Integração Godot
- **`ui/TowerPreview.gd`** (`class_name TowerPreview`, extends `TextureRect`): cicla
  `res://assets/ui/tower/<key>/fN.png` em loop (`_process`, 8 fps, `TEXTURE_FILTER_NEAREST`).
  Fallback em cascata: vários quadros → 1 estático (`<key>.png`) → `null` (quem chama esconde).
  `TowerPreview.make(key, px)` é a fábrica.
- **`ui/Tower.gd`**: `_tower_art_key()` + `_enemy_portrait(key, is_mvp, border)` (alcova escura
  emoldurada; MVP dourado + selo BOSS; fallback `Icons.rect("tower")` enquanto a arte não existe).
  `_render_floor` e `_render_lobby` viraram 2 colunas.
- **`TowerController.getCurrent`**: sem run ativa, devolve `nextFloor`/`isMvp`/`bossName`/`highestFloor`
  (o `enter()` começa em `towerBestFloor+1`). `active:false` preservado → testes antigos passam.

## Verificação
- Backend: `TowerServiceTest` + `TowerIntegrationTest` verdes (payload aditivo).
- Godot (reabrir p/ importar a arte): lobby e card do andar mostram o retrato à direita, animado;
  MVP com moldura dourada + BOSS; sem rolagem; fallback de ícone antes da arte existir.
