# Plano — Fundo de menu dinâmico + duelo do jogador [MENU_FUNDO]

## Objetivo
Dar vida à tela inicial (login + menu): **a cada abertura do jogo** o mapa de fundo muda, e
**dois guerreiros duelam** sobre ele. Antes de logar, os dois lados são aleatórios; **depois de
logar, o lado esquerdo é o personagem do jogador** (com a arma real equipada).

## O que já existia
- `App.gd` montava **1 fundo 3D persistente** atrás de todas as telas, **fixo no `"castle"`**.
- `MenuFx.bg_3d(scenario)` constrói o cenário (via `Scenery.gd` — 7 mapas) + vinheta, e **só
  no `"castle"`** adicionava o `MenuDuel`.
- `MenuDuel.gd` spawnava **2 Rangers fixos**, com **seed de RNG fixa** (mesma luta sempre) e
  armas fixas.
- `PaperDollLive.gd` já sabe vestir o rig com o equip real (`/api/inventory`).
- `Weapons.weapon_kind(nome, categoria)` mapeia item do backend → modelo de arma; `attach_weapon`.

## Decisões
- **Mapas no sorteio:** só os **4 fechados** (`castle, arena, city, dungeon`) — câmera olha pra
  dentro e enquadra bem os lutadores. Abertos (mining/beach/garimpa) ficam de fora (árvores cruzam
  a câmera fixa). Pode ampliar depois.
- **Sorteio = por abertura:** o cenário é escolhido **1x no boot** (`App._ready`), fixo durante a
  sessão. Próxima abertura → outro mapa.
- **Logado:** esquerda = você (sua **arma real**: tipo + raridade/brilho); direita = oponente
  aleatório. *(Como só há 1 set de roupa — Ranger — o que personaliza visualmente é a ARMA; o
  sistema já fica pronto pra quando houver mais sets.)*
- **Deslogado:** os dois lados aleatórios (arma melee aleatória + raridade + coreografia por seed
  aleatória).
- **Arma aleatória = só melee** (sword/greatsword/axe/spear/mace): as animações do duelo são de
  espada. A arma real do jogador pode ser arco (mostra o arco mesmo com swing melee — decoração).

## Implementação (3 arquivos)
1. **`MenuDuel.gd`** — vira reativo:
   - `_ready()`: só luz de preenchimento + `_rng.randomize()` (coreografia/arma diferentes a cada
     abertura). Não spawna sozinho — o App dirige.
   - `setup()` (async): limpa os lutadores e remonta conforme `Api.token`. Logado → busca a arma
     equipada (`_player_loadout` lê `/api/inventory`) p/ a esquerda; senão aleatório. Direita sempre
     aleatória. Guarda de **geração** (`_gen`) aborta um `setup` antigo se outro começar (relogin).
   - `_spawn(pos, yaw, weapon_kind, weapon_rarity)` (antes só `rarity`, sempre "sword").
2. **`MenuFx.gd`** — `bg_3d` adiciona o `MenuDuel` em **qualquer** cenário (não só castle) e guarda
   o ref em `svc.set_meta("menu_duel", duel)` p/ o App.
3. **`App.gd`** — `randomize()` + escolhe `scenario` aleatório dos 4; `_route()` chama
   `_refresh_duel()` (→ `duel.setup()`) p/ atualizar os lutadores **a cada login/logout**. Renomeia
   `_castle_bg` → `_menu_bg`.

## Robustez
- Se faltar asset/arma/inventário, o duelo só não aparece (o menu continua) — padrão defensivo já
  usado no `MenuDuel`/`MenuFx`.
- `setup()` é idempotente (limpa antes de remontar); `_process` já guarda `_fighters.size() < 2`.

## Limites conhecidos / futuro
- 1 só set de roupa (Ranger) → personagens não diferem por armadura ainda; quando houver mais sets,
  `setup()` já está pronto p/ vestir o equip real (reusa a lógica do `PaperDollLive`).
- Animações só de espada → arco fica com swing melee (cosmético).
