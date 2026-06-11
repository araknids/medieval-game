extends Node3D
# ── Viewer de CENÁRIOS 3D (mundos do jogo) ──────────────────────────────────────
# Monta um cenário com os MegaKits Quaternius (copiados, gitignored, em res://assets/world/).
# Câmera orbita devagar pra você inspecionar. Troque `scenario` no Inspector.
# Rode World.tscn com F6. Plano: docs/PLANO_GODOT_3D.md (Fase 4 — cenários)

const NAT := "res://assets/world/nature/"   # kit Stylized Nature
const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")

## Cenário a montar. Por ora: "mining" (mineração/garimpo). Outros virão.
@export var scenario := "mining"
## Velocidade de órbita da câmera (graus/s). 0 = parada.
@export var orbit_speed := 12.0

var cam: Camera3D
var cam_angle := 0.0
var rng := RandomNumberGenerator.new()

func _ready() -> void:
	rng.seed = 20260611
	_setup_environment()
	_setup_lights()
	cam = Camera3D.new()
	add_child(cam)
	match scenario:
		"mining": _build_mining()
		_:        _build_mining()
	_update_cam(0.0)

func _process(dt: float) -> void:
	cam_angle += orbit_speed * dt
	_update_cam(dt)

func _update_cam(_dt: float) -> void:
	var a := deg_to_rad(cam_angle)
	var radius := 24.0
	cam.position = Vector3(sin(a) * radius, 11.0, cos(a) * radius)
	cam.look_at(Vector3(0, 1.5, 0), Vector3.UP)

# ── cenário: MINERAÇÃO ──────────────────────────────────────────────────────────
# Clareira rochosa: veios de minério (rochas tingidas) no centro, pedras/pebbles
# espalhadas, árvores (mortas/pinheiros) na borda, grama. Personagem p/ escala.
func _build_mining() -> void:
	_ground(Color(0.34, 0.27, 0.19), 26.0)          # terra batida
	_disc(Color(0.40, 0.36, 0.30), 7.0, 0.02)        # clareira escavada (mais clara)

	# VEIOS DE MINÉRIO no centro — rochas tingidas (ouro / cobre / ferro / esmeralda)
	var ores := [Color(0.95, 0.78, 0.22), Color(0.82, 0.46, 0.22), Color(0.62, 0.66, 0.72), Color(0.25, 0.75, 0.5)]
	for i in 6:
		var a := TAU * i / 6.0 + rng.randf_range(-0.4, 0.4)
		var r := rng.randf_range(1.0, 3.2)
		_place(NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), Vector3(cos(a) * r, 0, sin(a) * r),
				rng.randf_range(0, 360), rng.randf_range(0.9, 1.5), ores[i % ores.size()])

	# PEDRAS normais espalhadas
	for i in 14:
		var p := _scatter(4.0, 22.0)
		_place(NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), p, rng.randf_range(0, 360), rng.randf_range(0.6, 1.3))

	# PEBBLES (cascalho)
	for i in 40:
		var p := _scatter(1.0, 24.0)
		var n := 1 + (i % 5)
		_place(NAT + "Pebble_Round_%d.gltf" % n, p, rng.randf_range(0, 360), rng.randf_range(0.7, 1.6))

	# ÁRVORES na borda (mortas + pinheiros = clima de pedreira)
	var trees := ["DeadTree_1", "DeadTree_2", "DeadTree_3", "Pine_1", "Pine_2", "CommonTree_3"]
	for i in 16:
		var a := TAU * i / 16.0 + rng.randf_range(-0.15, 0.15)
		var r := rng.randf_range(18.0, 24.0)
		_place(NAT + trees[i % trees.size()] + ".gltf", Vector3(cos(a) * r, 0, sin(a) * r),
				rng.randf_range(0, 360), rng.randf_range(0.9, 1.4))

	# GRAMA / arbustos espalhados
	for i in 50:
		var p := _scatter(5.0, 24.0)
		var g: String = ["Grass_Common_Short", "Grass_Common_Tall", "Grass_Wispy_Short", "Bush_Common", "Fern_1"][i % 5]
		_place(NAT + g + ".gltf", p, rng.randf_range(0, 360), rng.randf_range(0.8, 1.4))

	# personagem p/ ESCALA (idle), perto do minério
	var ch := CHAR.instantiate()
	add_child(ch)
	ch.position = Vector3(3.2, 0, 1.0)
	ch.rotation_degrees = Vector3(0, -130, 0)
	var ap: AnimationPlayer = ch.find_child("AnimationPlayer", true, false)
	if ap:
		var il := ap.get_animation("UAL1_Standard/Sword_Idle")
		if il: il.loop_mode = Animation.LOOP_LINEAR
		ap.play("UAL1_Standard/Sword_Idle")

# ── helpers ─────────────────────────────────────────────────────────────────────
# Posição aleatória num anel [r_min, r_max] em volta do centro.
func _scatter(r_min: float, r_max: float) -> Vector3:
	var a := rng.randf_range(0, TAU)
	var r := rng.randf_range(r_min, r_max)
	return Vector3(cos(a) * r, 0, sin(a) * r)

func _place(path: String, pos: Vector3, rot_y: float, scl: float, tint = null) -> Node3D:
	var ps: PackedScene = load(path)
	if ps == null:
		push_warning("modelo não carregou: %s" % path)
		return null
	var inst := ps.instantiate()
	add_child(inst)
	inst.position = pos
	inst.rotation_degrees = Vector3(0, rot_y, 0)
	inst.scale = Vector3(scl, scl, scl)
	if tint != null:
		_tint(inst, tint)
	return inst

# Sobrescreve o material das malhas (p/ "pintar" minério nas rochas).
func _tint(node: Node, color: Color) -> void:
	if node is MeshInstance3D:
		var m := StandardMaterial3D.new()
		m.albedo_color = color
		m.metallic = 0.7
		m.roughness = 0.35
		(node as MeshInstance3D).material_override = m
	for c in node.get_children():
		_tint(c, color)

func _ground(color: Color, radius: float) -> void:
	var mi := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = radius; c.bottom_radius = radius; c.height = 0.5
	mi.mesh = c
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color; mat.roughness = 1.0
	mi.material_override = mat
	mi.position = Vector3(0, -0.25, 0)
	add_child(mi)

func _disc(color: Color, radius: float, y: float) -> void:
	var mi := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = radius; c.bottom_radius = radius; c.height = 0.04
	mi.mesh = c
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color; mat.roughness = 1.0
	mi.material_override = mat
	mi.position = Vector3(0, y, 0)
	add_child(mi)

func _setup_environment() -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.30, 0.50, 0.78)
	sky_mat.sky_horizon_color = Color(0.78, 0.80, 0.78)
	sky_mat.ground_horizon_color = Color(0.55, 0.55, 0.5)
	sky_mat.ground_bottom_color = Color(0.32, 0.30, 0.26)
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.6
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.75, 0.78, 0.8)
	env.fog_density = 0.006
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

func _setup_lights() -> void:
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-52, -40, 0)
	sun.light_color = Color(1.0, 0.96, 0.88)
	sun.light_energy = 1.4
	sun.shadow_enabled = true
	add_child(sun)
