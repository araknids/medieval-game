class_name DarkButtonStyle
extends RefCounted
# ── Botões dark NOBRES: couro escuro texturizado + borda dourada FINA (procedural) — [BOTAO_DARK] ──
# Tudo gerado por código (sem PixelLab): o PixelLab não entregava um botão com borda dourada fina e
# UNIFORME de forma confiável (saía ornamentado e esticava feio, ou um quadrado sem borda). Aqui a
# borda é uma moldura dourada FINA e reta, desenhada na textura → no 9-slice ela fica IDÊNTICA esticada
# em qualquer largura (linha continua linha), e cabe em botão fino (borda ~9px). Centro = grão de couro
# escuro (tilea). Estados por modulate (hover quente / pressed afunda). Cache estático. Uso: apply(btn).

const S := 96          # lado da textura
const BORDER := 9      # largura da moldura dourada (px) = margin do 9-slice (não estica)
const SEED := 23

static var _cache := {}    # state(int) -> StyleBoxTexture
static var _tex: Texture2D = null

static func _texture() -> Texture2D:
	if _tex == null:
		_tex = ImageTexture.create_from_image(_image())
	return _tex

static func apply(btn: Button) -> void:
	btn.add_theme_stylebox_override("normal",  stylebox(0))
	btn.add_theme_stylebox_override("hover",   stylebox(1))
	btn.add_theme_stylebox_override("pressed", stylebox(2))
	btn.add_theme_stylebox_override("focus",   stylebox(1))
	btn.add_theme_color_override("font_color",         Color(0.93, 0.87, 0.72))
	btn.add_theme_color_override("font_hover_color",   Color(1.0, 0.96, 0.80))
	btn.add_theme_color_override("font_pressed_color", Color(0.82, 0.74, 0.58))

static func stylebox(state: int) -> StyleBoxTexture:
	if _cache.has(state):
		return _cache[state]
	var sb := StyleBoxTexture.new()
	sb.texture = _texture()
	sb.texture_margin_left = BORDER; sb.texture_margin_right = BORDER
	sb.texture_margin_top = BORDER;  sb.texture_margin_bottom = BORDER
	# borda (margin) fica FIXA; o centro (couro) tilea → grão constante, sem esticar/borrar
	sb.axis_stretch_horizontal = StyleBoxTexture.AXIS_STRETCH_MODE_TILE
	sb.axis_stretch_vertical = StyleBoxTexture.AXIS_STRETCH_MODE_TILE
	match state:
		1: sb.modulate_color = Color(1.18, 1.12, 0.96)   # hover: brilho quente (tocha no ouro)
		2: sb.modulate_color = Color(0.74, 0.70, 0.62)   # pressed: afunda/escurece
	sb.content_margin_left = 16; sb.content_margin_right = 16
	sb.content_margin_top = 8 if state != 2 else 10
	sb.content_margin_bottom = 8 if state != 2 else 6
	_cache[state] = sb
	return sb

# Textura: grão de couro escuro no centro + moldura dourada FINA biselada (luz do topo-esquerda).
static func _image() -> Image:
	var grain := FastNoiseLite.new()
	grain.seed = SEED
	grain.noise_type = FastNoiseLite.TYPE_SIMPLEX_SMOOTH
	grain.fractal_type = FastNoiseLite.FRACTAL_FBM
	grain.fractal_octaves = 4
	grain.frequency = 0.06
	var spec := FastNoiseLite.new()       # manchinhas/poros do couro
	spec.seed = SEED + 50
	spec.noise_type = FastNoiseLite.TYPE_CELLULAR
	spec.frequency = 0.18
	var leather_a := Color(0.135, 0.10, 0.075)
	var leather_b := Color(0.26, 0.185, 0.125)
	var gold_lit := Color(0.96, 0.81, 0.40)   # ouro iluminado (topo/esquerda)
	var gold_dim := Color(0.55, 0.42, 0.17)   # ouro na sombra (baixo/direita)
	var img := Image.create(S, S, false, Image.FORMAT_RGBA8)
	for y in S:
		for x in S:
			var d := mini(mini(x, S - 1 - x), mini(y, S - 1 - y))   # distância à borda mais perto
			if d >= BORDER:
				var n := grain.get_noise_2d(x, y) * 0.5 + 0.5
				var s := clampf(spec.get_noise_2d(x, y) * 0.5 + 0.5, 0.0, 1.0)
				var c := leather_a.lerp(leather_b, n)
				c = c.darkened(0.12 * (1.0 - s))   # poros escuros
				img.set_pixel(x, y, c)
			elif d == 0:
				img.set_pixel(x, y, Color(0.06, 0.05, 0.03))   # contorno escuro externo
			elif d == BORDER - 1:
				img.set_pixel(x, y, Color(0.05, 0.04, 0.025))  # sombra interna (separa do couro)
			else:
				var lit := d == y or d == x   # borda mais perto = topo/esquerda → luz (bisel)
				var g := gold_lit if lit else gold_dim
				var t := float(d) / float(BORDER)     # leve gradiente no bisel
				img.set_pixel(x, y, g.lerp(g.darkened(0.18), t))
	return img
