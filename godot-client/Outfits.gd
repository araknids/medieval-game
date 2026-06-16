class_name Outfits
extends RefCounted
# ── Mapa de roupas por CLASSE (paper-doll + ícone de item) [OUTFITS_CLASSE] ──────────
# A armadura equipada aparece no TEMA da classe do personagem:
#   WARRIOR→Knight · MERCHANT→Noble · ARCHER→Ranger · RECRUIT/geral→Peasant (Wizard fora por enquanto)
# Pack: "Modular Character Outfits - Fantasy" (Quaternius). Peças em assets/outfits/<tema>/,
# ícones 2D renderizados em assets/outfits/icons/. Usado por DollView, BustView e UiKit.

# classId (enum estável do backend: warriorClassId) → pasta do tema
const THEME_FOR_CLASS := {
	"WARRIOR":  "knight",
	"MERCHANT": "noble",
	"ARCHER":   "ranger",
	"RECRUIT":  "peasant",
}
const DEFAULT_THEME := "peasant"

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

# pasta da peça derivada do basename (resolve o fallback peasant→ranger sozinho)
static func _dir_for(base: String) -> String:
	if "Knight" in base: return "knight"
	if "Noble" in base: return "noble"
	if "Ranger" in base: return "ranger"
	return "peasant"

static func _base(class_id: String, slot: String) -> String:
	var theme: String = theme_for_class(class_id)
	var m: Dictionary = SLOT_PIECE.get(theme, {})
	return str(m.get(slot, ""))

# caminho do .gltf da peça p/ vestir o paper-doll ("" se o slot não tem peça)
static func piece_path(class_id: String, slot: String) -> String:
	var base := _base(class_id, slot)
	if base == "":
		return ""
	return "res://assets/outfits/%s/%s.gltf" % [_dir_for(base), base]

# caminho do .png do ícone 2D da peça p/ a UI ("" se não tem)
static func icon_path(class_id: String, slot: String) -> String:
	var base := _base(class_id, slot)
	if base == "":
		return ""
	return "res://assets/outfits/icons/%s.png" % base

static func is_armor_slot(slot: String) -> bool:
	return ARMOR_SLOTS.has(slot)
