extends Node3D
# ── Dois guerreiros duelando no fundo do menu (castelo) — pura decoração. [MENU_DUEL] ──
# Reusa o rig Quaternius (Male_rigged) + as peças Ranger (paper-doll) + a espada procedural
# (Weapons) + as anims de espada do BattleReplay, e roda um loop simples: um ataca, o outro
# reage, ambos voltam pro idle. Defensivo: se qualquer asset faltar, só não aparece (o menu
# continua funcionando). Personagem 3D: docs/PLANO_GODOT_3D.md

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const Weapons := preload("res://Weapons.gd")
const UAL2_PATH := "res://addons/quaternius_ik_rigged/UAL2_Standard.glb"
const LIB := "UAL1_Standard/"
const LIB2 := "UAL2_Standard/"
const IDLE := LIB + "Sword_Idle"
const HURTS := [LIB + "Hit_Chest", LIB + "Hit_Head"]   # reação variada (peito/cabeça)
const ROLL := LIB + "Roll"                              # esquiva ocasional (in-place, sem sangue)
# golpes variados: 3 da UAL2 (A/B/combo) + o Sword_Attack da UAL1
const ATTACKS := [LIB2 + "Sword_Regular_A", LIB2 + "Sword_Regular_B", LIB2 + "Sword_Regular_Combo", LIB + "Sword_Attack"]
const BLEND := 0.12

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

func _ready() -> void:
	_rng.seed = 770413
	# luz quente de preenchimento (o castelo noturno é escuro) — destaca o duelo
	var key := OmniLight3D.new()
	key.light_color = Color(1.0, 0.86, 0.62)
	key.light_energy = 2.6
	key.omni_range = 13.0
	key.position = Vector3(0, 4.0, 7.5)
	add_child(key)
	_spawn(POS_L, 90.0, 1)     # esquerda → encara a direita (espada comum)
	_spawn(POS_R, -90.0, 3)    # direita  → encara a esquerda (espada azul)

func _spawn(pos: Vector3, yaw_deg: float, weapon_rarity: int) -> void:
	var node := CHAR.instantiate()
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
	# veste de Ranger (esconde o corpo base) + espada na mão
	if skel:
		_dress(node, skel)
		Weapons.new().attach_weapon(node, "sword", weapon_rarity)
	# idle em loop; one-shot (ataque/hurt) volta pro idle ao terminar
	if ap:
		var il := ap.get_animation(IDLE)
		if il:
			il.loop_mode = Animation.LOOP_LINEAR
		ap.animation_finished.connect(func(_a: StringName) -> void:
			if is_instance_valid(node):
				ap.play(IDLE, BLEND))
		ap.play(IDLE)
	_fighters.append({"node": node, "anim": ap})

func _dress(node: Node3D, skel: Skeleton3D) -> void:
	# esconde todas as malhas base (Superhero) ANTES de vestir
	var base: Array = []
	_collect_meshes(node, base)
	for m: MeshInstance3D in base:
		m.visible = false
	# rosto + set Ranger completo, reparenteados sob o esqueleto compartilhado
	for path in [BASE_HEAD] + RANGER:
		var scene = load(path)
		if scene is PackedScene:
			_attach_outfit(scene, skel)

func _attach_outfit(scene: PackedScene, skel: Skeleton3D) -> void:
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
	inst.queue_free()

func _collect_meshes(n: Node, out: Array) -> void:
	if n is MeshInstance3D:
		out.append(n)
	for c in n.get_children():
		_collect_meshes(c, out)

func _process(dt: float) -> void:
	if _fighters.size() < 2:
		return
	_timer -= dt
	if _timer <= 0.0:
		_timer = _rng.randf_range(0.65, 1.05)   # bem mais frequente → duelo vivo, não travado
		_atk = 1 - _atk
		_swing(_atk, 1 - _atk)

# Um golpe: atacante INVESTE (lunge) pra frente + toca o ataque; defensor reage (Hit_Chest)
# e SANGRA no impacto. Ambos voltam pro idle sozinhos (animation_finished). Sem await.
func _swing(attacker: int, defender: int) -> void:
	var ap_a: AnimationPlayer = _fighters[attacker]["anim"]
	var ap_d: AnimationPlayer = _fighters[defender]["anim"]
	var na: Node3D = _fighters[attacker]["node"]
	var nd: Node3D = _fighters[defender]["node"]
	if not (is_instance_valid(na) and is_instance_valid(nd)):
		return
	# atacante: golpe ALEATÓRIO (A/B/combo/attack)
	if ap_a:
		var clip: String = ATTACKS[_rng.randi() % ATTACKS.size()]
		var an := ap_a.get_animation(clip)
		if an:
			an.loop_mode = Animation.LOOP_NONE
		ap_a.play(clip, BLEND)
	# investida: avança um passo pro oponente e recua (dá movimento)
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
