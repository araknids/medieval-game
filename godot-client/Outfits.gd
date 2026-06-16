class_name Outfits
extends RefCounted
# ── Mapa de roupas por ITEM (paper-doll + ícone de item) [OUTFITS_CLASSE] ────────────
# O visual da armadura é do PRÓPRIO ITEM (não de quem veste) — qualquer classe usa qualquer item.
# Cada item tem um tema FIXO (backend `outfitTheme`: KNIGHT/NOBLE/RANGER/PEASANT); a ideia é os bônus
# do item favorecerem a classe do tema (Knight→guerreiro etc.), mas sem trava de uso.
# Pack: "Modular Character Outfits - Fantasy" (Quaternius). Peças em assets/outfits/<tema>/,
# ícones 2D em assets/outfits/icons/. Usado por DollView, BustView e UiKit.

# classId (enum do backend) → tema "natural" da classe (só p/ telas que queiram um tema base; o
# visual do item NÃO depende disso — ver theme_for_item).
const THEME_FOR_CLASS := {
	"WARRIOR":  "knight",
	"MERCHANT": "noble",
	"ARCHER":   "ranger",
	"RECRUIT":  "peasant",
}
const DEFAULT_THEME := "peasant"
# ordem ESPELHA o backend InventoryService.OUTFIT_THEMES (fallback determinístico bate com o servidor)
const THEME_ORDER := ["knight", "noble", "ranger", "peasant"]

# tema → slot (ItemType) → basename da peça. Peasant não tem elmo/ombreira no pack → cai pro Ranger
# (o basename Male_Ranger_* resolve a pasta sozinho via _dir_for).
const SLOT_PIECE := {
	"knight": {
		"ARMOR":    "Male_Knight_Body_Armor",
		"GLOVES":   "Male_Knight_Arms",
		"BOOTS":    "Male_Knight_Feet_Armor",
		"PANTS":    "Male_Knight_Legs_Armor",
		"HELMET":   "Male_Knight_Head_Armet",
		"SHOULDER": "Male_Knight_Acc_Pauldron_Round",
	},
	"noble": {
		"ARMOR":    "Male_Noble_Body",
		"GLOVES":   "Male_Noble_Arms",
		"BOOTS":    "Male_Noble_Feet",
		"PANTS":    "Male_Noble_Legs",
		"HELMET":   "Male_Noble_Head_Crown",
		"SHOULDER": "Male_Noble_Acc_Pauldron",
	},
	"ranger": {
		"ARMOR":    "Male_Ranger_Body",
		"GLOVES":   "Male_Ranger_Arms",
		"BOOTS":    "Male_Ranger_Feet_Boots",
		"PANTS":    "Male_Ranger_Legs",
		"HELMET":   "Male_Ranger_Head_Hood",
		"SHOULDER": "Male_Ranger_Acc_Pauldron",
	},
	"peasant": {
		"ARMOR":    "Male_Peasant_Body",
		"GLOVES":   "Male_Peasant_Arms",
		"BOOTS":    "Male_Peasant_Feet",
		"PANTS":    "Male_Peasant_Legs",
		"HELMET":   "Male_Ranger_Head_Hood",      # peasant sem elmo → capuz ranger
		"SHOULDER": "Male_Ranger_Acc_Pauldron",   # peasant sem ombreira → ombreira ranger
	},
}

# slots de armadura que este sistema veste/icona (RING/NECKLACE/WEAPON/SHIELD ficam de fora)
const ARMOR_SLOTS := ["ARMOR", "GLOVES", "BOOTS", "PANTS", "HELMET", "SHOULDER"]

static func theme_for_class(class_id: String) -> String:
	return THEME_FOR_CLASS.get(class_id.to_upper(), DEFAULT_THEME)

# Fallback determinístico p/ quando o item não trouxe outfitTheme (legado/algum DTO): soma dos bytes
# UTF-8 do nome % 4 — MESMA fórmula do backend (InventoryService.outfitThemeFor) → resultado bate.
static func _theme_from_name(name: String) -> String:
	if name == "":
		return DEFAULT_THEME
	var sum := 0
	for b in name.to_utf8_buffer():
		sum += int(b)
	return THEME_ORDER[sum % THEME_ORDER.size()]

# Tema (pasta) de um ITEM: usa o `outfitTheme` do backend; cai no fallback pelo nome se faltar.
static func theme_for_item(it: Dictionary) -> String:
	var t := str(it.get("outfitTheme", "")).to_lower()
	if SLOT_PIECE.has(t):
		return t
	return _theme_from_name(str(it.get("name", "")))

# pasta da peça derivada do basename (resolve o fallback peasant→ranger sozinho)
static func _dir_for(base: String) -> String:
	if "Knight" in base: return "knight"
	if "Noble" in base: return "noble"
	if "Ranger" in base: return "ranger"
	return "peasant"

static func _base(theme: String, slot: String) -> String:
	var m: Dictionary = SLOT_PIECE.get(theme, {})
	return str(m.get(slot, ""))

# ── por TEMA (folder) ──
static func piece_path_theme(theme: String, slot: String) -> String:
	var base := _base(theme, slot)
	return "res://assets/outfits/%s/%s.gltf" % [_dir_for(base), base] if base != "" else ""

static func icon_path_theme(theme: String, slot: String) -> String:
	var base := _base(theme, slot)
	return "res://assets/outfits/icons/%s.png" % base if base != "" else ""

# ── por ITEM (o caso padrão — visual vem do item) ──
static func piece_path_item(it: Dictionary, slot: String) -> String:
	return piece_path_theme(theme_for_item(it), slot)

static func icon_path_item(it: Dictionary, slot: String) -> String:
	return icon_path_theme(theme_for_item(it), slot)

static func is_armor_slot(slot: String) -> bool:
	return ARMOR_SLOTS.has(slot)
