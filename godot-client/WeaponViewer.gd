extends Node3D
# ── Viewer de ARMAS × RARIDADE (mock de teste) ───────────────────────────────────
# 5 heróis lado a lado = raridade 1→5 (esq→dir) da MESMA arma. ←/→ troca o TIPO (8).
# Palco escuro com bloom p/ ler o brilho da raridade. Rode WeaponViewer.tscn com F6.

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const Weapons := preload("res://Weapons.gd")

## Por qual tipo começar (índice em Weapons.KINDS). 0 = sword.
@export var start_type := 0
## Giro dos personagens (graus) p/ mostrar a arma. Ajuste se a arma ficar escondida.
@export var hero_yaw := 195.0
## Mostra também o ESCUDO (só com arma melee) — pra ver o brilho de raridade do escudo junto.
@export var show_shield := true

var wp := Weapons.new()
var kind_idx := 0
var heroes: Array = []
var info: Label

func _ready() -> void:
	_stage()
	kind_idx = clampi(start_type, 0, Weapons.KINDS.size() - 1)
	_spawn()

func _stage() -> void:
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	var sky := Sky.new()
	var sm := ProceduralSkyMaterial.new()
	sm.sky_top_color = Color(0.14, 0.16, 0.22)
	sm.sky_horizon_color = Color(0.26, 0.28, 0.33)
	sm.ground_horizon_color = Color(0.18, 0.18, 0.20)
	sm.ground_bottom_color = Color(0.08, 0.08, 0.10)
	sky.sky_material = sm
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.55
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.glow_enabled = true                       # bloom → o metal emissivo "acende"
	env.glow_intensity = 0.6
	env.glow_bloom = 0.15
	env.glow_hdr_threshold = 0.9
	env.glow_blend_mode = Environment.GLOW_BLEND_MODE_SCREEN
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-45, -35, 0)
	key.light_energy = 1.1
	key.shadow_enabled = true
	add_child(key)
	var fill := DirectionalLight3D.new()
	fill.rotation_degrees = Vector3(-20, 140, 0)
	fill.light_color = Color(0.7, 0.75, 0.9)
	fill.light_energy = 0.3
	add_child(fill)
	var g := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = 5.5; c.bottom_radius = 5.5; c.height = 0.2
	g.mesh = c
	var gm := StandardMaterial3D.new()
	gm.albedo_color = Color(0.19, 0.20, 0.19); gm.roughness = 1.0
	g.material_override = gm
	g.position.y = -0.1
	add_child(g)
	var cam := Camera3D.new()
	cam.position = Vector3(0, 1.25, 5.2)
	add_child(cam)
	cam.look_at(Vector3(0, 1.0, 0), Vector3.UP)
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
	hint.text = "←/→ trocar tipo de arma   ·   raridade 1→5 (comum→lendário), esquerda→direita"
	layer.add_child(hint)

func _spawn() -> void:
	for h in heroes:
		if is_instance_valid(h): h.queue_free()
	heroes.clear()
	var kind: String = Weapons.KINDS[kind_idx]
	for r in range(1, 6):                         # raridade 1..5
		var ch := CHAR.instantiate()
		add_child(ch)
		ch.position = Vector3((r - 3) * 1.2, 0, 0)   # -2.4 .. 2.4 em linha
		ch.scale = Vector3(0.92, 0.92, 0.92)
		ch.rotation_degrees = Vector3(0, hero_yaw, 0)
		var ap: AnimationPlayer = ch.find_child("AnimationPlayer", true, false)
		if ap:
			var idle := ap.get_animation("UAL1_Standard/Sword_Idle")
			if idle: idle.loop_mode = Animation.LOOP_LINEAR
			ap.play("UAL1_Standard/Sword_Idle")
		wp.attach_weapon(ch, kind, r, 0.10)
		if show_shield and not wp.is_bow_kind(kind):   # escudo na mesma raridade (face = câmera, +Z)
			wp.attach_shield(ch, {"rarity": r, "forward": Vector3(0, 0, 1)})
		heroes.append(ch)
	var with_sh := "  + escudo" if (show_shield and not wp.is_bow_kind(kind)) else ""
	info.text = "Arma: %s%s  [%d/%d]" % [kind, with_sh, kind_idx + 1, Weapons.KINDS.size()]
	print(">>> arma=%s (raridade 1→5)" % kind)

func _process(_dt: float) -> void:
	var n := Weapons.KINDS.size()
	if Input.is_action_just_pressed("ui_right"):
		kind_idx = (kind_idx + 1) % n; _spawn()
	if Input.is_action_just_pressed("ui_left"):
		kind_idx = (kind_idx - 1 + n) % n; _spawn()
