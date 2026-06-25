# Plano — Mapa "Torre Amaldiçoada" (a capa, no gráfico do jogo) — [MAPA_TORRE]

## Objetivo
Criar **um cenário 3D novo** que **evoca a capa** (Crown of Aravok): clareira sombria
estilo o "bosque" (`mining`), mas com uma **torre gótica em ruína queimando à direita**
(o lado de onde o inimigo entra) e o chão coberto de **escombros de batalha** — armas
fincadas, soldados tombados e uma **fera morta** (como o bicho no canto da capa).

Não é cópia 1:1 da pintura — é **clima**. Tudo procedural com primitivas + assets que já
existem (`assets/world/nature`), **sem asset novo**.

## Decisões (grilladas com o dono, 2026-06-25)
- **Onde:** os DOIS — fundo vivo do menu/login **e** mapa de batalha da **Fortaleza** (`fortress`).
- **Bosque (`mining`):** **mantido**; a Torre é cenário NOVO, à parte.
- **Fidelidade:** **evocar** o clima (primitivas), não copiar a pintura.
- **Prioridade:** feito antes do lançamento do itch (aposta de 1ª impressão / tom sombrio).

## Composição (eixos)
- `+X` = DIREITA = a **torre** (em `(16, 0, -3)`) e o lado do **inimigo** na batalha.
- `+Z` = FRENTE = a **câmera** → fica ABERTO (sem árvore na frente).
- Mata MORTA (DeadTree/Pine) só num **arco de fundo/esquerda** (~108°–332°), pulando a
  torre (+X) e a câmera (+Z). Centro LIVRE p/ os lutadores (igual aos outros mapas).

## Peças (em `Scenery.gd`)
- `cursed_tower_lighting(host)` — céu de tempestade quase preto, **fresta de luz fria**
  rompendo as nuvens (key light), névoa de fumaça quente. O **calor laranja vem do FOGO
  da torre** (não do sol).
- `cursed_tower(host, rng, combat_r)` — chão queimado + caminho de pedra + arco de mata
  morta + a torre + escombros + braseiros + brasas subindo.
- `_dark_tower(host, rng, base)` — pilha de blocos de pedra quase preta **afinando pra
  cima** (gótico), **topo quebrado** (merlons faltando), pináculos tortos, **contrafortes**
  na base, **frestas de brasa** (janelas acesas) e **fogo no topo** (`_tower_fire`) + fumaça.
- `_tower_fire` — brasa emissiva grande + **OmniLight quente forte** (banha a cena de
  laranja, o "glow" da capa) + chama de partícula + flicker.
- `_embers` — brasas/cinzas alaranjadas subindo (vende o incêndio).
- Escombros: `_planted_sword`, `_planted_spear`, `_war_shield`, `_fallen_soldier`,
  `_dead_beast` (todos via `_pivot` + `_box3`, impressionistas).
- Reuso: `_ground`, `_cobble_path`, `_brazier`, `_smoke`, `_flat`, `_place`, `_scatter`,
  `_billboard_mat` + novo `_tree_arc` (anel de árvores só num setor).

## Integração (call sites)
- `Scenery.build()` — novo case `"cursed_tower"`.
- `BattleReplay.SCENARIOS` — entra no sorteio.
- `BattleReplay.SCENE_TO_MAP` — **`fortress → cursed_tower`** (era `castle`): toda luta da
  Fortaleza Maldita roda nesse mapa.
- `App.MENU_MAPS` — vira `["cursed_tower"]`: o fundo do menu/login passa a ser a "capa viva".
  (Reversível: re-adicionar castle/arena/city/dungeon volta o sorteio.)
- `MenuFx.bg_3d` — câmera própria do `cursed_tower` (olha do front-esquerda pra direita →
  a torre fica à direita do quadro, o duelo do menu em primeiro plano).
- `World.SCENARIOS` + câmera do viewer — pra pré-visualizar com ← →.

## Tuning aberto (placeholders)
Posição/altura da torre, energia/alcance da luz do fogo, densidade de escombros, ângulos do
arco de mata e enquadramento das câmeras (menu/viewer) — todos fáceis de ajustar no playtest.
