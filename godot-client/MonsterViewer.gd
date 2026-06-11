extends Node3D
# ── Viewer de MONSTROS (calibração) ─────────────────────────────────────────────
# Cicla os 30 monstros do bundle, cada um AUTO-ESCALADO (altura-alvo + pé no chão) e
# tocando a anim idle. Palco neutro p/ ler cor/forma/orientação de verdade.
# Rode MonsterViewer.tscn com F6.  ←/→ troca · ESPAÇO gira (turntable).

const Monsters := preload("res://Monsters.gd")

## Por qual monstro começar (índice em Monsters.NAMES).
@export var start_index := 0
## Gira o monstro continuamente (turntable) p/ ver todos os lados.
@export var turntable := false

var mon := Monsters.new()
var idx := 0
var current: Node3D
var info: Label
var cam: Camera3D

func _ready() -> void:
	_stage()
	idx = clampi(start_index, 0, Monsters.NAMES.size() - 1)
	_show(idx)

# palco: luz neutra de estúdio + chão disco + câmera fixa 3/4 frontal.
func _stage() -> void:
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	var sm := ProceduralSkyMaterial.new()
	sm.sky_top_color = Color(0.42, 0.52, 0.66)
	sm.sky_horizon_color = Color(0.70, 0.74, 0.78)
	sm.ground_horizon_color = Color(0.5, 0.5, 0.5)
	sm.ground_bottom_color = Color(0.28, 0.30, 0.30)
	sky.sky_material = sm
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.85
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-45, -35, 0)
	key.light_energy = 1.3
	key.shadow_enabled = true
	add_child(key)
	var fill := DirectionalLight3D.new()   # fill suave do outro lado (tira sombra dura)
	fill.rotation_degrees = Vector3(-20, 140, 0)
	fill.light_color = Color(0.8, 0.85, 1.0)
	fill.light_energy = 0.4
	add_child(fill)
	var g := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = 6.0; c.bottom_radius = 6.0; c.height = 0.2
	g.mesh = c
	var gm := StandardMaterial3D.new()
	gm.albedo_color = Color(0.33, 0.35, 0.31); gm.roughness = 1.0
	g.material_override = gm
	g.position.y = -0.1
	add_child(g)
	cam = Camera3D.new()
	cam.position = Vector3(0, 1.3, 4.0)
	add_child(cam)
	cam.look_at(Vector3(0, 0.9, 0), Vector3.UP)
	# UI
	var layer := CanvasLayer.new()
	add_child(layer)
	info = Label.new()
	info.add_theme_font_size_override("font_size", 20)
	info.position = Vector2(16, 12)
	layer.add_child(info)
	var hint := Label.new()
	hint.add_theme_font_size_override("font_size", 14)
	hint.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_LEFT)
	hint.offset_top = -34; hint.offset_left = 16
	hint.text = "←/→ trocar monstro   ·   ESPAÇO girar (turntable)"
	layer.add_child(hint)

func _show(i: int) -> void:
	if current and is_instance_valid(current):
		current.queue_free()
	var mname: String = Monsters.NAMES[i]
	current = mon.instance(mname)
	if current == null:
		info.text = "[%d/%d] %s — FALHOU ao carregar" % [i + 1, Monsters.NAMES.size(), mname]
		return
	add_child(current)
	current.rotation_degrees.y = Monsters.FACE_OFFSET_DEG
	var f := mon.fit(current)             # auto-escala + pé no chão
	mon.play_idle(current)
	info.text = "[%d/%d]  %s   ·   escala %.2f   (altura crua %.2fm)" % \
			[i + 1, Monsters.NAMES.size(), mname, f["scale"], f["height"]]
	print(">>> %s · escala=%.3f · altura=%.3f" % [mname, f["scale"], f["height"]])

func _process(dt: float) -> void:
	var n := Monsters.NAMES.size()
	if Input.is_action_just_pressed("ui_right"):
		idx = (idx + 1) % n; _show(idx)
	if Input.is_action_just_pressed("ui_left"):
		idx = (idx - 1 + n) % n; _show(idx)
	if Input.is_action_just_pressed("ui_accept"):   # Espaço/Enter
		turntable = not turntable
	if turntable and current and is_instance_valid(current):
		current.rotation_degrees.y += 30.0 * dt
