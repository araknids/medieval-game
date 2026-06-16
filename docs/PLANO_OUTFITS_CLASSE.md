# Roupas por Item (paper-doll + ícone de item) — [OUTFITS_CLASSE]

## Objetivo
Trocar o set único (Ranger) por **4 temas de armadura** do pack pago *Modular Character Outfits –
Fantasy* (Quaternius). O visual é do **PRÓPRIO ITEM** (não de quem veste) — **qualquer classe usa
qualquer item**. A armadura equipada aparece no boneco (DollView/BustView) E nos ícones de item
(mochila/loja/leilão/baú/forja/mail/slots) no tema do item.

> ⚠️ **v1 (descartada): tema por CLASSE de quem veste** → fazia o arqueiro ver TUDO como Ranger
> ("parece que só tem item de ranger"). Corrigido para **tema por item**.

## Tema do item (4 temas; Wizard fora)
Cada item tem `outfitTheme` FIXO (`KNIGHT/NOBLE/RANGER/PEASANT`), **determinístico pelo nome-base**
(soma de bytes % 4 — `InventoryService.outfitThemeFor`). Determinístico ⇒ o preview da loja/forja
bate com o item criado, e o front reproduz no fallback (mesma fórmula/ordem). A ideia: os **afixos do
item pendem pro atributo do tema** (Knight→STR, Ranger→DEX, Noble→LUK; Peasant=geral, sem viés) — então
o item "beneficia" a classe do tema, mas **sem trava de uso**.

| Tema | Cara | Afixo enviesado |
|---|---|---|
| **Knight** | placas de aço (armet, peitoral, greaves) | STR (guerreiro) |
| **Noble** | nobre (coroa, gorgeira, dourado) | LUK (mercador) |
| **Ranger** | couro/capuz | DEX (arqueiro) |
| **Peasant** | túnica simples (geral) | — |

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
### Backend
- `InventoryItem.outfitTheme` (coluna `outfit_theme varchar(16)`, migração no `SchemaMigrator`).
- `InventoryService.outfitThemeFor(name)` (estático, determinístico) + set no `buildItem` ANTES dos
  afixos; `rollAffixesFor` traz 1 afixo do atributo do tema pra frente (viés).
- DTOs expõem `outfitTheme`: `InventoryController` (com fallback p/ legado), `ShopController`,
  `AuctionService/Controller`, `SmithingController`, `MailController` (via `outfitThemeFor(itemName)`).

### Frontend
- `Outfits.gd` (`class_name Outfits`) — fonte única: `theme_for_item(it)` (usa `outfitTheme`, fallback
  `_theme_from_name` = mesma soma-de-bytes do backend), `piece_path_item`/`icon_path_item`,
  `is_armor_slot`. `_dir_for(base)` resolve o fallback peasant→ranger.
- `DollView.gd` / `BustView.gd` — `apply()` veste cada peça pelo tema do **item** (`piece_path_item`).
- `UiKit.item_icon_for` cobre **arma** (modelo 3D), **escudo** (`Shield_Heater`), **armadura** (peça do
  tema DO ITEM) e cai no ícone genérico do slot. (`current_class` virou dead code — pode sair depois.)
- `Character.gd` (`_equip_icon_tex`), `Forge.gd`, `Mail.gd` passam/usam `outfitTheme` por item.

## Pendências / notas
- Só **Male** (o herói é masculino). Female fica pra quando houver personagem feminino.
- Reabrir o Godot p/ importar os novos `.gltf`/`.png` (gera `.gltf.import` finais + `.png.import`).
- Wizard e variantes extra (Knight Horns/Spike/Scarf, Noble Gorget/Lion) ficaram de fora — hooks fáceis.
- Itens **legados** (sem `outfitTheme` no banco) recebem tema pelo fallback determinístico (não muda).
