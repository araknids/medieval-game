extends RefCounted
# ── Helper de ÍCONES de UI ────────────────────────────────────────────────────────
# Carrega os PNGs de assets/ui/icons/ por NOME (ex.: "world", "gold", "attr_strength").
# Se o ícone ainda não existe, cai no fallback (mantém o emoji do label) — assim dá pra
# fiar tudo já e os ícones que faltam aparecem sozinhos quando forem gerados. [MIGRACAO_GODOT]
# Uso: const Icons := preload("res://ui/Icons.gd"); Icons.label_button(b, "world", "🌍 Mundo")

const DIR := "res://assets/ui/icons/"

# Textura do ícone `key`, ou null se não houver arquivo.
static func tex(key: String) -> Texture2D:
	var p := DIR + key + ".png"
	if ResourceLoader.exists(p):
		return load(p) as Texture2D
	return null

# Seta o ícone num Button (escala pro tamanho do botão). Retorna true se aplicou.
static func set_icon(b: Button, key: String) -> bool:
	var t := tex(key)
	if t == null:
		return false
	b.icon = t
	b.expand_icon = true
	b.add_theme_constant_override("h_separation", 8)
	return true

# Button com ícone + texto. label_with_emoji = "🌍 Mundo": com ícone vira ícone + "Mundo";
# sem ícone, mantém "🌍 Mundo" (fallback). Retorna o próprio botão.
static func label_button(b: Button, key: String, label_with_emoji: String) -> Button:
	if set_icon(b, key):
		var i := label_with_emoji.find(" ")
		if i >= 0:
			b.text = label_with_emoji.substr(i + 1).strip_edges()
		else:
			b.text = label_with_emoji
	else:
		b.text = label_with_emoji
	return b

# ── Emoji-marcador → ícone PixelLab [ICONES_MARCADOR] ───────────────────────────────
# Quando um texto de UI começa com um emoji que é ÍCONE (cabeçalho de seção, título de card,
# linha de recompensa, badge), trocamos pelo ícone. Inline no meio de frase NÃO entra aqui.
const EMOJI_ICON := {
	"🎒": "carried", "🔒": "locked", "🎁": "gift", "📦": "package",
	"💀": "skull", "☠": "skull", "💎": "gem", "⭐": "star", "⚠": "warning",
	"⏳": "hourglass", "🐟": "fish", "🎣": "map_fishing", "⛏": "map_mines",
	"🔎": "act_pan", "🏃": "act_flee",
	"👑": "node_boss", "📜": "node_event", "⚔": "node_combat", "🗡": "node_combat",
	"🛡": "slot_shield", "🔥": "elem_fire", "🏆": "achievements", "🥇": "gold",
	"🍺": "tavern", "🏰": "map_fortress", "🌍": "world", "🔨": "forge",
	"💰": "gold", "🪙": "bronze", "🥈": "silver", "🥉": "bronze",
	"❤": "hp", "⚡": "stamina", "🏅": "achievements", "🎯": "tower",
	"📬": "mail", "📭": "mail", "📩": "mail", "📨": "mail", "✉": "mail",
}

# Separa um emoji-ícone do INÍCIO do texto. Retorna [icon_key, resto] — ["", texto] se não houver.
# Tira o seletor de variação (U+FE0F) p/ casar "⚔️"/"⚠️" etc.
static func split_emoji(text: String) -> Array:
	var t := text.strip_edges()
	var sp := t.find(" ")
	if sp <= 0:
		return ["", text]
	var head := t.substr(0, sp).replace("️", "")
	if EMOJI_ICON.has(head) and tex(EMOJI_ICON[head]) != null:
		return [EMOJI_ICON[head], t.substr(sp + 1).strip_edges()]
	return ["", text]

# ItemType (backend) → arquivo de ícone do slot. ARMOR=peito, PANTS=perna, SHOULDER reusa o peito.
const ITEM_TYPE_ICON := {
	"WEAPON": "slot_weapon", "SHIELD": "slot_shield", "HELMET": "slot_helmet",
	"ARMOR": "slot_chest", "PANTS": "slot_legs", "BOOTS": "slot_boots",
	"GLOVES": "slot_gloves", "SHOULDER": "slot_chest", "RING": "slot_ring",
	"NECKLACE": "slot_necklace",
}

# Textura do ícone de um ITEM pelo seu type (ex.: "WEAPON" → slot_weapon.png). null se não mapeado.
static func item_tex(item_type: String) -> Texture2D:
	var key: String = ITEM_TYPE_ICON.get(item_type.to_upper(), "")
	if key == "":
		return null
	return tex(key)

# TextureRect pronto pra HUD (recurso/atributo). size em px; null-safe (volta um TextureRect vazio).
static func rect(key: String, px := 24) -> TextureRect:
	var tr := TextureRect.new()
	tr.texture = tex(key)
	tr.custom_minimum_size = Vector2(px, px)
	tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	tr.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return tr
