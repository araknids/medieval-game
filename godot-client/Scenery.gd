extends RefCounted
# ── Construtor de CENÁRIOS 3D (compartilhado) ───────────────────────────────────
# Uso: const Scenery := preload("res://Scenery.gd"); var sc := Scenery.new()
#      sc.night_lighting(host); sc.mining(host, rng, combat_r)
# (métodos de INSTÂNCIA — não static, p/ evitar erro de parse; sem class_name p/
#  evitar flakiness de registro de classe global em script novo)
# Monta o cenário num `host: Node3D` (viewer World OU a batalha BattleReplay),
# mantendo o CENTRO livre (raio combat_r). Assets gitignored em res://assets/world/.

const NAT := "res://assets/world/nature/"
const VIL := "res://assets/world/village/"   # kit Medieval Village (grade de 2m: piso 2x2, parede 2x3m)

# Dispatcher: escolhe iluminação + geometria pelo nome do cenário.
func build(host: Node3D, scenario: String, rng: RandomNumberGenerator, combat_r: float) -> void:
	match scenario:
		"beach":
			dusk_lighting(host)
			beach(host, rng, combat_r)
		"garimpa":
			day_lighting(host)
			garimpa(host, rng, combat_r)
		"dungeon":
			dungeon_lighting(host)
			dungeon(host, rng, combat_r)
		"arena":
			day_lighting(host)
			arena(host, rng, combat_r)
		"city":
			day_lighting(host)
			city(host, rng, combat_r)
		_:  # "mining" (default)
			night_lighting(host)
			mining(host, rng, combat_r)

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

# ── iluminação de ENTARDECER (pôr do sol quente — enche o horizonte, sem cinza) ──
func dusk_lighting(host: Node3D) -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.24, 0.20, 0.40)      # roxo lá em cima
	sky_mat.sky_horizon_color = Color(0.98, 0.55, 0.30)  # laranja no horizonte
	sky_mat.ground_horizon_color = Color(0.7, 0.45, 0.32)
	sky_mat.ground_bottom_color = Color(0.20, 0.15, 0.16)
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.6
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.95, 0.55, 0.35)        # névoa quente funde no pôr do sol
	env.fog_density = 0.004
	env.glow_enabled = true
	env.glow_intensity = 0.4
	env.glow_bloom = 0.1
	var we := WorldEnvironment.new()
	we.environment = env
	host.add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-9, -38, 0)           # sol baixo no horizonte
	sun.light_color = Color(1.0, 0.62, 0.36)
	sun.light_energy = 1.5
	sun.shadow_enabled = true
	host.add_child(sun)

# ── cenário: MINERAÇÃO (noite) ──────────────────────────────────────────────────
# Centro LIVRE p/ combate; veio de minério num OUTCROP lateral; mata densa no fundo;
# braseiros quentes ao redor da clareira iluminam os lutadores. Minério BRILHA.
func mining(host: Node3D, rng: RandomNumberGenerator, combat_r: float) -> void:
	var OUTCROP := Vector3(-13.0, 0, 5.0)   # veio BEM afastado do combate (não bloqueia os lutadores)
	_ground(host, Color(0.31, 0.28, 0.20), 40.0)
	# colisão do chão (p/ o ragdoll da batalha não atravessar o piso)
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)

	# CAMINHO de ladrilho RETO no eixo X (por onde os lutadores entram): vai até bem longe
	# nos dois lados (some na distância). Os 2 chegam de pontas opostas e se encontram no meio.
	_cobble_path(host, rng, 30.0, 2.0)

	# VEIO de minério (outcrop lateral, LONGE): parede de rocha + veios tingidos que brilham
	var ores := [Color(0.95, 0.78, 0.22), Color(0.82, 0.46, 0.22), Color(0.62, 0.66, 0.72), Color(0.25, 0.78, 0.5)]
	for i in 4:
		var off := Vector3(rng.randf_range(-2.2, 2.2), 0, rng.randf_range(-2.2, 2.2))
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), OUTCROP + off, rng.randf_range(0, 360), rng.randf_range(1.3, 1.9))
	for i in 7:
		var off2 := Vector3(rng.randf_range(-2.8, 2.8), rng.randf_range(0, 0.7), rng.randf_range(-2.8, 2.8))
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), OUTCROP + off2,
				rng.randf_range(0, 360), rng.randf_range(0.6, 1.0), ores[i % ores.size()])

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

# ── cenário: PRAIA (entardecer) — ilha de areia cercada de mar ──────────────────
func beach(host: Node3D, rng: RandomNumberGenerator, combat_r: float) -> void:
	var ISLE := 24.0   # raio da ilha de areia (menor → a orla e o mar ficam perto/visíveis)
	_ground(host, Color(0.82, 0.73, 0.52), ISLE)   # ilha de AREIA
	# colisão do chão (p/ o ragdoll não atravessar)
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)
	# MAR ao redor — BAIXO (y=-0.9) → cria uma ORLA visível (areia desce pro mar) e enche o horizonte
	_water(host, Vector3(0, -0.9, 0), Vector2(800, 800))
	# AREIA MOLHADA na beira + areia seca no meio (gradiente até a água)
	_disc(host, Color(0.58, 0.52, 0.40), ISLE - 0.5, 0.015)
	_disc(host, Color(0.82, 0.73, 0.52), ISLE - 7.0, 0.02)
	# ROCHAS de maré meio submersas na orla
	for i in 16:
		var ar := TAU * i / 16.0 + rng.randf_range(-0.25, 0.25)
		var rr := rng.randf_range(ISLE - 4.0, ISLE + 1.0)
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), Vector3(cos(ar) * rr, -0.6, sin(ar) * rr), rng.randf_range(0, 360), rng.randf_range(1.0, 2.0))
	# DUNA com árvores só de UM lado (o outro fica aberto pro mar/pôr do sol)
	var trees := ["CommonTree_1", "CommonTree_2", "CommonTree_4", "TwistedTree_1", "TwistedTree_2", "TwistedTree_3"]
	for i in 14:
		var at := lerpf(0.4, PI - 0.4, rng.randf())   # arco de ~180° de um lado só
		var rt := rng.randf_range(13.0, ISLE - 2.0)
		_place(host, rng, NAT + trees[rng.randi() % trees.size()] + ".gltf", Vector3(cos(at) * rt, 0, sin(at) * rt), rng.randf_range(0, 360), rng.randf_range(0.9, 1.6))
	# capim de praia + arbustos (fora do combate)
	for i in 70:
		var g: String = ["Grass_Wispy_Tall", "Grass_Wispy_Short", "Grass_Common_Tall", "Bush_Common", "Fern_1"][i % 5]
		_place(host, rng, NAT + g + ".gltf", _scatter(rng, combat_r + 1.0, ISLE - 1.0), rng.randf_range(0, 360), rng.randf_range(0.8, 1.5))
	# pedrinhas/conchas espalhadas
	for i in 36:
		_place(host, rng, NAT + "Pebble_Round_%d.gltf" % (1 + i % 5), _scatter(rng, combat_r + 0.5, ISLE), rng.randf_range(0, 360), rng.randf_range(0.6, 1.3))

# Plano d'água (azul-turquesa; usado p/ o mar e p/ a faixa do rio).
func _water(host: Node3D, center: Vector3, size: Vector2) -> void:
	var mi := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = size
	mi.mesh = pm
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.08, 0.45, 0.66)   # azul-turquesa forte
	m.metallic = 0.6
	m.roughness = 0.14
	# emissão AZUL (ignora a luz laranja do pôr do sol) → a água lê como azul mesmo no entardecer
	m.emission_enabled = true
	m.emission = Color(0.08, 0.42, 0.62)
	m.emission_energy_multiplier = 0.6
	mi.material_override = m
	mi.position = center
	host.add_child(mi)

# CAMINHO de ladrilho reto no eixo X: faixa de largura 2*half_w, comprimento 2*half_len.
# Vai de uma ponta à outra (some na distância) — os lutadores entram pelas pontas.
func _cobble_path(host: Node3D, rng: RandomNumberGenerator, half_len: float, half_w: float) -> void:
	var tiles := ["RockPath_Square_Wide", "RockPath_Square_Thin", "RockPath_Round_Wide"]
	var step := 1.6
	var x := -half_len
	while x <= half_len:
		var z := -half_w
		while z <= half_w:
			var t: String = tiles[rng.randi() % tiles.size()]
			var pos := Vector3(x + rng.randf_range(-0.25, 0.25), 0.03, z + rng.randf_range(-0.25, 0.25))
			_place(host, rng, NAT + t + ".gltf", pos, rng.randf_range(0, 360), rng.randf_range(1.0, 1.4))
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

# ── iluminação de DIA (céu azul claro — enche o horizonte, fresco) ───────────────
func day_lighting(host: Node3D) -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.24, 0.45, 0.78)
	sky_mat.sky_horizon_color = Color(0.72, 0.82, 0.90)
	sky_mat.ground_horizon_color = Color(0.58, 0.60, 0.50)
	sky_mat.ground_bottom_color = Color(0.30, 0.32, 0.24)
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.6
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.75, 0.82, 0.86)
	env.fog_density = 0.004
	var we := WorldEnvironment.new()
	we.environment = env
	host.add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-52, -45, 0)
	sun.light_color = Color(1.0, 0.97, 0.9)
	sun.light_energy = 1.5
	sun.shadow_enabled = true
	host.add_child(sun)

# ── cenário: GARIMPO (dia) — beira de rio com muito cascalho ────────────────────
func garimpa(host: Node3D, rng: RandomNumberGenerator, combat_r: float) -> void:
	var BANK := 8.0            # x onde a margem encontra a água
	var RIVER_X := BANK + 5.0  # centro do rio (~13), correndo ao longo de Z
	_ground(host, Color(0.37, 0.32, 0.22), 44.0)   # terra/cascalho do vale
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)
	# leito MOLHADO (faixa escura, mais larga que a água) + ÁGUA azul do rio (ao longo de Z)
	_flat(host, Color(0.20, 0.18, 0.15), Vector3(RIVER_X, 0.02, 0), Vector2(12.0, 80.0))
	_water(host, Vector3(RIVER_X, 0.05, 0), Vector2(10.0, 80.0))
	# pedras grandes molhadas na beira do rio
	for i in 12:
		var x := BANK + rng.randf_range(-1.5, 1.2)
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), Vector3(x, 0, rng.randf_range(-30, 30)), rng.randf_range(0, 360), rng.randf_range(0.9, 1.8))
	# CASCALHO (muito) na margem perto da água — onde se garimpa
	for i in 80:
		var x2 := rng.randf_range(combat_r - 1.0, BANK)
		_place(host, rng, NAT + "Pebble_Round_%d.gltf" % (1 + i % 5), Vector3(x2, 0, rng.randf_range(-30, 30)), rng.randf_range(0, 360), rng.randf_range(0.7, 1.6))
	# cascalho miúdo também no centro (área de garimpo/luta)
	for i in 30:
		_place(host, rng, NAT + "Pebble_Square_%d.gltf" % (1 + i % 6), _scatter(rng, 0.5, combat_r), rng.randf_range(0, 360), rng.randf_range(0.6, 1.2))
	# MATA do vale no lado -X (oposto ao rio) + margem oposta
	var trees := ["CommonTree_1", "CommonTree_2", "CommonTree_3", "CommonTree_5", "Pine_1", "Pine_2", "TwistedTree_1"]
	for i in 24:
		var a := lerpf(PI * 0.42, PI * 1.58, rng.randf())   # arco do lado -X
		var r := rng.randf_range(14.0, 36.0)
		_place(host, rng, NAT + trees[rng.randi() % trees.size()] + ".gltf", Vector3(cos(a) * r, 0, sin(a) * r), rng.randf_range(0, 360), rng.randf_range(1.0, 1.8))
	for i in 9:   # margem oposta do rio (enquadra)
		_place(host, rng, NAT + trees[rng.randi() % trees.size()] + ".gltf", Vector3(RIVER_X + rng.randf_range(7, 15), 0, rng.randf_range(-30, 30)), rng.randf_range(0, 360), rng.randf_range(1.0, 1.8))
	# vegetação de margem
	for i in 80:
		var g: String = ["Fern_1", "Bush_Common", "Grass_Common_Tall", "Grass_Wispy_Tall", "Grass_Wispy_Short", "Mushroom_Common"][i % 6]
		_place(host, rng, NAT + g + ".gltf", _scatter(rng, combat_r + 0.6, 34.0), rng.randf_range(0, 360), rng.randf_range(0.8, 1.5))

# ── iluminação de MASMORRA (escura, sem sol — as tochas iluminam) ───────────────
func dungeon_lighting(host: Node3D) -> void:
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.02, 0.02, 0.035)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.12, 0.13, 0.20)
	env.ambient_light_energy = 0.4
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.05, 0.05, 0.08)
	env.fog_density = 0.018
	env.glow_enabled = true
	env.glow_intensity = 0.4
	env.glow_bloom = 0.1
	var we := WorldEnvironment.new()
	we.environment = env
	host.add_child(we)
	var fill := DirectionalLight3D.new()   # leve fill frio de cima (luar pelas frestas)
	fill.rotation_degrees = Vector3(-70, -20, 0)
	fill.light_color = Color(0.3, 0.35, 0.5)
	fill.light_energy = 0.25
	host.add_child(fill)

# ── cenário: DUNGEON — salão de pedra (kit Village), aberto pro lado da câmera ───
func dungeon(host: Node3D, rng: RandomNumberGenerator, _combat_r: float) -> void:
	var RX := 12.0   # meia-largura (X = eixo dos lutadores)
	var RZ := 8.0    # meia-profundidade (Z); o lado +Z fica ABERTO (câmera olha pra dentro)
	# PISO de tijolo (grade 2x2)
	var x := -RX
	while x <= RX + 0.1:
		var z := -RZ
		while z <= RZ + 0.1:
			_place(host, rng, VIL + "Floor_UnevenBrick.gltf", Vector3(x, 0, z), rng.randi_range(0, 3) * 90.0, 1.0)
			z += 2.0
		x += 2.0
	# colisão do chão
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)
	# PAREDE do FUNDO (Z=-RZ-1), de frente pro salão (+Z)
	var wx := -RX
	while wx <= RX + 0.1:
		_place(host, rng, VIL + "Wall_UnevenBrick_Straight.gltf", Vector3(wx, 0, -RZ - 1.0), 0, 1.0)
		wx += 2.0
	# PAREDES das PONTAS (X=±(RX+1)) viradas pra dentro; ARCO no meio (entrada dos lutadores)
	for sx in [-1.0, 1.0]:
		var rot := -90.0 if sx > 0 else 90.0
		var wz := -RZ
		while wz <= RZ + 0.1:
			var piece: String = "Wall_Arch" if absf(wz) < 1.1 else "Wall_UnevenBrick_Straight"
			_place(host, rng, VIL + piece + ".gltf", Vector3(sx * (RX + 1.0), 0, wz), rot, 1.0)
			wz += 2.0
	# TOCHAS (braseiros) ao longo das paredes
	for tz in [-6.0, -1.0, 4.0]:
		_brazier(host, Vector3(-RX + 0.7, 0, tz))
		_brazier(host, Vector3(RX - 0.7, 0, tz))
	_brazier(host, Vector3(-5.0, 0, -RZ + 0.7))
	_brazier(host, Vector3(5.0, 0, -RZ + 0.7))

# ── cenário: ARENA de duelo — coliseu de pedra (kit Village), de dia ─────────────
func arena(host: Node3D, rng: RandomNumberGenerator, _combat_r: float) -> void:
	_ground(host, Color(0.60, 0.50, 0.33), 20.0)        # base
	_disc(host, Color(0.72, 0.61, 0.41), 10.0, 0.02)    # pit de AREIA (clareira de combate)
	# colisão do chão
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)
	# BARREIRA interna (parede baixa em volta do pit) + MURALHA externa alta (2 fileiras)
	_ring(host, rng, "Wall_UnevenBrick_Straight", 11.0, 0.0, 36, 1.0)
	_ring(host, rng, "Wall_UnevenBrick_Straight", 14.0, 0.0, 44, 1.0)
	_ring(host, rng, "Wall_UnevenBrick_Straight", 14.0, 3.0, 44, 1.0)   # 2ª fileira empilhada → muralha alta
	# BANNERS coloridos na barreira interna
	var cols := [Color(0.7, 0.15, 0.15), Color(0.15, 0.3, 0.7), Color(0.85, 0.7, 0.2), Color(0.2, 0.55, 0.3)]
	for i in 8:
		var a := TAU * i / 8.0
		_banner(host, Vector3(cos(a) * 10.4, 2.5, sin(a) * 10.4), cols[i % cols.size()])
	# TOCHAS ao redor do pit
	for i in 8:
		var a := TAU * i / 8.0 + 0.39
		_brazier(host, Vector3(cos(a) * 9.6, 0, sin(a) * 9.6))

# ── cenário: CIDADE/VILA — praça de pedra cercada de casas, de dia ──────────────
func city(host: Node3D, rng: RandomNumberGenerator, _combat_r: float) -> void:
	_ground(host, Color(0.40, 0.36, 0.27), 44.0)        # terra/rua
	_tile_circle(host, rng, "Floor_Brick", 8.0)          # PRAÇA de pedra (onde rola a luta)
	# colisão do chão
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)
	# CASAS em volta (pulando o vão da FRENTE, pro lado da câmera ficar aberto)
	for i in 7:
		var a := deg_to_rad(130.0 + i * 40.0)            # 130°..370°, vão aberto ~50°-130° (frente)
		var pos := Vector3(cos(a) * 12.0, 0, sin(a) * 12.0)
		var face := rad_to_deg(atan2(-cos(a), -sin(a)))  # porta virada pro centro
		_house(host, rng, pos, face)
	# árvores e luminárias entre as casas
	for i in 8:
		var a2 := deg_to_rad(140.0 + i * 30.0)
		var t: String = ["CommonTree_1", "CommonTree_3", "Pine_1"][i % 3]
		_place(host, rng, NAT + t + ".gltf", Vector3(cos(a2) * 17.0, 0, sin(a2) * 17.0), rng.randf_range(0, 360), rng.randf_range(1.0, 1.6))
	for i in 5:
		var a3 := deg_to_rad(150.0 + i * 45.0)
		_brazier(host, Vector3(cos(a3) * 9.0, 0, sin(a3) * 9.0))
	# MATA densa no fundo (atrás das casas) — enche o horizonte
	var pool := ["CommonTree_1", "CommonTree_2", "CommonTree_4", "CommonTree_5", "Pine_1", "Pine_2", "Pine_3"]
	_tree_ring(host, rng, pool, 24.0, 31.0, 30, 1.0, 1.7)
	_tree_ring(host, rng, pool, 33.0, 42.0, 42, 1.2, 2.0)

# Casa 4×4 (paredes c/ porta+janelas + frontão triangular + telhado). `face_deg` = frente virada pra essa direção.
func _house(host: Node3D, rng: RandomNumberGenerator, center: Vector3, face_deg: float) -> void:
	var c := Node3D.new()
	host.add_child(c)
	c.position = center
	c.rotation_degrees = Vector3(0, face_deg, 0)
	var H := 2.0   # meia-aresta da casa (4×4)
	var DOOR := VIL + "Wall_UnevenBrick_Door_Round.gltf"
	var WIN := VIL + "Wall_UnevenBrick_Window_Wide_Round.gltf"
	var STR := VIL + "Wall_UnevenBrick_Straight.gltf"
	var SHUT := VIL + "WindowShutters_Thin_Round_Open.gltf"   # postigos visíveis na janela
	# FRENTE (+Z): porta (+ lâmina) à esquerda + janela à direita
	_place(c, rng, DOOR, Vector3(-1, 0, H), 0, 1.0)
	_place(c, rng, VIL + "Door_2_Round.gltf", Vector3(-1.5, 0, H + 0.06), 0, 1.0)
	_window(c, rng, WIN, SHUT, Vector3(1, 0, H), 0)
	# FUNDO (-Z): janela + parede
	_window(c, rng, WIN, SHUT, Vector3(-1, 0, -H), 180)
	_place(c, rng, STR, Vector3(1, 0, -H), 180, 1.0)
	# LATERAIS (±X): janela + parede de cada lado
	_window(c, rng, WIN, SHUT, Vector3(H, 0, -1), 90)
	_place(c, rng, STR, Vector3(H, 0, 1), 90, 1.0)
	_window(c, rng, WIN, SHUT, Vector3(-H, 0, 1), 270)
	_place(c, rng, STR, Vector3(-H, 0, -1), 270, 1.0)
	# TELHADO + FRONTÃO (gable) triangular na frente e no fundo
	_place(c, rng, VIL + "Roof_RoundTiles_4x4.gltf", Vector3(0, 3.05, 0), 0, 1.0)
	_place(c, rng, VIL + "Roof_Front_Brick4.gltf", Vector3(0, 3.0, H + 0.1), 0, 1.0)
	_place(c, rng, VIL + "Roof_Front_Brick4.gltf", Vector3(0, 3.0, -H - 0.1), 180, 1.0)

# Parede de janela + postigos de madeira (na mesma posição/rotação) = janela visível.
func _window(host: Node3D, rng: RandomNumberGenerator, win: String, shut: String, pos: Vector3, rot: float) -> void:
	_place(host, rng, win, pos, rot, 1.0)
	_place(host, rng, shut, pos, rot, 1.0)

# Ladrilha um CÍRCULO de raio `radius` com a peça `piece` (grade de 2m).
func _tile_circle(host: Node3D, rng: RandomNumberGenerator, piece: String, radius: float) -> void:
	var x := -radius
	while x <= radius + 0.1:
		var z := -radius
		while z <= radius + 0.1:
			if Vector2(x, z).length() <= radius:
				_place(host, rng, VIL + piece + ".gltf", Vector3(x, 0.01, z), rng.randi_range(0, 3) * 90.0, 1.0)
			z += 2.0
		x += 2.0

# Anel de peças do kit (paredes) viradas pro CENTRO (look_at), com pouco overlap.
func _ring(host: Node3D, rng: RandomNumberGenerator, piece: String, r: float, y: float, n: int, scl: float) -> void:
	for i in n:
		var a := TAU * i / float(n)
		var pos := Vector3(cos(a) * r, y, sin(a) * r)
		var inst := _place(host, rng, VIL + piece + ".gltf", pos, 0.0, scl)
		if inst: inst.look_at(Vector3(0, pos.y, 0), Vector3.UP)   # -Z (face fina) aponta pro centro

# Estandarte de pano (quad vertical) pendurado, virado pro centro.
func _banner(host: Node3D, pos: Vector3, color: Color) -> void:
	var mi := MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(0.95, 2.1)
	mi.mesh = q
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	m.roughness = 0.9
	m.cull_mode = BaseMaterial3D.CULL_DISABLED   # mostra dos 2 lados
	mi.material_override = m
	host.add_child(mi)
	mi.position = pos
	mi.look_at(Vector3(0, pos.y, 0), Vector3.UP)

# Faixa/plano retangular colorido (leito do rio, areia molhada, etc.).
func _flat(host: Node3D, color: Color, center: Vector3, size: Vector2) -> void:
	var mi := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = size
	mi.mesh = pm
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color; mat.roughness = 1.0
	mi.material_override = mat
	mi.position = center
	host.add_child(mi)
