extends RefCounted
# ── Estilo de menu "grimdark/Diablo" (procedural) ────────────────────────────────
# Fundo 3D atmosférico (cenário noturno com tochas) via SubViewport + vinheta escura +
# botões com moldura de pedra/bronze + título dourado. Uso: var fx := MenuFx.new(); fx.bg_3d(self); ...
# [MIGRACAO_GODOT] Reaproveita Scenery.gd. Robusto: se o 3D falhar, o menu ainda funciona.

const Scenery := preload("res://Scenery.gd")

const VIGNETTE := """
shader_type canvas_item;
void fragment() {
	float d = length(SCREEN_UV - vec2(0.5, 0.46)) * 1.5;
	float v = smoothstep(0.40, 1.05, d);
	COLOR = vec4(0.02, 0.01, 0.0, v * 0.78);   // cantos quase pretos (mood Diablo)
}
"""

# Fundo 3D (cenário noturno) atrás de `control`, com drift lento de câmera + vinheta por cima.
# Use cenários FECHADOS/voltados pra câmera (dungeon/castle/arena/city) — os abertos (mining/beach)
# cercam o centro com árvores que passam na frente da câmera fixa.
func bg_3d(control: Control, scenario := "dungeon") -> void:
	var svc := SubViewportContainer.new()
	svc.stretch = true
	svc.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	svc.mouse_filter = Control.MOUSE_FILTER_IGNORE
	control.add_child(svc)
	control.move_child(svc, 0)   # atrás de tudo
	var sv := SubViewport.new()
	sv.msaa_3d = Viewport.MSAA_2X
	svc.add_child(sv)
	var world := Node3D.new()
	sv.add_child(world)
	var cam := Camera3D.new()
	world.add_child(cam)
	cam.position = Vector3(0, 4.0, 13.0)                     # FORA do lado aberto (+Z), olhando pra dentro do salão
	cam.look_at(Vector3(0, 2.4, -2.0), Vector3.UP)
	var rng := RandomNumberGenerator.new()
	rng.seed = 1337
	Scenery.new().build(world, scenario, rng, 6.0, false)   # grimdark off (screen-shader não vale em SubViewport)
	var tw := cam.create_tween().set_loops()                # drift lento (parallax) só no X (não cruza paredes)
	tw.tween_property(cam, "position", Vector3(2.6, 4.2, 13.0), 14.0).set_trans(Tween.TRANS_SINE)
	tw.tween_property(cam, "position", Vector3(-2.6, 3.8, 13.0), 14.0).set_trans(Tween.TRANS_SINE)
	# vinheta escura por cima do 3D (abaixo da UI)
	var vig := ColorRect.new()
	vig.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	vig.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var sh := Shader.new(); sh.code = VIGNETTE
	var mat := ShaderMaterial.new(); mat.shader = sh
	vig.material = mat
	control.add_child(vig)
	control.move_child(vig, 1)

# Título dourado com contorno escuro (gótico aproximado).
func title(text: String, size := 56) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", Color(0.96, 0.66, 0.26))
	l.add_theme_color_override("font_outline_color", Color(0.15, 0.04, 0.0))
	l.add_theme_constant_override("outline_size", 8)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	return l

# Botão com moldura de pedra/bronze (hover aceso).
func button(text: String) -> Button:
	var b := Button.new()
	b.text = text
	b.add_theme_color_override("font_color", Color(0.82, 0.70, 0.45))
	b.add_theme_color_override("font_hover_color", Color(1.0, 0.88, 0.55))
	b.add_theme_color_override("font_pressed_color", Color(1.0, 0.9, 0.6))
	b.add_theme_stylebox_override("normal", _box(Color(0.12, 0.10, 0.09), Color(0.42, 0.33, 0.20)))
	b.add_theme_stylebox_override("hover", _box(Color(0.20, 0.15, 0.11), Color(0.85, 0.65, 0.32)))
	b.add_theme_stylebox_override("pressed", _box(Color(0.08, 0.06, 0.05), Color(0.6, 0.46, 0.26)))
	b.add_theme_stylebox_override("focus", _box(Color(0.20, 0.15, 0.11), Color(0.85, 0.65, 0.32)))
	return b

# Painel escuro de pedra (caixa de login etc.).
func panel() -> StyleBoxFlat:
	return _box(Color(0.10, 0.09, 0.10, 0.92), Color(0.40, 0.32, 0.20))

func _box(bg: Color, border: Color) -> StyleBoxFlat:
	var sb := StyleBoxFlat.new()
	sb.bg_color = bg
	sb.set_border_width_all(2)
	sb.border_color = border
	sb.set_corner_radius_all(3)
	sb.set_content_margin_all(10)
	sb.shadow_color = Color(0, 0, 0, 0.5)
	sb.shadow_size = 4
	return sb
