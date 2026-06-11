extends RefCounted
# ── Construtor de CENÁRIOS 3D (compartilhado) ───────────────────────────────────
# Uso: const Scenery := preload("res://Scenery.gd"); var sc := Scenery.new()
#      sc.night_lighting(host); sc.mining(host, rng, combat_r)
# (métodos de INSTÂNCIA — não static, p/ evitar erro de parse; sem class_name p/
#  evitar flakiness de registro de classe global em script novo)
# Monta o cenário num `host: Node3D` (viewer World OU a batalha BattleReplay),
# mantendo o CENTRO livre (raio combat_r). Assets gitignored em res://assets/world/.

const NAT := "res://assets/world/nature/"

# ── iluminação NOTURNA (some o céu cinza: céu escuro + luar frio) ────────────────
func night_lighting(host: Node3D) -> void:
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
func mining(host: Node3D, rng: RandomNumberGenerator, combat_r: float) -> void:
	var OUTCROP := Vector3(-13.0, 0, 5.0)   # veio BEM afastado do combate (não bloqueia os lutadores)
	_ground(host, Color(0.31, 0.28, 0.20), 40.0)
	_disc(host, Color(0.40, 0.36, 0.30), combat_r, 0.02)
	# colisão do chão (p/ o ragdoll da batalha não atravessar o piso)
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)

	# CHÃO DE LADRILHO cobrindo a ÁREA DE COMBATE inteira (onde os personagens andam)
	_cobble_floor(host, rng, combat_r - 0.2)

	# VEIO de minério (outcrop lateral, LONGE): parede de rocha + veios tingidos que brilham
	var ores := [Color(0.95, 0.78, 0.22), Color(0.82, 0.46, 0.22), Color(0.62, 0.66, 0.72), Color(0.25, 0.78, 0.5)]
	for i in 4:
		var off := Vector3(rng.randf_range(-2.2, 2.2), 0, rng.randf_range(-2.2, 2.2))
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), OUTCROP + off, rng.randf_range(0, 360), rng.randf_range(1.3, 1.9))
	for i in 7:
		var off2 := Vector3(rng.randf_range(-2.8, 2.8), rng.randf_range(0, 0.7), rng.randf_range(-2.8, 2.8))
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), OUTCROP + off2,
				rng.randf_range(0, 360), rng.randf_range(0.6, 1.0), ores[i % ores.size()])

	# TRILHA de pedras da BORDA da clareira até o veio (começa fora do círculo)
	for i in 6:
		var p := Vector3.ZERO.lerp(OUTCROP, lerpf(0.5, 1.0, float(i) / 5.0)) + Vector3(rng.randf_range(-0.5, 0.5), 0, rng.randf_range(-0.5, 0.5))
		_place(host, rng, NAT + "RockPath_Round_%s.gltf" % (["Wide", "Thin"][i % 2]), p, rng.randf_range(0, 360), rng.randf_range(1.0, 1.4))

	# pedras + cascalho — BEM longe do combate (nada perto do círculo)
	for i in 22:
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), _scatter(rng, combat_r + 3.5, 34.0), rng.randf_range(0, 360), rng.randf_range(0.6, 1.3))
	for i in 50:
		_place(host, rng, NAT + "Pebble_Round_%d.gltf" % (1 + i % 5), _scatter(rng, combat_r + 1.5, 36.0), rng.randf_range(0, 360), rng.randf_range(0.7, 1.6))

	# MATA densa em 3 camadas (fundo) — esconde o céu
	var near := ["DeadTree_1", "DeadTree_2", "DeadTree_3", "Pine_1", "CommonTree_3"]
	var far := ["Pine_1", "Pine_2", "Pine_3", "CommonTree_1", "CommonTree_2", "CommonTree_4", "CommonTree_5"]
	_tree_ring(host, rng, near, 14.0, 18.0, 20, 0.9, 1.3)
	_tree_ring(host, rng, far, 22.0, 28.0, 34, 1.1, 1.7)
	_tree_ring(host, rng, far, 30.0, 38.0, 46, 1.3, 2.1)

	# VEGETAÇÃO RASTEIRA — MUITA grama/trevo p/ o chão não ficar marrom (fora do combate)
	for i in 150:
		var g: String = ["Grass_Common_Short", "Grass_Common_Tall", "Grass_Wispy_Short", "Grass_Wispy_Tall", "Clover_1", "Clover_2", "Bush_Common", "Fern_1"][i % 8]
		_place(host, rng, NAT + g + ".gltf", _scatter(rng, combat_r + 0.6, 37.0), rng.randf_range(0, 360), rng.randf_range(0.7, 1.3))

	# BRASEIROS ao redor da clareira (luz quente nos lutadores)
	for i in 5:
		var a := TAU * i / 5.0 + 0.6
		_brazier(host, Vector3(cos(a) * (combat_r + 1.2), 0, sin(a) * (combat_r + 1.2)))

# Ladrilha a área de combate (grade de pedras de caminho dentro do círculo).
func _cobble_floor(host: Node3D, rng: RandomNumberGenerator, radius: float) -> void:
	var tiles := ["RockPath_Square_Wide", "RockPath_Square_Thin", "RockPath_Round_Wide"]
	var step := 1.7
	var x := -radius
	while x <= radius:
		var z := -radius
		while z <= radius:
			if Vector2(x, z).length() <= radius:
				var t: String = tiles[rng.randi() % tiles.size()]
				var pos := Vector3(x + rng.randf_range(-0.3, 0.3), 0.03, z + rng.randf_range(-0.3, 0.3))
				_place(host, rng, NAT + t + ".gltf", pos, rng.randf_range(0, 360), rng.randf_range(1.1, 1.5))
			z += step
		x += step

# ── helpers (estáticos) ─────────────────────────────────────────────────────────
func _scatter(rng: RandomNumberGenerator, r_min: float, r_max: float) -> Vector3:
	var a := rng.randf_range(0, TAU)
	var r := rng.randf_range(r_min, r_max)
	return Vector3(cos(a) * r, 0, sin(a) * r)

func _place(host: Node3D, _rng: RandomNumberGenerator, path: String, pos: Vector3, rot_y: float, scl: float, tint = null) -> Node3D:
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
func _tint(node: Node, color: Color) -> void:
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

func _tree_ring(host: Node3D, rng: RandomNumberGenerator, pool: Array, r0: float, r1: float, n: int, s0: float, s1: float) -> void:
	for i in n:
		var a := TAU * i / float(n) + rng.randf_range(-0.08, 0.08)
		var r := rng.randf_range(r0, r1)
		var tree: String = pool[rng.randi() % pool.size()]
		_place(host, rng, NAT + tree + ".gltf", Vector3(cos(a) * r, 0, sin(a) * r), rng.randf_range(0, 360), rng.randf_range(s0, s1))

func _ground(host: Node3D, color: Color, radius: float) -> void:
	var mi := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = radius; c.bottom_radius = radius; c.height = 0.5
	mi.mesh = c
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color; mat.roughness = 1.0
	mi.material_override = mat
	mi.position = Vector3(0, -0.25, 0)
	host.add_child(mi)

func _disc(host: Node3D, color: Color, radius: float, y: float) -> void:
	var mi := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = radius; c.bottom_radius = radius; c.height = 0.04
	mi.mesh = c
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color; mat.roughness = 1.0
	mi.material_override = mat
	mi.position = Vector3(0, y, 0)
	host.add_child(mi)

func _brazier(host: Node3D, pos: Vector3) -> void:
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
