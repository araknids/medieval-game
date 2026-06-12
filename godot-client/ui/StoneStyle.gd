class_name StoneStyle
extends RefCounted
# ── Botões de PEDRA esculpida (procedural, sem arte) — [Fable] ───────────────────
# Gera 1 textura de granito tileável (FastNoiseLite.get_seamless_image, síncrona) com bisel
# esculpido + rachaduras bakeados → StyleBoxTexture 3-slice (bevel fixo, centro tileado).
# Hover = modulate quente (tocha); pressed = bisel invertido + texto afunda. Cache estático
# (gera 1× pra TODOS os botões/telas). Uso: StoneStyle.apply(btn). [MIGRACAO_GODOT]

const TEX := 256        # tamanho da textura (seamless, compartilhada)
const BEVEL := 6        # largura da borda esculpida (px)
const SEED := 7

static var _cache := {} # state(int) -> StyleBoxTexture

static func apply(btn: Button) -> void:
	btn.add_theme_stylebox_override("normal",  stylebox(0))
	btn.add_theme_stylebox_override("hover",   stylebox(1))
	btn.add_theme_stylebox_override("pressed", stylebox(2))
	btn.add_theme_stylebox_override("focus",   stylebox(1))
	btn.add_theme_color_override("font_color",         Color(0.80, 0.74, 0.62))
	btn.add_theme_color_override("font_hover_color",   Color(1.0, 0.88, 0.6))
	btn.add_theme_color_override("font_pressed_color", Color(0.62, 0.57, 0.50))

static func stylebox(state: int) -> StyleBoxTexture:
	if _cache.has(state):
		return _cache[state]
	var pressed := state == 2
	var sb := StyleBoxTexture.new()
	sb.texture = ImageTexture.create_from_image(_stone_image(pressed))
	var m := BEVEL + 2   # 3-slice: bevel fixo, centro tileado (densidade do grão constante)
	sb.texture_margin_left = m; sb.texture_margin_right = m
	sb.texture_margin_top = m; sb.texture_margin_bottom = m
	sb.axis_stretch_horizontal = StyleBoxTexture.AXIS_STRETCH_MODE_TILE
	sb.axis_stretch_vertical = StyleBoxTexture.AXIS_STRETCH_MODE_TILE
	match state:
		1: sb.modulate_color = Color(1.35, 1.18, 0.92)   # hover: tocha
		2: sb.modulate_color = Color(0.72, 0.70, 0.68)   # pressed: sombra
	sb.content_margin_left = 18; sb.content_margin_right = 18
	sb.content_margin_top = 12 if pressed else 10
	sb.content_margin_bottom = 8 if pressed else 10
	_cache[state] = sb
	return sb

static func _stone_image(pressed: bool) -> Image:
	var blotch := FastNoiseLite.new()
	blotch.seed = SEED
	blotch.noise_type = FastNoiseLite.TYPE_SIMPLEX_SMOOTH
	blotch.fractal_type = FastNoiseLite.FRACTAL_FBM
	blotch.fractal_octaves = 5
	blotch.frequency = 0.012
	var grain := FastNoiseLite.new()
	grain.seed = SEED + 100
	grain.noise_type = FastNoiseLite.TYPE_CELLULAR
	grain.cellular_return_type = FastNoiseLite.RETURN_DISTANCE2_SUB
	grain.frequency = 0.22
	var b_img := blotch.get_seamless_image(TEX, TEX)
	var g_img := grain.get_seamless_image(TEX, TEX)
	var grad := Gradient.new()
	grad.set_color(0, Color(0.10, 0.095, 0.09))
	grad.set_color(1, Color(0.40, 0.375, 0.345))
	grad.add_point(0.40, Color(0.20, 0.19, 0.18))
	grad.add_point(0.75, Color(0.31, 0.29, 0.265))
	var img := Image.create(TEX, TEX, false, Image.FORMAT_RGB8)
	var inv_bevel := 1.0 / float(BEVEL)
	for y in TEX:
		for x in TEX:
			var v := b_img.get_pixel(x, y).r
			var g := g_img.get_pixel(x, y).r
			v = clampf(v * 0.75 + g * 0.30 - 0.04, 0.0, 1.0)
			var c := grad.sample(v)
			if g < 0.06:
				c = c.darkened(0.35)
			var l := minf(x, BEVEL) * inv_bevel
			var r := minf(TEX - 1 - x, BEVEL) * inv_bevel
			var t := minf(y, BEVEL) * inv_bevel
			var btm := minf(TEX - 1 - y, BEVEL) * inv_bevel
			var hi := (1.0 - t) + (1.0 - l) * 0.5
			var lo := (1.0 - btm) + (1.0 - r) * 0.5
			if pressed:
				var tmp := hi; hi = lo; lo = tmp
			var rough := 0.85 + g * 0.3
			c = c.lightened(clampf(hi * 0.32 * rough, 0.0, 0.5))
			c = c.darkened(clampf(lo * 0.45 * rough, 0.0, 0.6))
			var edge := mini(mini(x, TEX - 1 - x), mini(y, TEX - 1 - y))
			if edge == 0:
				c = c.darkened(0.7)
			elif edge == 1:
				c = c.lerp(Color(0.45, 0.32, 0.14), 0.35)
			img.set_pixel(x, y, c)
	return img
