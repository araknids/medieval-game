class_name Scenery
extends RefCounted
# ── Construtor de CENÁRIOS 3D (compartilhado) ───────────────────────────────────
# Funções estáticas que montam um cenário num `host: Node3D` (viewer World OU a
# batalha BattleReplay). Mantém o CENTRO livre (raio combat_r) p/ os lutadores.
# Assets: MegaKits Quaternius copiados (gitignored) em res://assets/world/.

const NAT := "res://assets/world/nature/"

# ── iluminação NOTURNA (some o céu cinza: céu escuro + luar frio) ────────────────
static func night_lighting(host: Node3D) -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.02, 0.03, 0.07)
	sky_mat.sky_horizon_color = Color(0.07, 0.09, 0.16)
	sky_mat.ground_horizon_color = Color(0.05, 0.06, 0.10)
	sky_mat.ground_bottom_color = Color(0.02, 0.02, 0.05)
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.4
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.06, 0.08, 0.16)
	env.fog_density = 0.012
	env.glow_enabled = true
	env.glow_intensity = 0.35
	env.glow_bloom = 0.1
	var we := WorldEnvironment.new()
	we.environment = env
	host.add_child(we)
	var moon := DirectionalLight3D.new()
	moon.rotation_degrees = Vector3(-55, -30, 0)
	moon.light_color = Color(0.55, 0.65, 0.95)   # luar frio
	moon.light_energy = 0.5
	moon.shadow_enabled = true
	host.add_child(moon)

# ── cenário: MINERAÇÃO (noite) ──────────────────────────────────────────────────
# Centro LIVRE p/ combate; veio de minério num OUTCROP lateral; mata densa no fundo;
# braseiros quentes ao redor da clareira iluminam os lutadores. Minério BRILHA.
static func mining(host: Node3D, rng: RandomNumberGenerator, combat_r: float) -> void:
	var OUTCROP := Vector3(-9.0, 0, 3.0)
	_ground(host, Color(0.30, 0.25, 0.18), 40.0)
	_disc(host, Color(0.36, 0.32, 0.26), combat_r, 0.02)
	# colisão do chão (p/ o ragdoll da batalha não atravessar o piso)
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)

	# VEIO de minério (outcrop lateral): parede de rocha grande + veios tingidos que brilham
	var ores := [Color(0.95, 0.78, 0.22), Color(0.82, 0.46, 0.22), Color(0.62, 0.66, 0.72), Color(0.25, 0.78, 0.5)]
	for i in 4:
		var off := Vector3(rng.randf_range(-2.0, 2.0), 0, rng.randf_range(-2.0, 2.0))
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), OUTCROP + off, rng.randf_range(0, 360), rng.randf_range(1.4, 2.2))
	for i in 7:
		var off2 := Vector3(rng.randf_range(-2.6, 2.6), rng.randf_range(0, 0.6), rng.randf_range(-2.6, 2.6))
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), OUTCROP + off2,
				rng.randf_range(0, 360), rng.randf_range(0.6, 1.1), ores[i % ores.size()])

	# TRILHA de pedras da clareira ao veio
	for i in 6:
		var t := float(i) / 5.0
		var p := Vector3.ZERO.lerp(OUTCROP, t) + Vector3(rng.randf_range(-0.5, 0.5), 0, rng.randf_range(-0.5, 0.5))
		_place(host, rng, NAT + "RockPath_Round_%s.gltf" % (["Wide", "Thin"][i % 2]), p, rng.randf_range(0, 360), rng.randf_range(1.0, 1.4))

	# pedras + cascalho (fora do combate)
	for i in 22:
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), _scatter(rng, combat_r + 1.0, 34.0), rng.randf_range(0, 360), rng.randf_range(0.6, 1.3))
	for i in 55:
		_place(host, rng, NAT + "Pebble_Round_%d.gltf" % (1 + i % 5), _scatter(rng, combat_r - 1.0, 36.0), rng.randf_range(0, 360), rng.randf_range(0.7, 1.6))

	# MATA densa em 3 camadas (fundo) — esconde o céu
	var near := ["DeadTree_1", "DeadTree_2", "DeadTree_3", "Pine_1", "CommonTree_3"]
	var far := ["Pine_1", "Pine_2", "Pine_3", "CommonTree_1", "CommonTree_2", "CommonTree_4", "CommonTree_5"]
	_tree_ring(host, rng, near, 14.0, 18.0, 20, 0.9, 1.3)
	_tree_ring(host, rng, far, 22.0, 28.0, 34, 1.1, 1.7)
	_tree_ring(host, rng, far, 30.0, 38.0, 46, 1.3, 2.1)

	# grama / arbustos / cogumelos
	for i in 60:
		var g: String = ["Grass_Common_Short", "Grass_Common_Tall", "Grass_Wispy_Short", "Bush_Common", "Fern_1", "Mushroom_Common"][i % 6]
		_place(host, rng, NAT + g + ".gltf", _scatter(rng, combat_r - 1.0, 34.0), rng.randf_range(0, 360), rng.randf_range(0.8, 1.4))

	# BRASEIROS ao redor da clareira (luz quente nos lutadores)
	for i in 5:
		var a := TAU * i / 5.0 + 0.6
		_brazier(host, Vector3(cos(a) * (combat_r + 1.2), 0, sin(a) * (combat_r + 1.2)))

# ── helpers (estáticos) ─────────────────────────────────────────────────────────
static func _scatter(rng: RandomNumberGenerator, r_min: float, r_max: float) -> Vector3:
	var a := rng.randf_range(0, TAU)
	var r := rng.randf_range(r_min, r_max)
	return Vector3(cos(a) * r, 0, sin(a) * r)

static func _place(host: Node3D, _rng: RandomNumberGenerator, path: String, pos: Vector3, rot_y: float, scl: float, tint = null) -> Node3D:
	var ps: PackedScene = load(path)
	if ps == null:
		push_warning("modelo não carregou: %s" % path)
		return null
	var inst := ps.instantiate()
	host.add_child(inst)
	inst.position = pos
	inst.rotation_degrees = Vector3(0, rot_y, 0)
	inst.scale = Vector3(scl, scl, scl)
	if tint != null:
		_tint(inst, tint)
	return inst

# Pinta as malhas com uma cor (minério) + emissão sutil → brilha no escuro.
static func _tint(node: Node, color: Color) -> void:
	if node is MeshInstance3D:
		var m := StandardMaterial3D.new()
		m.albedo_color = color
		m.metallic = 0.7
		m.roughness = 0.3
		m.emission_enabled = true
		m.emission = color
		m.emission_energy_multiplier = 0.6
		(node as MeshInstance3D).material_override = m
	for c in node.get_children():
		_tint(c, color)

static func _tree_ring(host: Node3D, rng: RandomNumberGenerator, pool: Array, r0: float, r1: float, n: int, s0: float, s1: float) -> void:
	for i in n:
		var a := TAU * i / float(n) + rng.randf_range(-0.08, 0.08)
		var r := rng.randf_range(r0, r1)
		var tree: String = pool[rng.randi() % pool.size()]
		_place(host, rng, NAT + tree + ".gltf", Vector3(cos(a) * r, 0, sin(a) * r), rng.randf_range(0, 360), rng.randf_range(s0, s1))

static func _ground(host: Node3D, color: Color, radius: float) -> void:
	var mi := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = radius; c.bottom_radius = radius; c.height = 0.5
	mi.mesh = c
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color; mat.roughness = 1.0
	mi.material_override = mat
	mi.position = Vector3(0, -0.25, 0)
	host.add_child(mi)

static func _disc(host: Node3D, color: Color, radius: float, y: float) -> void:
	var mi := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = radius; c.bottom_radius = radius; c.height = 0.04
	mi.mesh = c
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color; mat.roughness = 1.0
	mi.material_override = mat
	mi.position = Vector3(0, y, 0)
	host.add_child(mi)

static func _brazier(host: Node3D, pos: Vector3) -> void:
	var post := MeshInstance3D.new()
	var pb := BoxMesh.new(); pb.size = Vector3(0.18, 1.7, 0.18)
	post.mesh = pb
	var pmat := StandardMaterial3D.new(); pmat.albedo_color = Color(0.15, 0.11, 0.07); pmat.roughness = 1.0
	post.material_override = pmat
	host.add_child(post); post.position = pos + Vector3(0, 0.85, 0)
	var coal := MeshInstance3D.new()
	var sm := SphereMesh.new(); sm.radius = 0.28; sm.height = 0.5
	coal.mesh = sm
	var cmat := StandardMaterial3D.new()
	cmat.albedo_color = Color(1.0, 0.55, 0.18)
	cmat.emission_enabled = true
	cmat.emission = Color(1.0, 0.5, 0.12)
	cmat.emission_energy_multiplier = 5.0
	coal.material_override = cmat
	host.add_child(coal); coal.position = pos + Vector3(0, 1.75, 0)
	var light := OmniLight3D.new()
	light.light_color = Color(1.0, 0.62, 0.28)
	light.light_energy = 5.0
	light.omni_range = 11.0
	host.add_child(light); light.position = pos + Vector3(0, 1.9, 0)
