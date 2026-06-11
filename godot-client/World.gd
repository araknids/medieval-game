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
	var radius := 21.0
	cam.position = Vector3(sin(a) * radius, 8.0, cos(a) * radius)   # mais baixa = pega a linha de árvores
	cam.look_at(Vector3(0, 2.5, 0), Vector3.UP)

# ── cenário: MINERAÇÃO ──────────────────────────────────────────────────────────
# Clareira de combate ABERTA no centro; o veio de minério fica num OUTCROP lateral.
# Mata densa em camadas no fundo (some o céu cinza). Trilha de pedras até o veio.
const COMBAT_R := 6.0        # raio do centro que fica LIVRE p/ os lutadores
const OUTCROP := Vector3(-9.0, 0, 3.0)   # onde fica o veio de minério (fora do combate)

func _build_mining() -> void:
	_ground(Color(0.34, 0.27, 0.19), 40.0)          # terra batida bem larga
	_disc(Color(0.42, 0.37, 0.30), COMBAT_R, 0.02)   # arena de combate (clareira pisada)

	# VEIO DE MINÉRIO — outcrop LATERAL (fora do centro): rochas grandes + tingidas
	var ores := [Color(0.95, 0.78, 0.22), Color(0.82, 0.46, 0.22), Color(0.62, 0.66, 0.72), Color(0.25, 0.75, 0.5)]
	for i in 4:   # "parede de rocha" do veio (cinza grande, sem tingir)
		var off := Vector3(rng.randf_range(-2.0, 2.0), 0, rng.randf_range(-2.0, 2.0))
		_place(NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), OUTCROP + off, rng.randf_range(0, 360), rng.randf_range(1.4, 2.2))
	for i in 7:   # veios de minério (tingidos) cravados no outcrop
		var off2 := Vector3(rng.randf_range(-2.6, 2.6), rng.randf_range(0, 0.6), rng.randf_range(-2.6, 2.6))
		_place(NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), OUTCROP + off2,
				rng.randf_range(0, 360), rng.randf_range(0.6, 1.1), ores[i % ores.size()])

	# TRILHA de pedras ligando a clareira ao veio
	for i in 6:
		var t := float(i) / 5.0
		var p := Vector3(0, 0, 0).lerp(OUTCROP, t) + Vector3(rng.randf_range(-0.5, 0.5), 0, rng.randf_range(-0.5, 0.5))
		_place(NAT + "RockPath_Round_%s.gltf" % (["Wide", "Thin"][i % 2]), p, rng.randf_range(0, 360), rng.randf_range(1.0, 1.4))

	# PEDRAS e cascalho espalhados (FORA do círculo de combate)
	for i in 22:
		_place(NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), _scatter(COMBAT_R + 1.0, 34.0), rng.randf_range(0, 360), rng.randf_range(0.6, 1.3))
	for i in 55:
		_place(NAT + "Pebble_Round_%d.gltf" % (1 + i % 5), _scatter(COMBAT_R - 1.0, 36.0), rng.randf_range(0, 360), rng.randf_range(0.7, 1.6))

	# MATA DENSA em 3 camadas no fundo (esconde o céu cinza) — mais alta e cheia atrás
	var near := ["DeadTree_1", "DeadTree_2", "DeadTree_3", "Pine_1", "CommonTree_3"]
	var far := ["Pine_1", "Pine_2", "Pine_3", "CommonTree_1", "CommonTree_2", "CommonTree_4", "CommonTree_5"]
	_tree_ring(near, 14.0, 18.0, 20, 0.9, 1.3)       # anel interno (acentos, troncos secos)
	_tree_ring(far, 22.0, 28.0, 34, 1.1, 1.7)        # anel médio (cheio)
	_tree_ring(far, 30.0, 38.0, 46, 1.3, 2.1)        # parede de mata ao fundo (alta)

	# GRAMA / arbustos / cogumelos (fora do combate)
	for i in 60:
		var g: String = ["Grass_Common_Short", "Grass_Common_Tall", "Grass_Wispy_Short", "Bush_Common", "Fern_1", "Mushroom_Common"][i % 6]
		_place(NAT + g + ".gltf", _scatter(COMBAT_R - 1.0, 34.0), rng.randf_range(0, 360), rng.randf_range(0.8, 1.4))

	# personagem p/ ESCALA (idle), na BEIRA da clareira (não no centro)
	var ch := CHAR.instantiate()
	add_child(ch)
	ch.position = Vector3(-4.5, 0, 2.4)
	ch.rotation_degrees = Vector3(0, 60, 0)
	var ap: AnimationPlayer = ch.find_child("AnimationPlayer", true, false)
	if ap:
		var il := ap.get_animation("UAL1_Standard/Sword_Idle")
		if il: il.loop_mode = Animation.LOOP_LINEAR
		ap.play("UAL1_Standard/Sword_Idle")

# Anel de árvores num raio [r0,r1] com N elementos, escala [s0,s1].
func _tree_ring(pool: Array, r0: float, r1: float, n: int, s0: float, s1: float) -> void:
	for i in n:
		var a := TAU * i / float(n) + rng.randf_range(-0.08, 0.08)
		var r := rng.randf_range(r0, r1)
		var tree: String = pool[rng.randi() % pool.size()]
		_place(NAT + tree + ".gltf", Vector3(cos(a) * r, 0, sin(a) * r), rng.randf_range(0, 360), rng.randf_range(s0, s1))

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
	sky_mat.sky_top_color = Color(0.32, 0.52, 0.74)
	sky_mat.sky_horizon_color = Color(0.66, 0.74, 0.62)   # horizonte esverdeado (some no topo da mata)
	sky_mat.ground_horizon_color = Color(0.5, 0.54, 0.44)
	sky_mat.ground_bottom_color = Color(0.30, 0.30, 0.24)
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.6
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.6, 0.66, 0.58)   # névoa esverdeada → funde com a mata ao fundo
	env.fog_density = 0.009
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
