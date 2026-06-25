extends RefCounted
# ── Estilo de menu "grimdark/Diablo" (procedural) ────────────────────────────────
# Fundo 3D atmosférico (cenário noturno com tochas) via SubViewport + vinheta escura +
# botões com moldura de pedra/bronze + título dourado. Uso: var fx := MenuFx.new(); fx.bg_3d(self); ...
# [MIGRACAO_GODOT] Reaproveita Scenery.gd. Robusto: se o 3D falhar, o menu ainda funciona.

const Scenery := preload("res://Scenery.gd")
const StoneStyle := preload("res://ui/StoneStyle.gd")
const MenuDuel := preload("res://ui/MenuDuel.gd")   # 2 guerreiros duelando (só no castelo)

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
func bg_3d(control: Control, scenario := "dungeon") -> SubViewportContainer:
	var svc := SubViewportContainer.new()
	svc.stretch = true
	svc.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	svc.mouse_filter = Control.MOUSE_FILTER_IGNORE
	control.add_child(svc)
	control.move_child(svc, 0)   # atrás de tudo
	var sv := SubViewport.new()
	sv.own_world_3d = true   # MUNDO 3D ISOLADO: senão o castelo+duelo do menu vazam pro World3D principal
	sv.msaa_3d = Viewport.MSAA_2X            # e a câmera do BattleReplay (Node3D no mundo principal) os enxerga
	svc.add_child(sv)
	var world := Node3D.new()
	sv.add_child(world)
	var cam := Camera3D.new()
	world.add_child(cam)
	# câmera por cenário: FORA do lado aberto (+Z) olhando pra dentro. Castle/arena são maiores/altos.
	var cpos := Vector3(0, 4.0, 13.0)
	var clook := Vector3(0, 2.4, -2.0)
	match scenario:
		"castle":
			cpos = Vector3(0, 6.5, 18.0); clook = Vector3(0, 3.2, -3.0)   # vê muralhas + torres
		"arena":
			cpos = Vector3(0, 7.0, 17.0); clook = Vector3(0, 1.8, 0.0)
		"city":
			cpos = Vector3(0, 5.5, 16.0); clook = Vector3(0, 2.5, -2.0)
		"cursed_tower":   # [MAPA_TORRE] LATERAL (estilo combate): câmera no lado +Z vê o duelo de PERFIL na estrada, fortaleza negra à DIREITA (+X)
			cpos = Vector3(-5.0, 4.5, 14.5); clook = Vector3(7.0, 5.2, 2.5)
	if scenario == "cursed_tower":
		cam.fov = 80.0   # lente mais aberta p/ pegar a altura da fortaleza sem perder o duelo/corpos no chão
	cam.position = cpos
	cam.look_at(clook, Vector3.UP)
	var rng := RandomNumberGenerator.new()
	rng.seed = 1337
	Scenery.new().build(world, scenario, rng, 6.0, false)   # grimdark off (screen-shader não vale em SubViewport)
	var duel := MenuDuel.new()                              # 2 guerreiros duelando em QUALQUER cenário (decoração)
	world.add_child(duel)
	svc.set_meta("menu_duel", duel)                         # o App pega esse ref p/ remontar os lutadores no login
	var tw := cam.create_tween().set_loops()                # drift lento (parallax) só no X (não cruza paredes)
	tw.tween_property(cam, "position", cpos + Vector3(2.6, 0.3, 0), 14.0).set_trans(Tween.TRANS_SINE)
	tw.tween_property(cam, "position", cpos + Vector3(-2.6, -0.2, 0), 14.0).set_trans(Tween.TRANS_SINE)
	# vinheta escura por cima do 3D (abaixo da UI)
	var vig := ColorRect.new()
	vig.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	vig.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var sh := Shader.new(); sh.code = VIGNETTE
	var mat := ShaderMaterial.new(); mat.shader = sh
	vig.material = mat
	control.add_child(vig)
	control.move_child(vig, 1)
	return svc   # o chamador (App) pode esconder/mostrar o fundo (ex.: durante a batalha)

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

# Botão de PEDRA esculpida (textura procedural via StoneStyle; hover aceso/torch, pressed afunda).
func button(text: String) -> Button:
	var b := Button.new()
	b.text = text
	DarkButtonStyle.apply(b)
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
