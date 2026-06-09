extends Node3D
# ── Fase 2 (início) — Replay 3D de batalha dirigido por eventos ────────────────
# 2 lutadores frente a frente. Um "diretor" percorre uma lista de eventos no MESMO
# formato do BattleEvent do backend (type/actor/target/damage). Por ora a lista é um
# EXEMPLO embutido; depois trocamos por um HTTPRequest pro backend (mesmos eventos do
# battleArena.js). Mapeia: spawn→idle, attack/crit→golpe + alvo leva Hit_Chest, death→Death01.
# Plano: docs/PLANO_GODOT_3D.md (§5/§7)

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const LIB := "UAL1_Standard/"
const A_IDLE := LIB + "Sword_Idle"
const A_ATTACK := LIB + "Sword_Attack"
const A_HIT := LIB + "Hit_Chest"
const A_DEATH := LIB + "Death01"

var hero := {}
var foe := {}

# ── stream de eventos de EXEMPLO (formato espelha o backend) ──
var events := [
	{"type": "spawn",  "actor": "Hero"},
	{"type": "spawn",  "actor": "Goblin"},
	{"type": "attack", "actor": "Hero",   "target": "Goblin", "damage": 12},
	{"type": "attack", "actor": "Goblin", "target": "Hero",   "damage": 8},
	{"type": "crit",   "actor": "Hero",   "target": "Goblin", "damage": 25},
	{"type": "attack", "actor": "Goblin", "target": "Hero",   "damage": 6},
	{"type": "attack", "actor": "Hero",   "target": "Goblin", "damage": 14},
	{"type": "death",  "actor": "Goblin"},
]
var idx := 0
var timer := 0.0
const STEP := 1.3   # segundos por evento

func _ready() -> void:
	_setup_scene()
	hero = _make_fighter("Hero",   Vector3(-0.8, 0, 0),  90)   # encara +X (pro inimigo)
	foe  = _make_fighter("Goblin", Vector3( 0.8, 0, 0), -90)   # encara -X (mais perto agora)
	print("=== BATTLE === lutadores prontos, ", events.size(), " eventos")

func _setup_scene() -> void:
	var cam := Camera3D.new()
	cam.position = Vector3(0.0, 1.5, 5.0)
	cam.rotation_degrees = Vector3(-10, 0, 0)
	add_child(cam)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, -30, 0)
	sun.light_energy = 1.2
	sun.shadow_enabled = true
	add_child(sun)
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.17, 0.17, 0.22)
	env.ambient_light_energy = 0.8
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	var ground := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(14, 14)
	ground.mesh = pm
	add_child(ground)

func _make_fighter(fname: String, pos: Vector3, rot_y: float) -> Dictionary:
	var node := CHAR.instantiate()
	add_child(node)
	node.position = pos
	node.rotation_degrees = Vector3(0, rot_y, 0)
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	_attach_sword(node)
	var f := {"name": fname, "node": node, "anim": ap, "home": pos, "dead": false}
	if ap:
		var il := ap.get_animation(A_IDLE)
		if il: il.loop_mode = Animation.LOOP_LINEAR
		# ao terminar um one-shot (golpe/hit), volta pro idle — a menos que esteja morto
		ap.animation_finished.connect(func(_a):
			if not f["dead"]:
				ap.play(A_IDLE))
		ap.play(A_IDLE)
	return f

func _by_name(n: String) -> Dictionary:
	if hero.get("name") == n: return hero
	if foe.get("name") == n: return foe
	return {}

func _process(dt: float) -> void:
	if idx >= events.size():
		return
	timer += dt
	if timer >= STEP:
		timer = 0.0
		_play_event(events[idx])
		idx += 1

func _play_event(e: Dictionary) -> void:
	print("evento: ", e)
	match e.get("type", ""):
		"spawn":
			pass   # já nascem em idle
		"attack", "crit":
			var atk := _by_name(e.get("actor", ""))
			var tgt := _by_name(e.get("target", ""))
			if atk and not atk["dead"]:
				atk["anim"].play(A_ATTACK)
				_lunge(atk, tgt)
			await get_tree().create_timer(0.4).timeout   # o golpe conecta um tiquinho depois
			if tgt and not tgt["dead"]:
				tgt["anim"].play(A_HIT)
		"death":
			var who := _by_name(e.get("actor", ""))
			if who:
				who["dead"] = true
				var d = who["anim"].get_animation(A_DEATH)
				if d: d.loop_mode = Animation.LOOP_NONE
				who["anim"].play(A_DEATH)

func _attach_sword(node: Node3D) -> void:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return
	var ba := BoneAttachment3D.new()
	ba.bone_name = "RightHand"
	skel.add_child(ba)
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.04, 0.7, 0.04)            # lâmina comprida (dá alcance ao golpe)
	mi.mesh = bm
	# orientação da lâmina no punho (alinha ao eixo X da mão = sai do topo do punho)
	mi.rotation_degrees = Vector3(0, 0, -90)
	# +X = lâmina pra cima;  +Y = pros dedos;  +Z = pra dentro da palma (vão dos dedos)
	mi.position = Vector3(0.30, 0.07, 0.04)
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.82, 0.84, 0.88)
	mi.material_override = mat
	ba.add_child(mi)

func _lunge(atk: Dictionary, tgt: Dictionary) -> void:
	if atk.is_empty() or tgt.is_empty(): return
	var node: Node3D = atk["node"]
	var home: Vector3 = atk["home"]
	var forward: Vector3 = home.lerp(tgt["home"], 0.18)   # nudge curto (menos escorregão)
	var tw := create_tween()
	tw.tween_property(node, "position", forward, 0.12)
	tw.tween_property(node, "position", home, 0.22)
