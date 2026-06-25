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

# [GODOT_GRIMDARK] pós-processo de clima sombrio (ligado por padrão). Design: docs/PLANO_GODOT_GRIMDARK.md
var grimdark := true
var _is_day := false   # [DIA] cenas de dia (day_lighting) → braseiros SEM luz (o sol já ilumina; tocha lavava o chão)

# Shader de tela (overlay full-screen): vinheta + dessaturação + contraste + tint + grão.
# Lê o frame já renderizado (3D) via hint_screen_texture e devolve a versão "grimdark".
const GRIMDARK_SHADER := """
shader_type canvas_item;
uniform sampler2D screen_tex : hint_screen_texture, filter_linear_mipmap;
uniform float vignette = 0.28;     // [NITIDEZ] cantos bem mais sutis (era 0.5 → escuro estranho em volta)
uniform float vradius = 0.72;      // [NITIDEZ] começa mais pra fora → só as PONTAS escurecem (era 0.55)
uniform float saturation = 0.85;   // <1 dessatura (grimdark = cor contida)
uniform float contrast = 1.08;     // sombras mais fundas
uniform vec3  tint = vec3(1.03, 0.99, 0.92);  // leve sépia/quente
uniform float grain = 0.012;       // [NITIDEZ] grão de filme bem mais sutil (era 0.035 → "sujo")
void fragment() {
	vec3 col = texture(screen_tex, SCREEN_UV).rgb;
	col = (col - 0.5) * contrast + 0.5;
	float l = dot(col, vec3(0.299, 0.587, 0.114));
	col = mix(vec3(l), col, saturation);
	col *= tint;
	float n = fract(sin(dot(SCREEN_UV * (TIME + 1.0), vec2(12.9898, 78.233))) * 43758.5453);
	col += (n - 0.5) * grain;
	float d = length(SCREEN_UV - vec2(0.5)) * 1.41421;
	float vig = smoothstep(vradius, 1.0, d);
	col *= mix(1.0, 1.0 - vignette, vig);
	COLOR = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""

# Dispatcher: escolhe iluminação + geometria pelo nome do cenário.
# grim=false desliga o pós-processo grimdark (look cru de cada perfil) — p/ A/B.
func build(host: Node3D, scenario: String, rng: RandomNumberGenerator, combat_r: float, grim := true) -> void:
	grimdark = grim
	_is_day = false   # default noite/dusk; day_lighting liga
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
		"castle":
			day_lighting(host)
			castle(host, rng, combat_r)
		"cursed_tower":
			cursed_tower_lighting(host)
			cursed_tower(host, rng, combat_r)
		_:  # "mining" (default)
			night_lighting(host)
			mining(host, rng, combat_r)
	_grimdark_overlay(host)   # filtro de tela (vinheta+grade) por cima de qualquer mapa

# [GODOT_GRIMDARK] grade no Environment (no pipeline 3D): bloom dos emissivos + SSAO + exposição.
# Chamado no fim dos 4 perfis de luz — sobrescreve o glow de cada um por um valor unificado.
func grimdark_grade(env: Environment) -> void:
	if not grimdark: return
	# GLOW: só emissivos FORTES florescem (tochas, sangue, minério) — não lava a cena toda. [NITIDEZ]
	# bloom=0 + threshold>1 → o chão claro (lum ~1.0) PARA de brilhar; só HDR (tochas) floresce.
	env.glow_enabled = true
	env.glow_intensity = 0.30
	env.glow_strength = 1.0
	env.glow_bloom = 0.0
	env.glow_hdr_threshold = 1.05
	env.glow_blend_mode = Environment.GLOW_BLEND_MODE_SCREEN
	# SSAO: oclusão de contato → cantos/junções encardidos (Forward+; ignorado de boa em Compatibility)
	env.ssao_enabled = true
	env.ssao_radius = 1.1
	env.ssao_intensity = 1.8
	env.ssao_power = 1.5
	# exposição levemente pra baixo → mood mais sombrio
	env.tonemap_exposure = 0.92

# [GODOT_GRIMDARK] overlay de tela: CanvasLayer(0) + ColorRect full-screen com o GRIMDARK_SHADER.
# layer 0 fica ABAIXO da UI da batalha (layer 1, criada depois) → números/vitória continuam limpos.
func _grimdark_overlay(host: Node3D) -> void:
	if not grimdark: return
	var layer := CanvasLayer.new()
	layer.layer = 0
	host.add_child(layer)
	var rect := ColorRect.new()
	rect.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	rect.mouse_filter = Control.MOUSE_FILTER_IGNORE   # não rouba clique/hover
	var sh := Shader.new()
	sh.code = GRIMDARK_SHADER
	var mat := ShaderMaterial.new()
	mat.shader = sh
	rect.material = mat
	layer.add_child(rect)

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
	grimdark_grade(env)
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
	grimdark_grade(env)
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
	# wildflowers esparsas — cor no chão escuro da clareira [realismo]
	for i in 14:
		_place(host, rng, NAT + ["Flower_3_Group", "Flower_4_Group", "Flower_3_Single"][i % 3] + ".gltf", _scatter(rng, combat_r + 1.0, 34.0), rng.randf_range(0, 360), rng.randf_range(0.7, 1.2))

	# BRASEIROS ao redor da clareira (luz quente nos lutadores)
	for i in 5:
		var a := TAU * i / 5.0 + 0.6
		_brazier(host, Vector3(cos(a) * (combat_r + 1.2), 0, sin(a) * (combat_r + 1.2)))
	_fireflies(host, 32, 15.0, Color(0.45, 0.95, 0.55))   # vagalumes (vende a noite) [Fable]

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
	# DEGRAU de areia molhada (disfarça o penhasco) + ESPUMA na linha d'água [Fable]
	_disc(host, Color(0.50, 0.45, 0.36), ISLE + 2.0, -0.45)
	_foam(host, ISLE + 0.6, -0.82)
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
func _cobble_path(host: Node3D, rng: RandomNumberGenerator, half_len: float, half_w: float, z_center := 0.0) -> void:
	var tiles := ["RockPath_Square_Wide", "RockPath_Square_Thin", "RockPath_Round_Wide"]
	var step := 1.6
	var x := -half_len
	while x <= half_len:
		var z := -half_w
		while z <= half_w:
			var t: String = tiles[rng.randi() % tiles.size()]
			var pos := Vector3(x + rng.randf_range(-0.25, 0.25), 0.03, z_center + z + rng.randf_range(-0.25, 0.25))
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

# Anel de espuma branca (na linha d'água da praia) — emissiva p/ brilhar no pôr do sol.
func _foam(host: Node3D, radius: float, y: float) -> void:
	var mi := MeshInstance3D.new()
	var c := CylinderMesh.new()
	c.top_radius = radius; c.bottom_radius = radius; c.height = 0.04
	mi.mesh = c
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.95, 0.97, 0.95)
	m.emission_enabled = true
	m.emission = Color(0.9, 0.95, 1.0)
	m.emission_energy_multiplier = 0.3
	m.roughness = 1.0
	mi.material_override = m
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
	cmat.emission_energy_multiplier = (1.4 if _is_day else 5.0)   # [DIA] de dia a brasa não floresce (sem halo)
	coal.material_override = cmat
	host.add_child(coal); coal.position = pos + Vector3(0, 1.75, 0)
	# [DIA] de dia o sol já ilumina → SEM luz de tocha (era ela que lavava o chão/paredes de laranja).
	var light: OmniLight3D = null
	if not _is_day:
		light = OmniLight3D.new()
		light.light_color = Color(1.0, 0.62, 0.28)
		light.light_energy = 2.6
		light.omni_range = 7.5
		host.add_child(light); light.position = pos + Vector3(0, 1.9, 0)
	# CHAMA de partículas (pega o glow dos cenários) + FLICKER da luz [Fable]
	var p := GPUParticles3D.new()
	p.amount = 14
	p.lifetime = 0.7
	var m := ParticleProcessMaterial.new()
	m.direction = Vector3.UP
	m.spread = 12.0
	m.initial_velocity_min = 0.6
	m.initial_velocity_max = 1.4
	m.gravity = Vector3(0, 1.5, 0)            # fogo sobe acelerando
	m.scale_min = 0.6
	m.scale_max = 1.2
	var g := Gradient.new()
	g.set_color(0, Color(1.0, 0.75, 0.2, 0.9))
	g.add_point(0.5, Color(1.0, 0.35, 0.05, 0.6))
	g.set_color(2, Color(0.3, 0.05, 0.02, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g
	m.color_ramp = gt
	p.process_material = m
	var q := QuadMesh.new(); q.size = Vector2(0.22, 0.22)
	var qm := StandardMaterial3D.new()
	qm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	qm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	qm.vertex_color_use_as_albedo = true
	qm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	qm.emission_enabled = true
	qm.emission = Color(1.0, 0.5, 0.1)
	qm.emission_energy_multiplier = 2.0
	q.material = qm
	p.draw_pass_1 = q
	host.add_child(p); p.position = pos + Vector3(0, 1.85, 0)
	if light != null:   # [DIA] sem luz → sem flicker (a tocha só tem chama visível)
		var tw := host.create_tween().set_loops()   # flicker (loop barato)
		tw.tween_property(light, "light_energy", 2.2, 0.13)
		tw.tween_property(light, "light_energy", 3.0, 0.17)
		tw.tween_property(light, "light_energy", 2.5, 0.11)

# ── iluminação de DIA (céu azul claro — enche o horizonte, fresco) ───────────────
func day_lighting(host: Node3D) -> void:
	_is_day = true   # [DIA] braseiros não emitem luz (o sol ilumina; a tocha só lavava o chão de dia)
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
	env.ambient_light_energy = 0.42                  # [NITIDEZ] era 0.6 → menos clarão/lavado no chão
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.62, 0.66, 0.70)    # [NITIDEZ] névoa menos clara
	env.fog_density = 0.0015                          # [NITIDEZ] era 0.004 → menos haze
	grimdark_grade(env)
	env.tonemap_exposure = 0.78                       # [NITIDEZ] cenas-dia (arena/cidade/castelo) sem estourar o chão
	var we := WorldEnvironment.new()
	we.environment = env
	host.add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-52, -45, 0)
	sun.light_color = Color(1.0, 0.97, 0.9)
	sun.light_energy = 1.1    # [PEDRA] era 1.5 → menos estouro no chão de dia
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
	# wildflowers + arbustos floridos na margem [realismo]
	for i in 16:
		_place(host, rng, NAT + ["Flower_3_Group", "Flower_4_Group", "Bush_Common_Flowers"][i % 3] + ".gltf", _scatter(rng, combat_r + 1.0, 30.0), rng.randf_range(0, 360), rng.randf_range(0.7, 1.2))
	# CALHA (sluice) de garimpo na margem + baldes + carroça [Fable]
	var sx := BANK - 1.2
	_box(host, Vector3(0.7, 0.15, 2.4), Vector3(sx, 0.55, 5.0), Color(0.4, 0.28, 0.15), 8.0)   # calha inclinada
	_box(host, Vector3(0.12, 1.0, 0.12), Vector3(sx - 0.2, 0.3, 4.0), Color(0.3, 0.2, 0.12))
	_box(host, Vector3(0.12, 1.0, 0.12), Vector3(sx + 0.2, 0.3, 6.0), Color(0.3, 0.2, 0.12))
	for k in 3:   # baldes (cilindros)
		var bk := MeshInstance3D.new()
		var cy := CylinderMesh.new(); cy.top_radius = 0.22; cy.bottom_radius = 0.18; cy.height = 0.4
		bk.mesh = cy
		var bm := StandardMaterial3D.new(); bm.albedo_color = Color(0.32, 0.22, 0.12); bm.roughness = 1.0
		bk.material_override = bm
		host.add_child(bk); bk.position = Vector3(BANK - rng.randf_range(0.5, 2.0), 0.2, rng.randf_range(-3, 8))
	_place(host, rng, VIL + "Prop_Wagon.gltf", Vector3(BANK - 3.0, 0, -6.0), 120, 1.0)
	_river_mist(host, RIVER_X)   # névoa baixa deslizando sobre o rio

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
	grimdark_grade(env)
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
	# PAREDE do FUNDO (Z=-RZ-1), de frente pro salão (+Z) — 2 fileiras (pé-direito alto)
	var wx := -RX
	while wx <= RX + 0.1:
		_place(host, rng, VIL + "Wall_UnevenBrick_Straight.gltf", Vector3(wx, 0, -RZ - 1.0), 0, 1.0)
		_place(host, rng, VIL + "Wall_UnevenBrick_Straight.gltf", Vector3(wx, 3.0, -RZ - 1.0), 0, 1.0)
		wx += 2.0
	# PAREDES das PONTAS (X=±(RX+1)) viradas pra dentro; ARCO no meio (entrada dos lutadores)
	for sx in [-1.0, 1.0]:
		var rot := -90.0 if sx > 0 else 90.0
		var wz := -RZ
		while wz <= RZ + 0.1:
			var piece: String = "Wall_Arch" if absf(wz) < 1.1 else "Wall_UnevenBrick_Straight"
			_place(host, rng, VIL + piece + ".gltf", Vector3(sx * (RX + 1.0), 0, wz), rot, 1.0)
			if piece != "Wall_Arch":   # 2ª fileira (não em cima do arco da entrada)
				_place(host, rng, VIL + "Wall_UnevenBrick_Straight.gltf", Vector3(sx * (RX + 1.0), 3.0, wz), rot, 1.0)
			wz += 2.0
	# TOCHAS (braseiros) ao longo das paredes
	for tz in [-6.0, -1.0, 4.0]:
		_brazier(host, Vector3(-RX + 0.7, 0, tz))
		_brazier(host, Vector3(RX - 0.7, 0, tz))
	_brazier(host, Vector3(-5.0, 0, -RZ + 0.7))
	_brazier(host, Vector3(5.0, 0, -RZ + 0.7))
	# COLUNAS de pedra internas (quebra o "caixote vazio") [Fable]
	for cx in [-RX + 3.0, RX - 3.0]:
		for cz in [-RZ + 3.0, RZ - 3.5]:
			_box(host, Vector3(0.7, 6.4, 0.7), Vector3(cx, 3.2, cz), Color(0.42, 0.40, 0.37))
	# ENTULHO nos cantos (rochas pequenas + tijolos caídos)
	for i in 8:
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), Vector3(rng.randf_range(-RX + 1, RX - 1), 0, rng.randf_range(-RZ + 1, -RZ + 3)), rng.randf_range(0, 360), rng.randf_range(0.4, 0.7))
	for i in 6:
		_place(host, rng, VIL + "Prop_Brick%d.gltf" % (1 + i % 4), Vector3(rng.randf_range(-RX + 1, RX - 1), 0, rng.randf_range(-RZ + 1, RZ - 1)), rng.randf_range(0, 360), 1.0)
	# UMIDADE: poças escuras fora do centro + musgo nas frestas (masmorra viva, não tabuleiro liso)
	_flat(host, Color(0.04, 0.05, 0.06), Vector3(-4.5, 0.03, 3.0), Vector2(3.2, 2.2))
	_flat(host, Color(0.04, 0.05, 0.06), Vector3(5.0, 0.03, -2.5), Vector2(2.4, 3.0))
	for i in 12:
		_place(host, rng, NAT + ["Grass_Wispy_Short", "Clover_1", "Fern_1"][i % 3] + ".gltf", Vector3(rng.randf_range(-RX + 1, RX - 1), 0.03, rng.randf_range(-RZ + 1, RZ - 1)), rng.randf_range(0, 360), rng.randf_range(0.3, 0.55))

# ── cenário: ARENA de duelo — coliseu de pedra (kit Village), de dia ─────────────
func arena(host: Node3D, rng: RandomNumberGenerator, _combat_r: float) -> void:
	_ground(host, Color(0.32, 0.35, 0.23), 52.0)        # campo gramado em volta (largo p/ a mata pousar)
	_disc(host, Color(0.17, 0.17, 0.19), 14.5, 0.0)     # [PEDRA] plataforma de PEDRA escura
	_disc(host, Color(0.23, 0.23, 0.26), 10.0, 0.02)    # [PEDRA] pit de PEDRA cinza (mais escuro → não estoura)
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
	# [DIA] CAIXAS ao redor do pit no lugar das fogueiras (sem luz que lava o chão de dia); variadas + algumas empilhadas
	for i in 8:
		var a := TAU * i / 8.0 + 0.39
		var base := Vector3(cos(a) * 9.6, 0, sin(a) * 9.6)
		_place(host, rng, VIL + "Prop_Crate.gltf", base, rad_to_deg(a) + rng.randf_range(-25, 25), rng.randf_range(0.9, 1.25))
		if rng.randf() < 0.5:   # metade ganha uma caixa empilhada por cima (variedade)
			_place(host, rng, VIL + "Prop_Crate.gltf", base + Vector3(rng.randf_range(-0.2, 0.2), 0.9, rng.randf_range(-0.2, 0.2)), rng.randf_range(0, 360), rng.randf_range(0.7, 0.95))
	# AMEIAS no topo do anel externo (silhueta de coliseu) [Fable]
	for i in 44:
		var am := TAU * i / 44.0
		_merlon(host, Vector3(cos(am) * 14.0, 6.15, sin(am) * 14.0), rad_to_deg(am))
	# ARCOS de entrada nas pontas do eixo X (por onde os lutadores entram)
	for ex in [-1.0, 1.0]:
		var arch := _place(host, rng, VIL + "Wall_Arch.gltf", Vector3(ex * 10.6, 0, 0), 0.0, 1.0)
		if arch: arch.look_at(Vector3(0, 0, 0), Vector3.UP)
	# PLATEIA fake no topo do anel externo
	_crowd(host, rng, 13.4, 6.3, 40)
	# CAMPO + MATA atrás do coliseu (antes o horizonte ficava vazio além da muralha)
	_meadow(host, rng, 16.0, 26.0, 110)
	_tree_ring(host, rng, ["CommonTree_1", "CommonTree_2", "CommonTree_4", "Pine_1", "Pine_2", "Pine_3"], 28.0, 40.0, 40, 1.3, 2.1)

# ── cenário: CASTELO — pátio cercado de muralhas (ameias) + torres de canto ─────
func castle(host: Node3D, rng: RandomNumberGenerator, _combat_r: float) -> void:
	var RX := 10.0   # meia-largura (X = eixo dos lutadores); FRENTE (+Z) aberta p/ a câmera
	var RZ := 8.0
	_ground(host, Color(0.33, 0.36, 0.24), 60.0)   # campo gramado em volta (some o disco marrom chapado)
	# PÁTIO de pedra cobrindo TODO o chão: de muralha a muralha (X) e do fundo até SAIR pelo
	# portão da frente (Z). Antes era um disco r=10 que deixava cantos/beiras na terra crua.
	_tile_rect(host, rng, "Floor_Brick", -RX, RX, -RZ - 2.0, RZ + 4.0)
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)
	# MURALHAS (fundo + 2 laterais), 2 fileiras (6m) + ameias; faces viradas pro pátio
	_wall_run(host, rng, Vector3(-RX, 0, -RZ - 1), Vector3(RX, 0, -RZ - 1), 0)     # fundo (face +Z)
	_wall_run(host, rng, Vector3(-RX - 1, 0, -RZ), Vector3(-RX - 1, 0, RZ), 90)    # esquerda (face +X)
	_wall_run(host, rng, Vector3(RX + 1, 0, -RZ), Vector3(RX + 1, 0, RZ), 270)     # direita (face -X)
	# 4 TORRES de canto (com pináculo)
	for sx in [-1.0, 1.0]:
		for sz in [-1.0, 1.0]:
			_tower(host, rng, Vector3(sx * (RX + 1.0), 0, sz * (RZ + 1.0)))
	# BANNERS na muralha do fundo + TOCHAS no pátio
	var cols := [Color(0.7, 0.15, 0.15), Color(0.15, 0.3, 0.7), Color(0.85, 0.7, 0.2)]
	for i in 5:
		_banner(host, Vector3(-8.0 + i * 4.0, 3.6, -RZ - 0.6), cols[i % cols.size()])
	for i in 6:
		var a := TAU * i / 6.0 + 0.4
		_brazier(host, Vector3(cos(a) * 7.5, 0, sin(a) * 7.5))
	# PÁTIO com vida: pilhas de caixas nos cantos + boneco de treino numa lateral [Fable]
	_crates(host, rng, Vector3(-RX + 1.5, 0, -RZ + 1.5))
	_crates(host, rng, Vector3(RX - 2.0, 0, -RZ + 1.5))
	_dummy(host, Vector3(-RX + 1.5, 0, 2.0))
	# ERVA DANINHA nas frestas das lajes junto às muralhas (pátio "vivido", não estéril)
	for i in 26:
		var wa := TAU * i / 26.0
		var wr: float = (RX - 0.4) if absf(cos(wa)) > absf(sin(wa)) else (RZ - 0.4)
		_place(host, rng, NAT + ["Grass_Wispy_Short", "Grass_Common_Short", "Clover_1"][i % 3] + ".gltf", Vector3(cos(wa) * wr, 0.04, sin(wa) * wr), rng.randf_range(0, 360), rng.randf_range(0.35, 0.6))
	# CAMPO gramado entre as muralhas e a mata + mata densa no fundo (enche o horizonte)
	_meadow(host, rng, 13.0, 28.0, 130)
	_tree_ring(host, rng, ["CommonTree_1", "CommonTree_4", "Pine_1", "Pine_2", "Pine_3"], 30.0, 42.0, 42, 1.3, 2.0)

# Torre redonda: anel de paredes (2 fileiras = 6m) + pináculo cônico no topo.
func _tower(host: Node3D, rng: RandomNumberGenerator, center: Vector3) -> void:
	var r := 2.2
	var n := 7
	for row in [0.0, 3.0]:
		for i in n:
			var a := TAU * i / float(n)
			var pos := center + Vector3(cos(a) * r, row, sin(a) * r)
			var inst := _place(host, rng, VIL + "Wall_UnevenBrick_Straight.gltf", pos, 0.0, 1.0)
			if inst: inst.look_at(Vector3(center.x, pos.y, center.z), Vector3.UP)
	# AMEIAS no parapeito da torre [Fable]
	for i in n:
		var am := TAU * i / float(n)
		_merlon(host, center + Vector3(cos(am) * r, 6.15, sin(am) * r), rad_to_deg(am))
	_place(host, rng, VIL + "Roof_Tower_RoundTiles.gltf", center + Vector3(0, 6.0, 0), 0.0, 1.0)
	_flag(host, center + Vector3(0, 11.5, 0), Color(0.7, 0.15, 0.15))   # bandeira no pináculo

# Fileira de muralha (2 fileiras empilhadas) + ameias (merlons) no topo, de `a` até `b`.
func _wall_run(host: Node3D, rng: RandomNumberGenerator, a: Vector3, b: Vector3, rot: float) -> void:
	var d := a.distance_to(b)
	var dir := (b - a) / maxf(d, 0.001)
	var n := int(round(d / 2.0))
	for i in n + 1:
		var p := a + dir * (i * 2.0)
		_place(host, rng, VIL + "Wall_UnevenBrick_Straight.gltf", p, rot, 1.0)
		_place(host, rng, VIL + "Wall_UnevenBrick_Straight.gltf", p + Vector3(0, 3.0, 0), rot, 1.0)
		_merlon(host, p + Vector3(0, 6.15, 0), rot)

# Ameia (merlon) — bloco de pedra no topo da muralha.
func _merlon(host: Node3D, pos: Vector3, rot: float) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.85, 0.7, 0.5)
	mi.mesh = bm
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.5, 0.47, 0.43)
	m.roughness = 1.0
	mi.material_override = m
	host.add_child(mi)
	mi.position = pos
	mi.rotation_degrees = Vector3(0, rot, 0)

# ── cenário: CIDADE/VILA — praça de pedra cercada de casas, de dia ──────────────
func city(host: Node3D, rng: RandomNumberGenerator, _combat_r: float) -> void:
	_ground(host, Color(0.34, 0.34, 0.23), 48.0)        # chão de terra/grama da vila (some o marrom chapado)
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
	# CAMINHOS de pedra ligando a praça às portas (a terra crua entre praça e casas sumiu)
	for i in 7:
		var pa := deg_to_rad(130.0 + i * 40.0)
		var pz := 8.0
		while pz <= 11.5:
			_place(host, rng, VIL + "Floor_Brick.gltf", Vector3(cos(pa) * pz, 0.02, sin(pa) * pz), rng.randi_range(0, 3) * 90.0, 1.0)
			pz += 1.8
	# tufos de grama entre os caminhos (jardim da vila), antes da fileira de casas
	_meadow(host, rng, 9.0, 11.5, 36)
	# árvores e luminárias entre as casas
	for i in 8:
		var a2 := deg_to_rad(140.0 + i * 30.0)
		var t: String = ["CommonTree_1", "CommonTree_3", "Pine_1"][i % 3]
		_place(host, rng, NAT + t + ".gltf", Vector3(cos(a2) * 17.0, 0, sin(a2) * 17.0), rng.randf_range(0, 360), rng.randf_range(1.0, 1.6))
	for i in 5:
		var a3 := deg_to_rad(150.0 + i * 45.0)
		_brazier(host, Vector3(cos(a3) * 9.0, 0, sin(a3) * 9.0))
	# POÇO no fundo da praça (marco) + RUA de pedra saindo pelo vão da frente [Fable]
	_well(host, Vector3(0, 0, -10.0))
	var zz := 8.0
	while zz <= 20.0:
		for sxr in [-2.0, 0.0, 2.0]:
			_place(host, rng, VIL + "Floor_Brick.gltf", Vector3(sxr, 0.01, zz), 0, 1.0)
		zz += 2.0
	# CAMPO gramado entre as casas e a mata + MATA densa no fundo — enche o horizonte
	_meadow(host, rng, 14.0, 22.0, 80)
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
	# CHAMINÉ + fumaça (telhado, lado de trás) [Fable]
	_place(c, rng, VIL + "Prop_Chimney.gltf", Vector3(0.7, 2.7, -0.6), 0, 1.0)
	_smoke(c, Vector3(0.7, 6.0, -0.6))

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

# Ladrilha um RETÂNGULO [x0..x1]×[z0..z1] (grade de 2m): cobre TODO o chão de um pátio
# até as muralhas — sem deixar o buraco de terra que o pátio CIRCULAR deixava nos cantos.
func _tile_rect(host: Node3D, rng: RandomNumberGenerator, piece: String, x0: float, x1: float, z0: float, z1: float, y := 0.01) -> void:
	var x := x0
	while x <= x1 + 0.1:
		var z := z0
		while z <= z1 + 0.1:
			_place(host, rng, VIL + piece + ".gltf", Vector3(x, y, z), rng.randi_range(0, 3) * 90.0, 1.0)
			z += 2.0
		x += 2.0

# Tapete de CAMPO (grama/trevo/flores/arbustos) num anel — dá "chão de verdade" ao terreno
# em volta de um cenário, no lugar do disco de cor chapada. Sempre fora do círculo de combate.
func _meadow(host: Node3D, rng: RandomNumberGenerator, r_in: float, r_out: float, n: int) -> void:
	var pool := ["Grass_Common_Short", "Grass_Common_Tall", "Grass_Wispy_Short", "Grass_Wispy_Tall", "Clover_1", "Clover_2", "Bush_Common", "Fern_1", "Flower_3_Group", "Flower_4_Group", "Flower_3_Single", "Flower_4_Single"]
	for i in n:
		var g: String = pool[rng.randi() % pool.size()]
		_place(host, rng, NAT + g + ".gltf", _scatter(rng, r_in, r_out), rng.randf_range(0, 360), rng.randf_range(0.7, 1.4))

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

# ── helpers extras (props + partículas) — sugestões do Fable ─────────────────────
func _box(host: Node3D, size: Vector3, pos: Vector3, color: Color, rot := 0.0) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new(); bm.size = size
	mi.mesh = bm
	var m := StandardMaterial3D.new(); m.albedo_color = color; m.roughness = 0.95
	mi.material_override = m
	host.add_child(mi); mi.position = pos; mi.rotation_degrees = Vector3(0, rot, 0)

func _billboard_mat(emit: Color, energy: float) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.vertex_color_use_as_albedo = true
	m.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	if energy > 0.0:
		m.emission_enabled = true; m.emission = emit; m.emission_energy_multiplier = energy
	return m

# vagalumes/poeira luminosa flutuando numa esfera (noite).
func _fireflies(host: Node3D, count: int, radius: float, color: Color) -> void:
	var p := GPUParticles3D.new()
	p.amount = count; p.lifetime = 4.0; p.preprocess = 2.5
	var m := ParticleProcessMaterial.new()
	m.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_SPHERE
	m.emission_sphere_radius = radius
	m.direction = Vector3.UP; m.spread = 180.0
	m.initial_velocity_min = 0.08; m.initial_velocity_max = 0.3
	m.gravity = Vector3.ZERO
	m.scale_min = 0.6; m.scale_max = 1.4
	var g := Gradient.new()
	g.set_color(0, Color(color, 0.0)); g.add_point(0.5, color); g.set_color(2, Color(color, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g; m.color_ramp = gt
	p.process_material = m
	var q := QuadMesh.new(); q.size = Vector2(0.06, 0.06); q.material = _billboard_mat(color, 2.5)
	p.draw_pass_1 = q
	host.add_child(p); p.position = Vector3(0, radius * 0.4, 0); p.emitting = true

# fumaça cinza subindo (chaminé).
func _smoke(host: Node3D, pos: Vector3) -> void:
	var p := GPUParticles3D.new()
	p.amount = 10; p.lifetime = 3.0; p.preprocess = 1.5
	var m := ParticleProcessMaterial.new()
	m.direction = Vector3.UP; m.spread = 9.0
	m.initial_velocity_min = 0.4; m.initial_velocity_max = 0.8
	m.gravity = Vector3(0, 0.25, 0)
	m.scale_min = 1.5; m.scale_max = 3.5
	var g := Gradient.new()
	g.set_color(0, Color(0.55, 0.55, 0.55, 0.0)); g.add_point(0.2, Color(0.55, 0.55, 0.55, 0.32)); g.set_color(2, Color(0.55, 0.55, 0.55, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g; m.color_ramp = gt
	p.process_material = m
	var q := QuadMesh.new(); q.size = Vector2(0.5, 0.5); q.material = _billboard_mat(Color.BLACK, 0.0)
	p.draw_pass_1 = q
	host.add_child(p); p.position = pos; p.emitting = true

# névoa baixa branca deslizando sobre o rio.
func _river_mist(host: Node3D, river_x: float) -> void:
	var p := GPUParticles3D.new()
	p.amount = 14; p.lifetime = 5.0; p.preprocess = 3.0
	var m := ParticleProcessMaterial.new()
	m.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	m.emission_box_extents = Vector3(4.0, 0.15, 36.0)
	m.direction = Vector3(0, 0, 1); m.spread = 6.0
	m.initial_velocity_min = 0.3; m.initial_velocity_max = 0.7
	m.gravity = Vector3.ZERO
	m.scale_min = 3.0; m.scale_max = 6.0
	var g := Gradient.new()
	g.set_color(0, Color(0.9, 0.93, 0.95, 0.0)); g.add_point(0.3, Color(0.9, 0.93, 0.95, 0.1)); g.set_color(2, Color(0.9, 0.93, 0.95, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g; m.color_ramp = gt
	p.process_material = m
	var q := QuadMesh.new(); q.size = Vector2(1.0, 1.0); q.material = _billboard_mat(Color.BLACK, 0.0)
	p.draw_pass_1 = q
	host.add_child(p); p.position = Vector3(river_x, 0.35, 0); p.emitting = true

# pilha de caixas (Prop_Crate).
func _crates(host: Node3D, rng: RandomNumberGenerator, pos: Vector3) -> void:
	_place(host, rng, VIL + "Prop_Crate.gltf", pos, rng.randf_range(0, 25), 1.0)
	_place(host, rng, VIL + "Prop_Crate.gltf", pos + Vector3(1.05, 0, -0.2), rng.randf_range(0, 25), 1.0)
	_place(host, rng, VIL + "Prop_Crate.gltf", pos + Vector3(0.2, 1.05, 0.1), rng.randf_range(0, 25), 0.9)

# poço de pedra com telhadinho.
func _well(host: Node3D, pos: Vector3) -> void:
	var ring := MeshInstance3D.new()
	var cm := CylinderMesh.new(); cm.top_radius = 0.95; cm.bottom_radius = 0.95; cm.height = 0.9
	ring.mesh = cm
	var sm := StandardMaterial3D.new(); sm.albedo_color = Color(0.5, 0.47, 0.43); sm.roughness = 1.0
	ring.material_override = sm
	host.add_child(ring); ring.position = pos + Vector3(0, 0.45, 0)
	var hole := MeshInstance3D.new()
	var hc := CylinderMesh.new(); hc.top_radius = 0.62; hc.bottom_radius = 0.62; hc.height = 0.1
	hole.mesh = hc
	var hm := StandardMaterial3D.new(); hm.albedo_color = Color(0.04, 0.04, 0.06); hm.roughness = 1.0
	hole.material_override = hm
	host.add_child(hole); hole.position = pos + Vector3(0, 0.92, 0)
	_box(host, Vector3(0.12, 1.9, 0.12), pos + Vector3(-0.75, 0.95, 0), Color(0.3, 0.2, 0.12))
	_box(host, Vector3(0.12, 1.9, 0.12), pos + Vector3(0.75, 0.95, 0), Color(0.3, 0.2, 0.12))
	_box(host, Vector3(2.0, 0.14, 1.1), pos + Vector3(0, 2.0, 0), Color(0.42, 0.22, 0.12))

# boneco de treino (poste + travessão + cabeça + corpo de palha).
func _dummy(host: Node3D, pos: Vector3) -> void:
	_box(host, Vector3(0.12, 1.6, 0.12), pos + Vector3(0, 0.8, 0), Color(0.35, 0.22, 0.12))
	_box(host, Vector3(1.0, 0.1, 0.1), pos + Vector3(0, 1.3, 0), Color(0.35, 0.22, 0.12))
	_box(host, Vector3(0.42, 0.7, 0.32), pos + Vector3(0, 1.05, 0), Color(0.72, 0.6, 0.34))
	var head := MeshInstance3D.new()
	var hs := SphereMesh.new(); hs.radius = 0.18; hs.height = 0.36
	head.mesh = hs
	var hm := StandardMaterial3D.new(); hm.albedo_color = Color(0.72, 0.6, 0.34); hm.roughness = 1.0
	head.material_override = hm
	host.add_child(head); head.position = pos + Vector3(0, 1.6, 0)

# bandeira tremulando num mastro (topo de torre).
func _flag(host: Node3D, pos: Vector3, color: Color) -> void:
	_box(host, Vector3(0.05, 1.0, 0.05), pos + Vector3(0, 0.5, 0), Color(0.2, 0.15, 0.1))
	var mi := MeshInstance3D.new()
	var q := QuadMesh.new(); q.size = Vector2(0.7, 0.4)
	mi.mesh = q
	var m := StandardMaterial3D.new(); m.albedo_color = color; m.roughness = 0.9
	m.cull_mode = BaseMaterial3D.CULL_DISABLED
	mi.material_override = m
	host.add_child(mi); mi.position = pos + Vector3(0.37, 0.82, 0)

# plateia fake: esferas dessaturadas em volta (topo do anel da arena).
func _crowd(host: Node3D, rng: RandomNumberGenerator, r: float, y: float, n: int) -> void:
	for i in n:
		var a := TAU * i / float(n)
		var mi := MeshInstance3D.new()
		var s := SphereMesh.new(); s.radius = 0.18; s.height = 0.36
		mi.mesh = s
		var m := StandardMaterial3D.new()
		m.albedo_color = Color.from_hsv(rng.randf(), 0.3, rng.randf_range(0.4, 0.7))
		m.roughness = 1.0
		mi.material_override = m
		host.add_child(mi); mi.position = Vector3(cos(a) * r, y, sin(a) * r)

# ════════════════════════════════════════════════════════════════════════════════
# [MAPA_TORRE] Cenário "TORRE AMALDIÇOADA" — evoca a capa (Crown of Aravok) no gráfico
# do jogo. Clareira sombria estilo o bosque (mining), com uma torre gótica em chamas à
# DIREITA (+X = lado do inimigo) e o chão coberto de escombros de batalha. Tudo procedural
# + assets que já existem. Desenho: docs/PLANO_MAPA_TORRE.md
# ════════════════════════════════════════════════════════════════════════════════

# ── iluminação TORRE AMALDIÇOADA — tempestade sombria + brasa da torre em chamas ──
# Céu de tempestade quase preto, uma fresta de luz FRIA rompendo as nuvens (key light),
# névoa de fumaça quente. O calor laranja vem do FOGO da torre (OmniLight em _tower_fire),
# não do sol — exatamente como na capa.
func cursed_tower_lighting(host: Node3D) -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.03, 0.03, 0.05)        # tempestade quase preta no alto
	sky_mat.sky_horizon_color = Color(0.26, 0.18, 0.14)    # horizonte com brasa/fumaça quente
	sky_mat.ground_horizon_color = Color(0.13, 0.10, 0.10)
	sky_mat.ground_bottom_color = Color(0.03, 0.03, 0.04)
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.35
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.22, 0.17, 0.16)          # névoa de fumaça quente-cinza
	env.fog_density = 0.011
	env.glow_enabled = true
	env.glow_intensity = 0.4
	env.glow_bloom = 0.1
	grimdark_grade(env)
	var we := WorldEnvironment.new()
	we.environment = env
	host.add_child(we)
	# "fresta de luz" fria rompendo as nuvens (key light, como na capa) — vem de cima/trás
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-50, -125, 0)
	key.light_color = Color(0.68, 0.74, 0.92)
	key.light_energy = 0.75
	key.shadow_enabled = true
	host.add_child(key)

# ── cenário: TORRE AMALDIÇOADA ──────────────────────────────────────────────────
# Centro LIVRE p/ os lutadores; o INIMIGO entra pelo +X (onde fica a torre). Escombros
# adensam perto da torre. Mata MORTA só no arco do fundo/esquerda (deixa +X p/ a torre e
# +Z aberto p/ a câmera).
func cursed_tower(host: Node3D, rng: RandomNumberGenerator, combat_r: float) -> void:
	var TOWER := Vector3(18.0, 0, 2.0)              # fortaleza à DIREITA (+X), na linha da estrada (z≈2) → a estrada leva até o portão
	_ground(host, Color(0.15, 0.13, 0.11), 44.0)    # solo de terra queimada/cinza
	# colisão do chão (ragdoll da batalha não atravessa o piso)
	var fb := StaticBody3D.new()
	var fc := CollisionShape3D.new()
	fc.shape = WorldBoundaryShape3D.new()
	fb.add_child(fc)
	host.add_child(fb)
	# ESTRADA de pedra LARGA no eixo X, CENTRADA em z≈2 → cobre o centro da batalha (z=0) E o duelo
	# do menu (z=4), pra a briga ficar NO MEIO da estrada, e segue reto até o portão. [MAPA_TORRE]
	_cobble_path(host, rng, 28.0, 5.5, 2.0)
	# MANCHAS escuras de queimado/sangue no chão (fora do combate)
	for i in 7:
		_flat(host, Color(0.06, 0.05, 0.05), _scatter(rng, combat_r + 1.0, 22.0) + Vector3(0, 0.06, 0), Vector2(rng.randf_range(2.0, 4.0), rng.randf_range(2.0, 4.0)))
	# MATA MORTA nos FLANCOS (lados +Z e -Z), deixando LIVRE o corredor da estrada (câmera -X → fortaleza +X)
	var dead := ["DeadTree_1", "DeadTree_2", "DeadTree_3"]
	var mix := ["DeadTree_1", "DeadTree_2", "DeadTree_3", "Pine_1", "Pine_2", "Pine_3"]
	_tree_arc(host, rng, dead, 12.0, 17.0, 9, deg_to_rad(40), deg_to_rad(140), 0.9, 1.5)    # flanco +Z
	_tree_arc(host, rng, dead, 12.0, 17.0, 9, deg_to_rad(220), deg_to_rad(320), 0.9, 1.5)   # flanco -Z
	_tree_arc(host, rng, mix, 19.0, 27.0, 16, deg_to_rad(28), deg_to_rad(152), 1.1, 1.9)
	_tree_arc(host, rng, mix, 19.0, 27.0, 16, deg_to_rad(208), deg_to_rad(332), 1.1, 1.9)
	_tree_arc(host, rng, mix, 29.0, 37.0, 24, deg_to_rad(18), deg_to_rad(162), 1.3, 2.2)
	_tree_arc(host, rng, mix, 29.0, 37.0, 24, deg_to_rad(198), deg_to_rad(342), 1.3, 2.2)
	# A TORRE amaldiçoada em chamas (à direita)
	_dark_tower(host, rng, TOWER)
	# ESCOMBROS de batalha — viés p/ a direita (lado +X, "dos inimigos")
	for i in 18:
		var pos := Vector3(rng.randf_range(-6.0, 18.0), 0, rng.randf_range(-13.0, 13.0))
		if Vector2(pos.x, pos.z).length() < combat_r + 1.0:
			continue
		match rng.randi() % 5:
			0, 1: _planted_sword(host, pos, rng)
			2:    _planted_spear(host, pos, rng)
			3:    _war_shield(host, pos, rng)
			_:    _fallen_soldier(host, pos, rng)
	# SOLDADOS TOMBADOS em volta do campo (players mortos com armadura) — anel em torno da briga
	for i in 10:
		var sa := lerpf(deg_to_rad(15), deg_to_rad(345), float(i) / 9.0) + rng.randf_range(-0.12, 0.12)
		var sr := rng.randf_range(combat_r + 0.6, combat_r + 5.0)
		_fallen_soldier(host, Vector3(cos(sa) * sr, 0, sin(sa) * sr), rng)
	# FERA MORTA (como o bicho no canto da capa) — perto da fortaleza
	_dead_beast(host, Vector3(11.0, 0, 5.5), rng, 1.0)
	_dead_beast(host, Vector3(14.5, 0, -3.5), rng, 0.7)
	# BRASEIROS esparsos (fogo de acampamento/batalha) iluminam os lutadores
	for i in 3:
		var a := lerpf(deg_to_rad(120), deg_to_rad(300), float(i) / 2.0)
		_brazier(host, Vector3(cos(a) * (combat_r + 1.4), 0, sin(a) * (combat_r + 1.4)))
	# grama RALA/morta + arbustos secos (campo arrasado, não mata viva)
	for i in 60:
		var g: String = ["Grass_Wispy_Short", "Grass_Wispy_Tall", "Fern_1", "Bush_Common"][i % 4]
		_place(host, rng, NAT + g + ".gltf", _scatter(rng, combat_r + 0.8, 36.0), rng.randf_range(0, 360), rng.randf_range(0.5, 1.0))
	# pedras/escombro de pedra espalhado
	for i in 30:
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), _scatter(rng, combat_r + 2.0, 34.0), rng.randf_range(0, 360), rng.randf_range(0.5, 1.1))
	# BRASAS/cinzas subindo da torre em chamas (vende o incêndio)
	_embers(host, TOWER + Vector3(0, 18.0, 0), 56)

# Anel de árvores num ARCO (a0..a1 em rad) — como _tree_ring mas só num setor, p/ deixar
# lados abertos (a torre em +X e a câmera em +Z).
func _tree_arc(host: Node3D, rng: RandomNumberGenerator, pool: Array, r0: float, r1: float, n: int, a0: float, a1: float, s0: float, s1: float) -> void:
	for i in n:
		var a := lerpf(a0, a1, rng.randf())
		var r := rng.randf_range(r0, r1)
		var tree: String = pool[rng.randi() % pool.size()]
		_place(host, rng, NAT + tree + ".gltf", Vector3(cos(a) * r, 0, sin(a) * r), rng.randf_range(0, 360), rng.randf_range(s0, s1))

# Caixa com rotação 3D completa (euler) + emissão opcional — base dos escombros/torre.
func _box3(host: Node3D, size: Vector3, pos: Vector3, color: Color, rot: Vector3, rough := 0.9, metal := 0.0, emit = null, emit_e := 0.0) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new(); bm.size = size
	mi.mesh = bm
	var m := StandardMaterial3D.new()
	m.albedo_color = color; m.roughness = rough; m.metallic = metal
	if emit != null:
		m.emission_enabled = true; m.emission = emit; m.emission_energy_multiplier = emit_e
	mi.material_override = m
	host.add_child(mi)
	mi.position = pos
	mi.rotation_degrees = rot

# Pivô (Node3D) p/ agrupar peças que precisam girar JUNTAS (arma fincada, corpo tombado).
func _pivot(host: Node3D, pos: Vector3, rot: Vector3) -> Node3D:
	var n := Node3D.new()
	host.add_child(n)
	n.position = pos
	n.rotation_degrees = rot
	return n

# Torre gótica AMALDIÇOADA em ruína, queimando no topo (a silhueta da capa). Anel OCTOGONAL
# de segmentos de pedra (mais "redondo" que um cubo) afinando pra cima, janelas em arco com
# brasa, topo quebrado, pináculos tortos, contrafortes + entulho na base, fogo + fumaça no
# topo. Sem colisão (decorativa, longe do combate). [MAPA_TORRE]
func _dark_tower(host: Node3D, rng: RandomNumberGenerator, base: Vector3) -> void:
	var STONE := Color(0.085, 0.08, 0.092)              # pedra quase preta (silhueta)
	var sides := 8                                       # octógono → silhueta arredondada (não cubo)
	var levels := 10                                     # TORRE ALTA/imponente
	var seg_h := 2.6
	var r0 := 4.3
	for lv in levels:
		var t := float(lv) / float(levels)
		var r := r0 * (1.0 - t * 0.5)                    # afina pra cima (gótico)
		var y := lv * seg_h + seg_h * 0.5
		var bw := 2.0 * r * sin(PI / float(sides)) * 1.14   # largura do bloco p/ fechar o anel
		var miss := (0.42 if lv >= levels - 2 else 0.0)  # topo QUEBRADO (blocos faltando = ruína)
		for k in sides:
			if rng.randf() < miss:
				continue
			var a := TAU * k / float(sides) + t * 0.15    # giro leve por nível (juntas desencontradas)
			var pos := base + Vector3(cos(a) * r, y + rng.randf_range(-0.05, 0.05), sin(a) * r)
			if (lv == 2 or lv == 5) and k % 2 == 0:       # JANELAS em arco (brasa por dentro)
				_arch_window(host, pos, base, bw, seg_h)
			else:
				_wall_seg(host, Vector3(bw, seg_h + 0.05, 0.7), pos, base, STONE.lightened(t * 0.05))
	var topy := levels * seg_h
	var topr := r0 * 0.5
	# PINÁCULOS quebrados saindo do topo (silhueta gótica irregular)
	for sp in 5:
		var sa := TAU * sp / 5.0 + rng.randf_range(-0.2, 0.2)
		var sr := topr * rng.randf_range(0.2, 0.9)
		_box3(host, Vector3(0.4, rng.randf_range(2.0, 4.0), 0.4), base + Vector3(cos(sa) * sr, topy + 1.0, sin(sa) * sr), STONE.lightened(0.06), Vector3(rng.randf_range(-12, 12), rng.randf_range(0, 360), rng.randf_range(-12, 12)), 1.0)
	# CONTRAFORTES (buttresses) inclinados na base — peso gótico
	for b in 4:
		var ba := TAU * b / 4.0 + 0.39
		_box3(host, Vector3(0.7, seg_h * 2.4, 1.5), base + Vector3(cos(ba) * (r0 * 0.95), seg_h * 1.0, sin(ba) * (r0 * 0.95)), STONE, Vector3(14, rad_to_deg(ba), 0), 1.0)
	# ENTULHO na base (a torre desmoronando): rochas + lascas de pedra
	for i in 14:
		var ra := rng.randf_range(0, TAU)
		var rr := r0 + rng.randf_range(0.2, 2.8)
		_place(host, rng, NAT + "Rock_Medium_%d.gltf" % (1 + i % 3), base + Vector3(cos(ra) * rr, 0, sin(ra) * rr), rng.randf_range(0, 360), rng.randf_range(0.6, 1.4))
	for i in 8:
		var ca := rng.randf_range(0, TAU)
		var cr := r0 + rng.randf_range(0.0, 2.0)
		_box3(host, Vector3(rng.randf_range(0.5, 1.1), rng.randf_range(0.4, 0.9), rng.randf_range(0.5, 1.1)), base + Vector3(cos(ca) * cr, 0.2, sin(ca) * cr), STONE.lightened(0.04), Vector3(rng.randf_range(-20, 20), rng.randf_range(0, 360), rng.randf_range(-20, 20)), 1.0)
	# FOGO no topo (a torre queima) + colunas de fumaça
	_tower_fire(host, base + Vector3(0, topy + 0.2, 0))
	_smoke(host, base + Vector3(rng.randf_range(-0.6, 0.6), topy + 2.0, 0))
	_smoke(host, base + Vector3(rng.randf_range(-0.6, 0.6), topy + 2.8, 0.4))
	# MURALHA de castelo PRETA de frente pra estrada (com portão aceso + ameias) — imponência de fortaleza
	_castle_front(host, base, r0)

# Muralha de CASTELO preta de frente pra estrada (com ameias + PORTÃO aceso) — dá imponência de
# fortaleza à torre. base = centro da torre; faces viradas pro -X (a estrada/câmera). [MAPA_TORRE]
func _castle_front(host: Node3D, base: Vector3, r0: float) -> void:
	var STONE := Color(0.075, 0.07, 0.085)
	var wall_x := base.x - r0 - 0.4       # face frontal (lado da estrada)
	var wall_h := 9.5
	var span := 8.5                        # meia-largura da muralha (em Z)
	var gate := 1.9                        # meio-vão do portão
	# painéis esquerdo/direito (caixões de pedra), deixando o vão do portão no meio
	for sidez in [-1.0, 1.0]:
		var z0: float = base.z + sidez * gate
		var z1: float = base.z + sidez * span
		_box3(host, Vector3(1.7, wall_h, absf(z1 - z0)), Vector3(wall_x, wall_h * 0.5, (z0 + z1) * 0.5), STONE, Vector3.ZERO, 1.0)
	# torres-pilar do portão (mais altas, enquadram o vão)
	for sidez in [-1.0, 1.0]:
		_box3(host, Vector3(2.0, wall_h + 2.2, 1.1), Vector3(wall_x, (wall_h + 2.2) * 0.5, base.z + sidez * gate), STONE.lightened(0.03), Vector3.ZERO, 1.0)
	# verga (arco) do portão + BRASA no vão (a fortaleza arde por dentro)
	_box3(host, Vector3(1.8, wall_h * 0.3, gate * 2.0), Vector3(wall_x, wall_h * 0.82, base.z), STONE, Vector3.ZERO, 1.0)
	_box3(host, Vector3(0.4, wall_h * 0.55, gate * 1.5), Vector3(wall_x + 0.25, wall_h * 0.34, base.z), Color(1.0, 0.42, 0.12), Vector3.ZERO, 0.6, 0.0, Color(1.0, 0.38, 0.06), 3.0)
	var gl := OmniLight3D.new()           # luz quente saindo do portão
	gl.light_color = Color(1.0, 0.5, 0.2); gl.light_energy = 3.2; gl.omni_range = 11.0
	host.add_child(gl); gl.position = Vector3(wall_x + 1.2, 3.2, base.z)
	# AMEIAS (merlons) no topo da muralha — silhueta de castelo
	var z := base.z - span
	while z <= base.z + span + 0.01:
		if absf(z - base.z) > gate * 0.8:    # pula o vão do portão
			_box3(host, Vector3(0.9, 1.1, 0.8), Vector3(wall_x, wall_h + 0.55, z), STONE.lightened(0.05), Vector3.ZERO, 1.0)
		z += 1.5

# Segmento de muralha (caixa) com a FACE plana virada pra FORA do centro (look_at) — monta o
# anel octogonal da torre. [MAPA_TORRE]
func _wall_seg(host: Node3D, size: Vector3, pos: Vector3, center: Vector3, color: Color) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new(); bm.size = size
	mi.mesh = bm
	var m := StandardMaterial3D.new(); m.albedo_color = color; m.roughness = 1.0
	mi.material_override = m
	host.add_child(mi)
	mi.position = pos
	mi.look_at(Vector3(center.x, pos.y, center.z), Vector3.UP)   # -Z (profundidade) aponta pro centro

# Janela em arco com BRASA por dentro (a torre queima) — vão flanqueado pelos segmentos vizinhos.
func _arch_window(host: Node3D, pos: Vector3, center: Vector3, bw: float, seg_h: float) -> void:
	var STONE := Color(0.085, 0.08, 0.092)
	var pane := MeshInstance3D.new()                    # brasa por dentro (pane menor que o vão)
	var bm := BoxMesh.new(); bm.size = Vector3(bw * 0.5, seg_h * 0.6, 0.25)
	pane.mesh = bm
	var em := StandardMaterial3D.new()
	em.albedo_color = Color(1.0, 0.42, 0.12)
	em.emission_enabled = true
	em.emission = Color(1.0, 0.38, 0.06)
	em.emission_energy_multiplier = 3.5
	pane.material_override = em
	host.add_child(pane)
	pane.position = pos + Vector3(0, -seg_h * 0.1, 0)
	pane.look_at(Vector3(center.x, pane.position.y, center.z), Vector3.UP)
	_wall_seg(host, Vector3(bw, seg_h * 0.34, 0.72), pos + Vector3(0, seg_h * 0.33, 0), center, STONE)    # verga (arco)
	_wall_seg(host, Vector3(bw, seg_h * 0.24, 0.72), pos + Vector3(0, -seg_h * 0.42, 0), center, STONE)   # peitoril

# Coroa de FOGO no topo da torre: brasa emissiva grande + LUZ quente forte (o "glow" da
# capa que banha a cena de laranja) + chama de partícula + flicker.
func _tower_fire(host: Node3D, pos: Vector3) -> void:
	var coal := MeshInstance3D.new()
	var sm := SphereMesh.new(); sm.radius = 1.4; sm.height = 2.2
	coal.mesh = sm
	var cmat := StandardMaterial3D.new()
	cmat.albedo_color = Color(1.0, 0.5, 0.15)
	cmat.emission_enabled = true
	cmat.emission = Color(1.0, 0.45, 0.1)
	cmat.emission_energy_multiplier = 4.0
	coal.material_override = cmat
	host.add_child(coal); coal.position = pos
	# LUZ quente forte (banha os lutadores + o chão de laranja, como o incêndio da capa)
	var light := OmniLight3D.new()
	light.light_color = Color(1.0, 0.55, 0.22)
	light.light_energy = 6.0
	light.omni_range = 34.0
	host.add_child(light); light.position = pos + Vector3(0, 1.0, 0)
	# CHAMA de partícula (grande)
	var p := GPUParticles3D.new()
	p.amount = 40
	p.lifetime = 1.4
	var m := ParticleProcessMaterial.new()
	m.direction = Vector3.UP
	m.spread = 22.0
	m.initial_velocity_min = 1.4
	m.initial_velocity_max = 3.2
	m.gravity = Vector3(0, 2.2, 0)
	m.scale_min = 1.6
	m.scale_max = 3.4
	var g := Gradient.new()
	g.set_color(0, Color(1.0, 0.8, 0.25, 0.9))
	g.add_point(0.5, Color(1.0, 0.35, 0.05, 0.6))
	g.set_color(2, Color(0.25, 0.04, 0.02, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g
	m.color_ramp = gt
	p.process_material = m
	var q := QuadMesh.new(); q.size = Vector2(1.2, 1.2)
	q.material = _billboard_mat(Color(1.0, 0.5, 0.1), 2.2)
	p.draw_pass_1 = q
	host.add_child(p); p.position = pos + Vector3(0, 0.6, 0)
	var tw := host.create_tween().set_loops()
	tw.tween_property(light, "light_energy", 5.0, 0.15)
	tw.tween_property(light, "light_energy", 6.8, 0.19)
	tw.tween_property(light, "light_energy", 5.6, 0.12)

# BRASAS/cinzas alaranjadas subindo (da torre em chamas) — vende o incêndio.
func _embers(host: Node3D, pos: Vector3, count: int) -> void:
	var p := GPUParticles3D.new()
	p.amount = count; p.lifetime = 5.0; p.preprocess = 3.0
	var m := ParticleProcessMaterial.new()
	m.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	m.emission_box_extents = Vector3(4.0, 3.0, 4.0)
	m.direction = Vector3.UP; m.spread = 30.0
	m.initial_velocity_min = 0.6; m.initial_velocity_max = 1.6
	m.gravity = Vector3(0, 0.5, 0)
	m.scale_min = 0.4; m.scale_max = 1.0
	var col := Color(1.0, 0.55, 0.18)
	var g := Gradient.new()
	g.set_color(0, Color(col, 0.0)); g.add_point(0.3, col); g.set_color(2, Color(col, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g; m.color_ramp = gt
	p.process_material = m
	var q := QuadMesh.new(); q.size = Vector2(0.08, 0.08); q.material = _billboard_mat(col, 2.5)
	p.draw_pass_1 = q
	host.add_child(p); p.position = pos; p.emitting = true

# Espada FINCADA no chão (lâmina pra cima, inclinada) — escombro de batalha.
func _planted_sword(host: Node3D, pos: Vector3, rng: RandomNumberGenerator) -> void:
	var piv := _pivot(host, pos, Vector3(rng.randf_range(-22, 22), rng.randf_range(0, 360), rng.randf_range(-12, 12)))
	var steel := Color(0.30, 0.31, 0.34)
	_box3(piv, Vector3(0.10, 1.5, 0.03), Vector3(0, 0.5, 0), steel, Vector3.ZERO, 0.4, 0.6)          # lâmina
	_box3(piv, Vector3(0.42, 0.08, 0.08), Vector3(0, 1.12, 0), Color(0.20, 0.18, 0.14), Vector3.ZERO, 0.7)  # guarda
	_box3(piv, Vector3(0.08, 0.30, 0.08), Vector3(0, 1.32, 0), Color(0.16, 0.11, 0.07), Vector3.ZERO, 0.9)  # punho

# Lança/estaca FINCADA bem inclinada.
func _planted_spear(host: Node3D, pos: Vector3, rng: RandomNumberGenerator) -> void:
	var piv := _pivot(host, pos, Vector3(rng.randf_range(28, 58), rng.randf_range(0, 360), 0))
	var L := rng.randf_range(2.0, 2.8)
	_box3(piv, Vector3(0.07, L, 0.07), Vector3(0, L * 0.4, 0), Color(0.22, 0.15, 0.09), Vector3.ZERO, 0.95)        # haste
	_box3(piv, Vector3(0.12, 0.4, 0.04), Vector3(0, L * 0.85, 0), Color(0.30, 0.31, 0.34), Vector3.ZERO, 0.4, 0.6) # ponta

# Escudo de guerra caído (disco com tinta esmaecida), quase deitado.
func _war_shield(host: Node3D, pos: Vector3, rng: RandomNumberGenerator) -> void:
	var piv := _pivot(host, pos, Vector3(rng.randf_range(62, 90), rng.randf_range(0, 360), rng.randf_range(-15, 15)))
	var faded: Color = [Color(0.42, 0.13, 0.11), Color(0.13, 0.19, 0.36), Color(0.46, 0.37, 0.13)][rng.randi() % 3]
	faded = faded.darkened(0.25)
	var mi := MeshInstance3D.new()
	var cm := CylinderMesh.new(); cm.top_radius = 0.55; cm.bottom_radius = 0.55; cm.height = 0.08
	mi.mesh = cm
	var m := StandardMaterial3D.new(); m.albedo_color = faded; m.roughness = 0.85
	mi.material_override = m
	piv.add_child(mi); mi.position = Vector3(0, 0.2, 0)
	_box3(piv, Vector3(0.18, 0.18, 0.18), Vector3(0, 0.26, 0), Color(0.28, 0.29, 0.32), Vector3.ZERO, 0.4, 0.6)   # umbo

# Soldado TOMBADO (impressão, não boneco rigado): torso + capacete + pernas/braço, deitado
# e desbotado. Evoca os caídos da capa sem custo de rig.
func _fallen_soldier(host: Node3D, pos: Vector3, rng: RandomNumberGenerator) -> void:
	var piv := _pivot(host, pos, Vector3(0, rng.randf_range(0, 360), 0))
	var cloth := Color(0.15, 0.14, 0.15)
	var armor := Color(0.26, 0.27, 0.31)
	_box3(piv, Vector3(0.58, 0.32, 0.98), Vector3(0, 0.19, 0), armor, Vector3.ZERO, 0.5, 0.55)                 # torso (armadura)
	_box3(piv, Vector3(0.50, 0.16, 0.70), Vector3(0, 0.36, 0.05), armor.lightened(0.06), Vector3.ZERO, 0.4, 0.6)  # placa do peito (brilho metálico)
	_box3(piv, Vector3(0.26, 0.20, 0.30), Vector3(0, 0.30, 0.50), armor, Vector3.ZERO, 0.45, 0.6)              # ombreira
	_box3(piv, Vector3(0.24, 0.24, 0.82), Vector3(-0.17, 0.13, 0.90), cloth, Vector3(0, rng.randf_range(-15, 15), 0), 0.9)  # perna
	_box3(piv, Vector3(0.24, 0.24, 0.72), Vector3(0.19, 0.13, 0.86), cloth, Vector3(0, rng.randf_range(-15, 15), 0), 0.9)   # perna
	_box3(piv, Vector3(0.20, 0.20, 0.62), Vector3(0.50, 0.13, -0.12), armor, Vector3(0, rng.randf_range(-30, 30), 0), 0.5, 0.55)  # braço jogado
	var head := MeshInstance3D.new()
	var hs := SphereMesh.new(); hs.radius = 0.21; hs.height = 0.42
	head.mesh = hs
	var hm := StandardMaterial3D.new(); hm.albedo_color = armor.lightened(0.04); hm.roughness = 0.4; hm.metallic = 0.6
	head.material_override = hm
	piv.add_child(head); head.position = Vector3(rng.randf_range(-0.1, 0.1), 0.21, -0.78)                      # elmo metálico
	if rng.randf() < 0.6:                                                                                       # arma largada ao lado
		_box3(piv, Vector3(0.07, 0.03, 1.0), Vector3(rng.randf_range(0.45, 0.7), 0.04, rng.randf_range(-0.2, 0.4)), Color(0.30, 0.31, 0.34), Vector3(0, rng.randf_range(-50, 50), 0), 0.4, 0.6)

# FERA MORTA — massa escura alongada + espigões nas costas + cabeça/cauda caídas + poça de
# sangue. Evoca o monstro abatido no canto da capa. `s` = escala.
func _dead_beast(host: Node3D, pos: Vector3, rng: RandomNumberGenerator, s: float) -> void:
	var piv := _pivot(host, pos, Vector3(0, rng.randf_range(0, 360), 0))
	piv.scale = Vector3(s, s, s)
	var hide := Color(0.10, 0.09, 0.10)
	var body := MeshInstance3D.new()
	var bs := SphereMesh.new(); bs.radius = 1.0; bs.height = 1.4
	body.mesh = bs
	var bm := StandardMaterial3D.new(); bm.albedo_color = hide; bm.roughness = 0.95
	body.material_override = bm
	piv.add_child(body); body.position = Vector3(0, 0.45, 0); body.scale = Vector3(1.0, 0.55, 2.0)   # corpo achatado/alongado
	for i in 6:                                                                                        # espigões nas costas
		var z := lerpf(-1.6, 1.4, float(i) / 5.0)
		_box3(piv, Vector3(0.14, rng.randf_range(0.5, 1.0), 0.14), Vector3(rng.randf_range(-0.1, 0.1), 0.7, z), hide.lightened(0.05), Vector3(rng.randf_range(-25, 25), 0, rng.randf_range(-15, 15)), 0.95)
	_box3(piv, Vector3(0.5, 0.4, 0.8), Vector3(0, 0.25, 2.0), hide, Vector3(20, 0, 0), 0.95)           # cabeça caída
	_box3(piv, Vector3(0.25, 0.25, 1.4), Vector3(0, 0.3, -2.0), hide, Vector3(-10, rng.randf_range(-20, 20), 0), 0.95)  # cauda
	_flat(piv, Color(0.05, 0.02, 0.02), Vector3(0, 0.03, 0.5), Vector2(2.6, 4.0))                      # poça de sangue
