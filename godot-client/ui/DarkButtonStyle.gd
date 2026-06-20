class_name DarkButtonStyle
extends RefCounted
# ── Botões NOBRES: moldura dourada + couro escuro (arte PixelLab, 9-slice) — [BOTAO_DARK] ──
# StyleBoxTexture 9-slice: a borda dourada fica FIXA (texture_margin) e só o miolo (couro) estica →
# a moldura NÃO distorce em botão de qualquer largura. Estados por modulate (hover quente / pressed
# afunda). Fallback: se o PNG não importou (ResourceLoader.exists), cai no StoneStyle (granito
# procedural) → a UI nunca quebra. Cache estático (1 conjunto de styleboxes p/ TODOS os botões).
# Uso: DarkButtonStyle.apply(btn).

const TEX_PATH := "res://assets/ui/btn_noble.png"
const MARGIN := 38        # borda dourada fixa do 9-slice (px na textura 192) — bordas não esticam

static var _cache := {}   # state(int) -> StyleBoxTexture
static var _tex: Texture2D = null
static var _checked := false

static func _texture() -> Texture2D:
	if not _checked:
		_checked = true
		if ResourceLoader.exists(TEX_PATH):
			_tex = load(TEX_PATH)
	return _tex

static func apply(btn: Button) -> void:
	if _texture() == null:
		StoneStyle.apply(btn)   # arte não importada ainda → granito procedural (UI não quebra)
		return
	btn.add_theme_stylebox_override("normal",  stylebox(0))
	btn.add_theme_stylebox_override("hover",   stylebox(1))
	btn.add_theme_stylebox_override("pressed", stylebox(2))
	btn.add_theme_stylebox_override("focus",   stylebox(1))
	btn.add_theme_color_override("font_color",         Color(0.93, 0.87, 0.72))
	btn.add_theme_color_override("font_hover_color",   Color(1.0, 0.96, 0.80))
	btn.add_theme_color_override("font_pressed_color", Color(0.80, 0.72, 0.56))

static func stylebox(state: int) -> StyleBoxTexture:
	if _cache.has(state):
		return _cache[state]
	var sb := StyleBoxTexture.new()
	sb.texture = _texture()
	sb.texture_margin_left = MARGIN; sb.texture_margin_right = MARGIN
	sb.texture_margin_top = MARGIN;  sb.texture_margin_bottom = MARGIN
	# centro estica (couro escuro = estica sem artefato visível); a moldura dourada fica fixa nos cantos
	sb.axis_stretch_horizontal = StyleBoxTexture.AXIS_STRETCH_MODE_STRETCH
	sb.axis_stretch_vertical = StyleBoxTexture.AXIS_STRETCH_MODE_STRETCH
	match state:
		1: sb.modulate_color = Color(1.18, 1.12, 0.95)   # hover: brilho quente
		2: sb.modulate_color = Color(0.78, 0.74, 0.66)   # pressed: afunda/escurece
	# texto dentro da moldura (não encosta no ouro); afunda 2px no pressed
	sb.content_margin_left = 20; sb.content_margin_right = 20
	sb.content_margin_top = 8 if state != 2 else 10
	sb.content_margin_bottom = 8 if state != 2 else 6
	_cache[state] = sb
	return sb
