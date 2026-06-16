# Roupas por Classe (paper-doll + ícone de item) — [OUTFITS_CLASSE]

## Objetivo
Trocar o set único (Ranger) por **4 temas de armadura** do pack pago *Modular Character Outfits –
Fantasy* (Quaternius), escolhidos pela **classe** do personagem. A armadura equipada aparece no
boneco (DollView/BustView) E nos ícones de item (mochila/loja/leilão/baú/forja/slots) no tema certo.

## Mapa classe → tema (Wizard fora por enquanto)
| Classe (`warriorClassId`) | Tema | Cara |
|---|---|---|
| `WARRIOR` | **Knight** | placas de aço (armet, peitoral, greaves) |
| `MERCHANT` | **Noble** | nobre (coroa, gorgeira, dourado) |
| `ARCHER` | **Ranger** | couro/capuz (set original) |
| `RECRUIT` / default | **Peasant** | túnica simples (geral — "ajuda qualquer build") |

Peasant não tem elmo/ombreira no pack → cai pro capuz/ombreira **Ranger** (fallback).

## Slot (ItemType) → peça por tema
`ARMOR→Body(_Armor)` · `GLOVES→Arms` · `BOOTS→Feet(_Armor/_Boots)` · `PANTS→Legs(_Armor)` ·
`HELMET→Head(_Armet/_Crown/_Hood)` · `SHOULDER→Acc_Pauldron`. (RING/NECKLACE/WEAPON/SHIELD fora.)

## Pipeline de assets
- `godot-client/tools/import_outfits.py` — copia as peças **Male** (Knight/Noble/Peasant; Ranger já
  estava) + texturas do pack pra `assets/outfits/<tema>/` e gera o `.gltf.import` com o **retarget
  Humanoid_map** (`uid://dlsjipayxuj0q`), igual à Ranger. Ossos do pack são Unreal (`hand_l`, `spine_*`,
  `pelvis`, `root`) → o retarget casa com o `GeneralSkeleton`.
- `godot-client/tools/gltf_to_icon.py` — Blender headless (EEVEE_NEXT, 128², transparente) renderiza
  cada peça (skinned, **pose REST**) num ícone 2D frontal. Enquadra por **percentil 8–92% dos
  vértices** (os outliers do rig estouravam o bbox). Saída em `assets/outfits/icons/<peça>.png`.

## Código
- `Outfits.gd` (novo, `class_name Outfits`) — fonte única do mapa: `theme_for_class`, `piece_path`
  (gltf p/ vestir), `icon_path` (png p/ UI), `is_armor_slot`. O `_dir_for(base)` deriva a pasta do
  basename (resolve o fallback peasant→ranger sozinho).
- `DollView.gd` / `BustView.gd` — `apply(inv, class_id)` veste pela classe via `Outfits.piece_path`.
- `Shell.gd` — passa `warriorClassId` nos `_bust.apply(...)`; `update_topbar` seta `UiKit.current_class`.
- `UiKit.gd` — `current_class` (estática) + `item_icon_for` agora cobre **arma** (modelo 3D),
  **escudo** (`Shield_Heater`), **armadura** (peça do tema da classe) e cai no ícone genérico do slot.
- `Character.gd` — `_equip_icon_tex` mostra o render certo em cada slot equipado; mochila usa
  `item_icon_for`; passa a classe pro doll.

## Pendências / notas
- Só **Male** (o herói é masculino). Female fica pra quando houver personagem feminino.
- Reabrir o Godot p/ importar os novos `.gltf`/`.png` (gera `.gltf.import` finais + `.png.import`).
- Wizard e variantes extra (Knight Horns/Spike/Scarf, Noble Gorget/Lion) ficaram de fora — hooks fáceis.
