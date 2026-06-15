class_name ScrollStyle
extends RefCounted
# ── Tema PERGAMINHO (papel enrolado em volta da "realeza") — [PERGAMINHO_UI] ─────────
# Texturas PixelLab em assets/ui/ usadas como StyleBoxTexture 9-slice:
#   scroll_panel.png  → moldura de painel (rolos de madeira em cima/baixo, papel no meio)
#   scroll_button.png → corpo do botão (papel com bordas enroladas)
#   seal.png          → selo de cera (coroa) usado como ÍCONE nos botões de ação
# Fallback: enquanto o Godot não importou os PNG (sem .import), cai no StoneStyle/flat —
# a UI nunca quebra. load() em runtime + ResourceLoader.exists (não preload). [STUCK_FIX-style]

const PANEL_PATH  := "res://assets/ui/scroll_panel.png"
const BUTTON_PATH := "res://assets/ui/scroll_button.png"
const SEAL_PATH   := "res://assets/ui/seal.png"

static var _btn_tex: Texture2D = null
static var _panel_tex: Texture2D = null
static var _seal_tex: Texture2D = null
static var _loaded := false
static var _cache := {}   # int(state)/"panel" -> StyleBoxTexture (1× p/ todos)

static func _ensure() -> void:
	if _loaded:
		return
	_loaded = true
	if ResourceLoader.exists(BUTTON_PATH): _btn_tex = load(BUTTON_PATH)
	if ResourceLoader.exists(PANEL_PATH):  _panel_tex = load(PANEL_PATH)
	if ResourceLoader.exists(SEAL_PATH):   _seal_tex = load(SEAL_PATH)

static func ready() -> bool:
	_ensure()
	return _btn_tex != null

# ── Botões ───────────────────────────────────────────────────────────────────────
static func apply(btn: Button) -> void:
	_ensure()
	if _btn_tex == null:
		StoneStyle.apply(btn)   # pergaminho ainda não importado → pedra
		return
	btn.add_theme_stylebox_override("normal",  _btn_box(0))
	btn.add_theme_stylebox_override("hover",   _btn_box(1))
	btn.add_theme_stylebox_override("pressed", _btn_box(2))
	btn.add_theme_stylebox_override("focus",   _btn_box(1))
	btn.add_theme_color_override("font_color",         Color(0.34, 0.22, 0.10))
	btn.add_theme_color_override("font_hover_color",   Color(0.48, 0.26, 0.06))
	btn.add_theme_color_override("font_pressed_color", Color(0.26, 0.17, 0.10))

# Selo de cera (coroa) à direita do texto — p/ botões de ação proeminentes.
static func add_seal(btn: Button) -> void:
	_ensure()
	if _seal_tex == null:
		return
	btn.icon = _seal_tex
	btn.expand_icon = true
	btn.icon_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	btn.add_theme_constant_override("icon_max_width", 22)

static func _btn_box(state: int) -> StyleBoxTexture:
	if _cache.has(state):
		return _cache[state]
	var sb := StyleBoxTexture.new()
	sb.texture = _btn_tex
	var m := 34   # 9-slice: borda enrolada do papel (fixa); centro estica
	sb.texture_margin_left = m; sb.texture_margin_right = m
	sb.texture_margin_top = m; sb.texture_margin_bottom = m
	sb.axis_stretch_horizontal = StyleBoxTexture.AXIS_STRETCH_MODE_STRETCH
	sb.axis_stretch_vertical = StyleBoxTexture.AXIS_STRETCH_MODE_STRETCH
	sb.content_margin_left = 16; sb.content_margin_right = 16
	sb.content_margin_top = 8 if state != 2 else 10
	sb.content_margin_bottom = 10 if state != 2 else 8
	match state:
		1: sb.modulate_color = Color(1.12, 1.07, 0.94)   # hover: papel iluminado
		2: sb.modulate_color = Color(0.82, 0.76, 0.66)   # pressed: papel sombreado
	_cache[state] = sb
	return sb

# ── Painel (moldura de rolo) ────────────────────────────────────────────────────
# Retorna o StyleBoxTexture do pergaminho, ou null se não importado (caller usa fallback).
static func panel_box() -> StyleBoxTexture:
	_ensure()
	if _panel_tex == null:
		return null
	if _cache.has("panel"):
		return _cache["panel"]
	var sb := StyleBoxTexture.new()
	sb.texture = _panel_tex
	# rolos de madeira ocupam ~56px em cima/baixo; bordas laterais finas (~30px). Centro estica.
	sb.texture_margin_top = 56; sb.texture_margin_bottom = 56
	sb.texture_margin_left = 30; sb.texture_margin_right = 30
	sb.axis_stretch_horizontal = StyleBoxTexture.AXIS_STRETCH_MODE_STRETCH
	sb.axis_stretch_vertical = StyleBoxTexture.AXIS_STRETCH_MODE_STRETCH
	# conteúdo respira dentro do papel (mais em cima/baixo p/ não encostar nos rolos)
	sb.content_margin_left = 20; sb.content_margin_right = 20
	sb.content_margin_top = 34; sb.content_margin_bottom = 34
	_cache["panel"] = sb
	return sb
