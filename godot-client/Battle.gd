extends Node3D
# ── Replay 3D — KITING contínuo (mock) ─────────────────────────────────────────
# Hero = ARQUEIRO: mantém distância pulando pra trás, atira flechas; ao chegar na BORDA,
#   CRUZA pro outro lado do inimigo e recua até a outra borda.
# Goblin = MELEE: avança SEM PARAR (contínuo, mais lento), encarando o arqueiro.
# Movimento é simulado aqui (cosmético); o backend decide o resultado (depois via eventos).
# Plano: docs/PLANO_GODOT_3D.md (§6.4)

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const LIB := "UAL1_Standard/"
const A_IDLE := LIB + "Sword_Idle"
const A_HIT := LIB + "Hit_Chest"
const A_SHOOT := LIB + "Spell_Simple_Shoot"
const A_WALK := LIB + "Walk"
const A_JUMP := LIB + "Jump"
const A_ROLL := LIB + "Roll"
const A_DEATH := LIB + "Death01"
const BARW := 0.7

# parâmetros do kiting
const MELEE_SPEED := 0.75   # velocidade do goblin (unid/s)
const ARCHER_SPEED := 1.05  # arqueiro recua um pouco mais rápido (mantém distância)
const GAP := 2.2            # distância que o arqueiro tenta manter
const EDGE := 3.8           # borda do mapa (onde o arqueiro cruza)
const SHOOT_INTERVAL := 1.1
const HOP_CD := 0.45        # tempo mínimo entre pulinhos pra trás

var hero := {}
var foe := {}
var victory_label: Label
var battle_over := false
var hop_cd := 0.0
var shoot_t := 0.8

func _ready() -> void:
	_setup_scene()
	hero = _make_fighter("Hero",   Vector3(-2.0, 0, 0),  90, 90, 1.0,  true)
	foe  = _make_fighter("Goblin", Vector3( 3.5, 0, 0), -90, 80, 0.82, false)
	_make_victory_ui()
	print("=== KITING === arqueiro x melee")

func _setup_scene() -> void:
	var cam := Camera3D.new()
	cam.position = Vector3(0.0, 2.4, 9.0)
	cam.rotation_degrees = Vector3(-12, 0, 0)
	add_child(cam)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, -30, 0)
	sun.light_energy = 1.2
	sun.shadow_enabled = true
	add_child(sun)
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.15, 0.15, 0.2)
	env.ambient_light_energy = 0.8
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	var ground := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(24, 24)
	var gmat := StandardMaterial3D.new()
	gmat.albedo_color = Color(0.22, 0.2, 0.18)
	ground.material_override = gmat
	ground.mesh = pm
	add_child(ground)

func _make_fighter(fname: String, pos: Vector3, rot_y: float, maxhp: int, sc: float, ranged: bool) -> Dictionary:
	var node := CHAR.instantiate()
	add_child(node)
	node.position = pos
	node.rotation_degrees = Vector3(0, rot_y, 0)
	node.scale = Vector3(sc, sc, sc)
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	var f := {"name": fname, "node": node, "anim": ap, "dead": false,
			  "maxhp": maxhp, "hp": maxhp, "ranged": ranged, "hopping": false}
	_attach_weapon(node, ranged)
	var bar := Node3D.new()
	add_child(bar)
	bar.add_child(_quad(BARW, 0.09, Color(0, 0, 0, 0.55), 0))
	var fill := _quad(BARW, 0.09, Color(0.25, 0.85, 0.35, 1.0), 1)
	bar.add_child(fill)
	var name_lbl := Label3D.new()
	name_lbl.text = fname + ("  (arqueiro)" if ranged else "")
	name_lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	name_lbl.no_depth_test = true
	name_lbl.pixel_size = 0.004
	name_lbl.font_size = 48
	name_lbl.position = Vector3(0, 0.14, 0)
	bar.add_child(name_lbl)
	f["bar"] = bar
	f["fill"] = fill
	if ap:
		var il := ap.get_animation(A_IDLE)
		if il: il.loop_mode = Animation.LOOP_LINEAR
		ap.animation_finished.connect(func(_a):
			if not f["dead"] and not f["hopping"]:
				ap.play(A_IDLE))
		ap.play(A_IDLE)
	return f

func _attach_weapon(node: Node3D, ranged: bool) -> void:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return
	var ba := BoneAttachment3D.new()
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	var mat := StandardMaterial3D.new()
	if ranged:
		ba.bone_name = "LeftHand"
		bm.size = Vector3(0.03, 0.6, 0.03)
		mi.position = Vector3(0.10, 0.07, 0.04)
		mat.albedo_color = Color(0.45, 0.3, 0.16)
	else:
		ba.bone_name = "RightHand"
		bm.size = Vector3(0.04, 0.7, 0.04)
		mi.rotation_degrees = Vector3(0, 0, -90)
		mi.position = Vector3(0.30, 0.07, 0.04)
		mat.albedo_color = Color(0.82, 0.84, 0.88)
		mat.metallic = 0.6
	mi.mesh = bm
	mi.material_override = mat
	skel.add_child(ba)
	ba.add_child(mi)

func _quad(w: float, h: float, col: Color, prio: int) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(w, h)
	mi.mesh = q
	var mat := StandardMaterial3D.new()
	mat.albedo_color = col
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA if col.a < 1.0 else BaseMaterial3D.TRANSPARENCY_DISABLED
	mat.render_priority = prio
	mat.no_depth_test = true
	mi.material_override = mat
	return mi

func _make_victory_ui() -> void:
	var layer := CanvasLayer.new()
	add_child(layer)
	victory_label = Label.new()
	victory_label.add_theme_font_size_override("font_size", 42)
	victory_label.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	victory_label.offset_top = 40
	victory_label.offset_bottom = 110
	victory_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	layer.add_child(victory_label)

func _process(dt: float) -> void:
	for f in [hero, foe]:
		if f.has("bar") and is_instance_valid(f["node"]):
			f["bar"].global_position = (f["node"] as Node3D).global_position + Vector3(0, 2.05, 0)
	if battle_over:
		return

	var hn: Node3D = hero["node"]
	var gn: Node3D = foe["node"]
	var hx: float = hn.position.x
	var gx: float = gn.position.x
	var side := signf(hx - gx)          # +1 = arqueiro à direita do goblin
	if side == 0.0: side = 1.0

	# Goblin avança CONTÍNUO rumo ao arqueiro (sem pausa)
	gx += side * MELEE_SPEED * dt
	gn.position = Vector3(gx, gn.position.y, gn.position.z)
	gn.rotation_degrees = Vector3(0, (90.0 if side > 0 else -90.0), 0)
	var gca := ""
	if foe["anim"]: gca = foe["anim"].current_animation
	if foe["anim"] and gca != A_WALK and gca != A_HIT and gca != A_DEATH:
		var w: Animation = foe["anim"].get_animation(A_WALK)
		if w: w.loop_mode = Animation.LOOP_LINEAR
		foe["anim"].play(A_WALK)

	# Arqueiro: kiting clássico — ENCARA o goblin SEMPRE e recua de costas (backpedal);
	# cruza na borda (único momento em que sai da mira, via A_ROLL no _archer_cross)
	if not hero["hopping"]:
		var away := signf(hx - gx)            # lado/direção de recuo do arqueiro
		if away == 0.0: away = 1.0
		var desired := gx + away * GAP        # ponto a GAP de distância do goblin
		if absf(desired) > EDGE:
			_archer_cross(gx - away * GAP)    # encurralado na borda → cruza
		else:
			var new_hx := move_toward(hx, desired, ARCHER_SPEED * dt)
			var moving_back := absf(new_hx - hx) > 0.0008
			hn.position = Vector3(new_hx, hn.position.y, hn.position.z)
			# mira constante: rotação Y sempre apontando pro goblin
			hn.rotation_degrees = Vector3(0, (90.0 if gx > hx else -90.0), 0)
			var hca := ""
			if hero["anim"]: hca = hero["anim"].current_animation
			if hca != A_SHOOT:                # não interrompe o tiro (já está de frente)
				if moving_back:
					if hca != A_WALK:
						var w2: Animation = hero["anim"].get_animation(A_WALK)
						if w2: w2.loop_mode = Animation.LOOP_LINEAR
						hero["anim"].play_backwards(A_WALK)   # sem anim de ré → Walk invertido
				elif hca == A_WALK:
					hero["anim"].play(A_IDLE) # parou na distância GAP: idle, ainda encarando

	# Arqueiro atira em intervalos
	shoot_t -= dt
	if shoot_t <= 0.0 and not hero["hopping"]:
		shoot_t = SHOOT_INTERVAL
		if hero["anim"]: hero["anim"].play(A_SHOOT)
		_shoot_arrow(hero, foe, randi_range(8, 14))

	if int(foe["hp"]) <= 0:
		_kill(foe)

func _archer_cross(target_x: float) -> void:
	hero["hopping"] = true
	hop_cd = HOP_CD + 0.3
	var hn: Node3D = hero["node"]
	if hero["anim"]: hero["anim"].play(A_ROLL)
	_popup(_head(hero), "↩ CRUZA!", Color(0.8, 0.9, 1.0))
	var tw := create_tween()
	tw.tween_property(hn, "position", Vector3(target_x, hn.position.y, hn.position.z), 0.55)
	tw.tween_callback(func():
		hero["hopping"] = false
		if hero["anim"] and not hero["dead"]: hero["anim"].play(A_IDLE))

func _kill(f: Dictionary) -> void:
	battle_over = true
	f["dead"] = true
	if f["anim"]:
		var d: Animation = f["anim"].get_animation(A_DEATH)
		if d: d.loop_mode = Animation.LOOP_NONE
		f["anim"].play(A_DEATH)
	if victory_label:
		var w: Dictionary = hero if f["name"] == foe["name"] else foe
		victory_label.text = w["name"] + " venceu!"

func _shoot_arrow(a: Dictionary, b: Dictionary, dmg: int) -> void:
	if a.is_empty() or b.is_empty() or b["dead"]: return
	var arrow := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.03, 0.03, 0.5)
	arrow.mesh = bm
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.5, 0.35, 0.2)
	arrow.material_override = mat
	add_child(arrow)
	var start: Vector3 = _chest(a)
	var endp: Vector3 = _chest(b)
	arrow.global_position = start
	arrow.look_at(endp)
	var tw := create_tween()
	tw.tween_property(arrow, "global_position", endp, 0.25)
	tw.tween_callback(func():
		arrow.queue_free()
		if not b["dead"]:
			b["anim"].play(A_HIT)
			_damage(b, dmg))

func _damage(f: Dictionary, dmg: int) -> void:
	f["hp"] = max(0, int(f["hp"]) - dmg)
	_update_hp(f)
	_popup(_head(f), str(dmg), Color(1, 1, 1))

func _update_hp(f: Dictionary) -> void:
	var ratio: float = float(f["hp"]) / float(f["maxhp"])
	var fill: MeshInstance3D = f["fill"]
	fill.scale = Vector3(max(0.001, ratio), 1.0, 1.0)
	fill.position = Vector3(-BARW * 0.5 * (1.0 - ratio), 0.0, 0.0)
	var mat: StandardMaterial3D = fill.material_override
	mat.albedo_color = Color(0.85, 0.25, 0.25).lerp(Color(0.25, 0.85, 0.35), ratio)

func _head(f: Dictionary) -> Vector3:
	return (f["node"] as Node3D).global_position + Vector3(0, 1.7, 0)

func _chest(f: Dictionary) -> Vector3:
	return (f["node"] as Node3D).global_position + Vector3(0, 1.2, 0)

func _popup(pos: Vector3, text: String, color: Color) -> void:
	var lbl := Label3D.new()
	lbl.text = text
	lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	lbl.no_depth_test = true
	lbl.modulate = color
	lbl.pixel_size = 0.006
	lbl.font_size = 64
	add_child(lbl)
	lbl.global_position = pos
	var tw := create_tween().set_parallel(true)
	tw.tween_property(lbl, "global_position", pos + Vector3(0, 0.8, 0), 0.8)
	tw.tween_property(lbl, "modulate:a", 0.0, 0.8)
	get_tree().create_timer(0.9).timeout.connect(lbl.queue_free)
