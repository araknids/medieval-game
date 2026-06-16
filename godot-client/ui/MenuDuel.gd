extends Node3D
# ── Dois guerreiros duelando no fundo do menu (castelo) — pura decoração. [MENU_DUEL] ──
# Reusa o rig Quaternius (Male_rigged) + as peças Ranger (paper-doll) + a espada procedural
# (Weapons) + as anims de espada do BattleReplay, e roda um loop simples: um ataca, o outro
# reage, ambos voltam pro idle. Defensivo: se qualquer asset faltar, só não aparece (o menu
# continua funcionando). Personagem 3D: docs/PLANO_GODOT_3D.md

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const CHAR_FEMALE := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Female_Rigged.tscn")  # [OUTFITS_FEMALE]
const OutfitsLib := preload("res://Outfits.gd")   # sets novos (tema/gênero/cor/variante) — sorteados a cada entrada
const Weapons := preload("res://Weapons.gd")
const UAL2_PATH := "res://addons/quaternius_ik_rigged/UAL2_Standard.glb"
const LIB := "UAL1_Standard/"
const LIB2 := "UAL2_Standard/"
const IDLE := LIB + "Sword_Idle"
const HURTS := [LIB + "Hit_Chest", LIB + "Hit_Head"]   # reação variada (peito/cabeça)
const ROLL := LIB + "Roll"                              # esquiva ocasional (in-place, sem sangue)
# golpes variados: 3 da UAL2 (A/B/combo) + o Sword_Attack da UAL1
const ATTACKS := [LIB2 + "Sword_Regular_A", LIB2 + "Sword_Regular_B", LIB2 + "Sword_Regular_Combo", LIB + "Sword_Attack"]
const SHOOT := LIB + "Spell_Simple_Shoot"   # arco/ranged: anim de TIRO (não golpe de espada) [MENU_DUEL]
const BLEND := 0.12
# Arma aleatória dos lutadores — só MELEE (as anims do duelo são de espada). [MENU_DUEL]
const MELEE_KINDS := ["sword", "greatsword", "axe", "spear", "mace"]
const WALK := LIB + "Walk"      # andar (reverso = recuar) no kiting do arqueiro [MENU_DUEL]
# Kiting (arco × melee): o arqueiro recua e, encurralado na borda, PULA pro outro lado.
const KITE_EDGE := 3.6          # |x| máx do arqueiro (campo mais largo → recua de verdade)
const KITE_RANGE := 1.45        # distância que o melee tenta fechar
const KITE_PREF := 1.95         # arqueiro recua enquanto o gap for menor que isto
const KITE_MELEE_SPEED := 1.7
const KITE_ARCHER_SPEED := 2.0  # > melee → o arqueiro mantém distância
const KITE_LAND := 3.0          # DOIS dodges: aterrissa BEM longe do melee (> alcance) → não dodgeia sem parar

# Peças Ranger por slot (mesmo set do PaperDollLive) + a cabeça-base (rosto).
const BASE_HEAD := "res://assets/base/Base_Male_Head.gltf"
const RANGER := [
	"res://assets/outfits/ranger/Male_Ranger_Body.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Legs.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Feet_Boots.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Arms.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Head_Hood.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Acc_Pauldron.gltf",
]

# Posições/porte no pátio (fáceis de ajustar). z+ = mais perto da câmera (castelo: cam em z=18).
const POS_L := Vector3(-1.2, 0.0, 4.0)
const POS_R := Vector3(1.2, 0.0, 4.0)
const SCALE := 1.2

var _fighters: Array = []   # [{node, anim}]
var _atk := 0
var _timer := 1.0
var _rng := RandomNumberGenerator.new()
var _gen := 0   # geração do setup() — aborta um setup antigo se um novo começar (relogin rápido)

func _ready() -> void:
	_rng.randomize()   # cada abertura = coreografia + armas aleatórias diferentes [MENU_DUEL]
	# luz quente de preenchimento (destaca o duelo em qualquer cenário, mesmo os noturnos)
	var key := OmniLight3D.new()
	key.light_color = Color(1.0, 0.86, 0.62)
	key.light_energy = 2.6
	key.omni_range = 13.0
	key.position = Vector3(0, 4.0, 7.5)
	add_child(key)
	# os lutadores são montados por setup() — o App chama no boot e a cada login/logout.

# (Re)monta os 2 lutadores conforme o login. LOGADO: esquerda = você (sua arma equipada real);
# senão aleatório. DIREITA: sempre um oponente aleatório. Idempotente (limpa antes de remontar).
func setup() -> void:
	_gen += 1
	var my := _gen
	_clear_fighters()
	var left := {}
	if Api.token != "":
		left = await _player_loadout()   # única coisa que varia com 1 set de roupa: a ARMA
		if my != _gen:                   # um setup mais novo começou → aborta este
			return
		if left.is_empty():
			left = {"kind": "sword", "rarity": 1}   # logado mas desarmado → espada padrão (ainda é "você")
	if left.is_empty():
		_spawn(POS_L, 90.0, _rand_kind(), _rng.randi_range(1, 5))    # deslogado → aleatório
	else:
		_spawn(POS_L, 90.0, str(left.get("kind", "sword")), int(left.get("rarity", 1)))
	_spawn(POS_R, -90.0, _rand_kind(), _rng.randi_range(1, 5))       # oponente sempre aleatório

# Lê /api/inventory e devolve {kind, rarity} da arma equipada (ou {} se falhar/desarmado).
func _player_loadout() -> Dictionary:
	var r = await Api.get_inventory()
	if not (r.get("ok") and r.get("json") is Array):
		return {}
	for it in r["json"]:
		if it is Dictionary and it.get("equipped") == true and str(it.get("type", "")) == "WEAPON":
			var kind := Weapons.new().weapon_kind(str(it.get("name", "")), str(it.get("weaponCategory", "")))
			return {"kind": kind, "rarity": int(it.get("rarity", 1))}
	return {}

func _rand_kind() -> String:
	return MELEE_KINDS[_rng.randi() % MELEE_KINDS.size()]

func _clear_fighters() -> void:
	for f in _fighters:
		var n = f.get("node")
		if is_instance_valid(n):
			n.queue_free()
	_fighters.clear()
	_atk = 0
	_timer = 1.0

# Look SORTEADO (tema/gênero/cor/variante) — varia a cada setup() (= a cada entrada no jogo). [OUTFITS]
func _rand_look() -> Dictionary:
	var themes: Array = OutfitsLib.THEME_ORDER
	return {
		"theme":  str(themes[_rng.randi() % themes.size()]),
		"gender": "female" if _rng.randi() % 2 == 1 else "male",
		"rarity": _rng.randi_range(1, 5),
		"seed":   "menu_%d" % _rng.randi(),   # varia a peça-variante (elmo/ombreira/peitoral)
	}

func _spawn(pos: Vector3, yaw_deg: float, weapon_kind: String, weapon_rarity: int) -> void:
	var look := _rand_look()
	var node := (CHAR_FEMALE if look["gender"] == "female" else CHAR).instantiate()
	if node == null:
		return
	add_child(node)
	node.position = pos
	node.rotation_degrees = Vector3(0, yaw_deg, 0)
	node.scale = Vector3.ONE * SCALE
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	# liga a lib de espadas (UAL2 — variações de golpe)
	if ap and not ap.has_animation_library("UAL2_Standard"):
		var lib2 = load(UAL2_PATH)
		if lib2 is AnimationLibrary:
			ap.add_animation_library("UAL2_Standard", lib2)
	# veste o SET SORTEADO (esconde o corpo base) + arma na mão (tipo + raridade)
	if skel:
		_dress(node, skel, look)
		Weapons.new().attach_weapon(node, weapon_kind, weapon_rarity)
	# estado do lutador: ranged (arco→kiting) + base_y/hopping/busy p/ o movimento [MENU_DUEL]
	var fighter := {"node": node, "anim": ap, "ranged": Weapons.new().is_bow_kind(weapon_kind),
		"base_y": pos.y, "hopping": false, "busy": false}
	# idle em loop; one-shot (ataque/tiro/hurt/roll) volta pro idle ao terminar e libera o "busy"
	if ap:
		var il := ap.get_animation(IDLE)
		if il:
			il.loop_mode = Animation.LOOP_LINEAR
		var wl := ap.get_animation(WALK)   # locomoção do kiting precisa repetir (senão "trava" a cada ciclo)
		if wl:
			wl.loop_mode = Animation.LOOP_LINEAR
		ap.animation_finished.connect(func(_a: StringName) -> void:
			fighter["busy"] = false
			if is_instance_valid(node) and not fighter.get("hopping", false):
				ap.play(IDLE, BLEND))
		ap.play(IDLE)
	_fighters.append(fighter)

func _dress(node: Node3D, skel: Skeleton3D, look := {}) -> void:
	var theme := str(look.get("theme", "ranger"))
	var gender := "female" if str(look.get("gender", "male")) == "female" else "male"
	var rarity := int(look.get("rarity", 1))
	var seed_name := str(look.get("seed", ""))
	var g := "Female" if gender == "female" else "Male"
	# esconde todas as malhas base (Superhero) ANTES de vestir
	var base: Array = []
	_collect_meshes(node, base)
	for m: MeshInstance3D in base:
		m.visible = false
	# rosto (gênero-aware)
	var head = load("res://assets/base/Base_%s_Head.gltf" % g)
	if head is PackedScene:
		_attach_outfit(head, skel)
	# set completo por tema/gênero/variante + recolor por raridade
	var dressed := {}
	for ty in OutfitsLib.ARMOR_SLOTS:
		var path := OutfitsLib.piece_path_theme(theme, ty, gender, seed_name)
		if path != "" and ResourceLoader.exists(path):
			var sc = load(path)
			if sc is PackedScene:
				_attach_outfit(sc, skel, theme, rarity)
				dressed[ty] = true
	# pele nua nos slots de CORPO sem peça (defensivo: evita buraco se uma peça faltar)
	var part_slot := {"Torso": "ARMOR", "Arms": "GLOVES", "Legs": "PANTS", "Feet": "BOOTS"}
	for part in part_slot:
		if not dressed.has(part_slot[part]):
			var p = load("res://assets/base/Base_%s_%s.gltf" % [g, part])
			if p is PackedScene:
				_attach_outfit(p, skel)

func _attach_outfit(scene: PackedScene, skel: Skeleton3D, theme := "", rarity := 0) -> void:
	var inst := scene.instantiate()
	var meshes: Array = []
	_collect_meshes(inst, meshes)
	for mi: MeshInstance3D in meshes:
		var skin := mi.skin
		mi.get_parent().remove_child(mi)
		skel.add_child(mi)
		mi.transform = Transform3D.IDENTITY
		mi.skin = skin
		mi.skeleton = NodePath("..")
		if theme != "" and rarity > 0:
			OutfitsLib.recolor_mesh(mi, theme, rarity)
	inst.queue_free()

func _collect_meshes(n: Node, out: Array) -> void:
	if n is MeshInstance3D:
		out.append(n)
	for c in n.get_children():
		_collect_meshes(c, out)

func _process(dt: float) -> void:
	if _fighters.size() < 2:
		return
	var r := _ranged_idx()              # >=0 → arco×melee (kiting); -1 → duelo melee normal
	if r >= 0:
		_kite_move(dt, r, 1 - r)        # movimento contínuo (persegue/recua) todo frame
	_timer -= dt
	if _timer <= 0.0:
		_timer = _rng.randf_range(0.65, 1.05)
		if r >= 0:
			_kite_beat(r, 1 - r)        # arqueiro atira (ou pula se foi alcançado)
		else:
			_atk = 1 - _atk
			_swing(_atk, 1 - _atk)

# Índice do arqueiro se for ARCO×MELEE (kiting); -1 se ambos iguais (duelo normal). [MENU_DUEL]
func _ranged_idx() -> int:
	if _fighters.size() < 2:
		return -1
	var a: bool = _fighters[0].get("ranged", false)
	var b: bool = _fighters[1].get("ranged", false)
	if a == b:
		return -1
	return 0 if a else 1

# Vira o lutador p/ +X (dir>=0) ou -X (dir<0). yaw 90 = encara +X; -90 = encara -X (igual ao spawn).
func _face(f: Dictionary, dir: float) -> void:
	var n: Node3D = f["node"]
	if is_instance_valid(n):
		n.rotation_degrees.y = 90.0 if dir >= 0.0 else -90.0

# Kiting contínuo (por frame): melee persegue, arqueiro recua ANDANDO; encurralado na borda → pula através.
func _kite_move(dt: float, r: int, m: int) -> void:
	var R: Dictionary = _fighters[r]
	var M: Dictionary = _fighters[m]
	var rn: Node3D = R["node"]
	var mn: Node3D = M["node"]
	if not (is_instance_valid(rn) and is_instance_valid(mn)):
		return
	if R.get("hopping", false):
		return                                  # no meio do pulo: ninguém anda
	var side := signf(rn.position.x - mn.position.x)   # +1 = arqueiro à direita do melee
	if side == 0.0:
		side = 1.0
	var gap := absf(rn.position.x - mn.position.x)
	# MELEE persegue até KITE_RANGE
	var desired_m := rn.position.x - side * KITE_RANGE
	mn.position.x = move_toward(mn.position.x, desired_m, KITE_MELEE_SPEED * dt)
	_face(M, side)
	var ap_m: AnimationPlayer = M["anim"]
	if ap_m and not M.get("busy", false):
		var want_m: String = WALK if absf(mn.position.x - desired_m) > 0.03 else IDLE
		if ap_m.current_animation != want_m:
			ap_m.play(want_m, BLEND)
	# ARQUEIRO encara o melee; pressionado, recua; na borda → pula através
	_face(R, -side)
	var ap_r: AnimationPlayer = R["anim"]
	if gap < KITE_PREF and not R.get("busy", false):
		var next_x := rn.position.x + side * KITE_ARCHER_SPEED * dt
		if absf(next_x) > KITE_EDGE:
			_hop_through(r, m)                  # encurralado → pula pro outro lado
			return
		rn.position.x = next_x
		if ap_r and ap_r.current_animation != WALK:
			ap_r.play(WALK, BLEND, -1.0)        # walk em REVERSO = andar pra trás
	elif ap_r and not R.get("busy", false) and ap_r.current_animation == WALK:
		ap_r.play(IDLE, BLEND)                  # parou de recuar

# Batida do kiting: se o melee ALCANÇOU, golpeia e o arqueiro pula pro outro lado; senão o arqueiro ATIRA.
func _kite_beat(r: int, m: int) -> void:
	var R: Dictionary = _fighters[r]
	var M: Dictionary = _fighters[m]
	var rn: Node3D = R["node"]
	var mn: Node3D = M["node"]
	if not (is_instance_valid(rn) and is_instance_valid(mn)) or R.get("hopping", false):
		return
	if absf(rn.position.x - mn.position.x) < KITE_RANGE + 0.3 and not M.get("busy", false):
		_melee_swing(m)        # o guerreiro encostou → golpe
		_hop_through(r, m)     # arqueiro rola/pula pro outro lado (esquiva)
	else:
		_kite_shoot(r, m)

func _kite_shoot(r: int, m: int) -> void:
	var R: Dictionary = _fighters[r]
	var M: Dictionary = _fighters[m]
	var rn: Node3D = R["node"]
	var mn: Node3D = M["node"]
	if not (is_instance_valid(rn) and is_instance_valid(mn)):
		return
	var ap_r: AnimationPlayer = R["anim"]
	if ap_r:
		var a := ap_r.get_animation(SHOOT)
		if a:
			a.loop_mode = Animation.LOOP_NONE
		R["busy"] = true
		ap_r.play(SHOOT, BLEND)
	_arrow(rn, mn)
	# melee reage (hurt + sangue) quando a flecha chega
	var ap_m: AnimationPlayer = M["anim"]
	var hitdir := Vector3(mn.position.x - rn.position.x, 0, 0)
	var target := mn
	get_tree().create_timer(0.24).timeout.connect(func() -> void:
		if not is_instance_valid(target):
			return
		if ap_m:
			var react: String = HURTS[_rng.randi() % HURTS.size()]
			var h := ap_m.get_animation(react)
			if h:
				h.loop_mode = Animation.LOOP_NONE
			M["busy"] = true
			ap_m.play(react, BLEND)
		_blood(target.global_position + Vector3(0, 1.15, 0), hitdir))

func _melee_swing(m: int) -> void:
	var M: Dictionary = _fighters[m]
	var ap: AnimationPlayer = M["anim"]
	if ap:
		var clip: String = ATTACKS[_rng.randi() % ATTACKS.size()]
		var a := ap.get_animation(clip)
		if a:
			a.loop_mode = Animation.LOOP_NONE
		M["busy"] = true
		ap.play(clip, BLEND)

# Arqueiro encurralado: ROLA/PULA através do melee e aterrissa do outro lado (vira o kiting). [MENU_DUEL]
func _hop_through(r: int, m: int) -> void:
	var R: Dictionary = _fighters[r]
	var M: Dictionary = _fighters[m]
	if R.get("hopping", false):
		return
	var rn: Node3D = R["node"]
	var mn: Node3D = M["node"]
	if not (is_instance_valid(rn) and is_instance_valid(mn)):
		return
	R["hopping"] = true
	var toward := signf(mn.position.x - rn.position.x)   # através do melee
	if toward == 0.0:
		toward = 1.0
	var land_x := clampf(mn.position.x + toward * KITE_LAND, -KITE_EDGE, KITE_EDGE)
	_face(R, toward)                                     # encara a direção do pulo
	var base_y: float = R.get("base_y", 0.0)
	var ap_r: AnimationPlayer = R["anim"]
	var roll: String = ROLL
	var dur := 0.55
	if ap_r:
		if not ap_r.has_animation(ROLL):
			roll = WALK
		var a: Animation = ap_r.get_animation(roll)
		if a and a.get_length() > 0.05:
			a.loop_mode = Animation.LOOP_NONE
			dur = a.get_length()
		ap_r.play(roll, BLEND)
		# 2º rolê no meio do caminho → "dois dodges" cobrem a distância maior sem deslizar
		get_tree().create_timer(dur).timeout.connect(func() -> void:
			if is_instance_valid(rn) and ap_r and R.get("hopping", false):
				ap_r.play(roll, BLEND))
	var total := dur * 2.0                               # dois rolês = leva o dobro de distância
	var tw := rn.create_tween()
	tw.tween_property(rn, "position:x", land_x, total)   # linear = velocidade de rolê constante
	tw.tween_callback(func() -> void:
		R["hopping"] = false
		if is_instance_valid(rn) and ap_r:
			ap_r.play(IDLE, BLEND))
	var ty := rn.create_tween()                          # arco vertical: um pulinho por rolê (dois)
	for _i in 2:
		ty.tween_property(rn, "position:y", base_y + 0.4, dur * 0.5).set_trans(Tween.TRANS_SINE)
		ty.tween_property(rn, "position:y", base_y, dur * 0.5).set_trans(Tween.TRANS_SINE)

# Um golpe: atacante INVESTE (lunge) pra frente + toca o ataque; defensor reage (Hit_Chest)
# e SANGRA no impacto. Ambos voltam pro idle sozinhos (animation_finished). Sem await.
func _swing(attacker: int, defender: int) -> void:
	var ap_a: AnimationPlayer = _fighters[attacker]["anim"]
	var ap_d: AnimationPlayer = _fighters[defender]["anim"]
	var na: Node3D = _fighters[attacker]["node"]
	var nd: Node3D = _fighters[defender]["node"]
	if not (is_instance_valid(na) and is_instance_valid(nd)):
		return
	var ranged: bool = _fighters[attacker].get("ranged", false)
	# atacante: ARCO → tiro (Spell_Simple_Shoot); MELEE → golpe aleatório de espada (A/B/combo/attack)
	if ap_a:
		var clip: String = SHOOT if ranged else ATTACKS[_rng.randi() % ATTACKS.size()]
		var an := ap_a.get_animation(clip)
		if an:
			an.loop_mode = Animation.LOOP_NONE
		ap_a.play(clip, BLEND)
	if ranged:
		_arrow(na, nd)   # arqueiro atira PARADO (sem investida) — a flecha voa até o alvo
	else:
		# investida melee: avança um passo pro oponente e recua (dá movimento)
		var dirx := signf(nd.position.x - na.position.x)
		var home := na.position
		var tw := na.create_tween()
		tw.tween_property(na, "position", home + Vector3(dirx * 0.38, 0, 0), 0.16).set_trans(Tween.TRANS_SINE)
		tw.tween_interval(0.10)
		tw.tween_property(na, "position", home, 0.30).set_trans(Tween.TRANS_SINE)
	# defensor: ~25% ESQUIVA (rola, sem sangue); senão LEVA o golpe (hurt variado + sangue)
	var dodge := _rng.randf() < 0.25
	var react: String = ROLL if dodge else HURTS[_rng.randi() % HURTS.size()]
	var hitdir := Vector3(nd.position.x - na.position.x, 0, 0)
	get_tree().create_timer(0.26).timeout.connect(func() -> void:
		if not is_instance_valid(nd):
			return
		if ap_d:
			var h := ap_d.get_animation(react)
			if h:
				h.loop_mode = Animation.LOOP_NONE
			ap_d.play(react, BLEND)
		if not dodge:
			_blood(nd.global_position + Vector3(0, 1.15, 0), hitdir))

# Flecha do arqueiro: vara fina que voa do atacante até o alvo e some. Decoração. [MENU_DUEL]
func _arrow(from_node: Node3D, to_node: Node3D) -> void:
	if not (is_instance_valid(from_node) and is_instance_valid(to_node)):
		return
	var arrow := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.035, 0.035, 0.55)   # comprida no eixo Z → vira "flecha"
	arrow.mesh = bm
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.55, 0.40, 0.18)
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	arrow.material_override = mat
	add_child(arrow)
	var start := from_node.global_position + Vector3(0, 1.35, 0)
	var endp := to_node.global_position + Vector3(0, 1.15, 0)
	arrow.global_position = start
	arrow.look_at(endp)   # aponta a vara pro alvo
	var tw := arrow.create_tween()
	tw.tween_property(arrow, "global_position", endp, 0.22)
	tw.tween_callback(arrow.queue_free)

# Jato de sangue por partículas (versão enxuta do GORE do BattleReplay). Some sozinho.
func _blood(pos: Vector3, dir: Vector3) -> void:
	var p := GPUParticles3D.new()
	p.one_shot = true
	p.explosiveness = 1.0
	p.amount = 24
	p.lifetime = 0.9
	var m := ParticleProcessMaterial.new()
	var d := Vector3.UP
	if dir.length() > 0.01:
		d = (dir.normalized() + Vector3.UP * 0.6).normalized()
	m.direction = d
	m.spread = 32.0
	m.initial_velocity_min = 1.6
	m.initial_velocity_max = 4.8
	m.gravity = Vector3(0, -9.0, 0)
	m.damping_min = 0.4
	m.damping_max = 1.6
	m.scale_min = 0.5
	m.scale_max = 1.35
	var g := Gradient.new()
	g.set_color(0, Color(0.55, 0.02, 0.02))
	g.add_point(0.55, Color(0.22, 0.0, 0.0))
	g.set_color(2, Color(0.22, 0.0, 0.0, 0.0))
	var gt := GradientTexture1D.new()
	gt.gradient = g
	m.color_ramp = gt
	p.process_material = m
	var quad := QuadMesh.new()
	quad.size = Vector2(0.085, 0.085)
	var qm := StandardMaterial3D.new()
	qm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	qm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	qm.vertex_color_use_as_albedo = true
	qm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	quad.material = qm
	p.draw_pass_1 = quad
	add_child(p)
	p.global_position = pos
	p.emitting = true
	get_tree().create_timer(1.1).timeout.connect(func() -> void:
		if is_instance_valid(p):
			p.queue_free())
