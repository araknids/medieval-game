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
const HOP_DIST := 0.85      # distância de cada mini-salto pra trás

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
	# colisão do chão (pra o ragdoll não atravessar o piso)
	var floor_body := StaticBody3D.new()
	var floor_col := CollisionShape3D.new()
	floor_col.shape = WorldBoundaryShape3D.new()   # plano infinito em y=0
	floor_body.add_child(floor_col)
	add_child(floor_body)

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
	name_lbl.text = fname + (Lang.t("  (arqueiro)") if ranged else "")
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

	# Arqueiro: kiting com MINI-SALTOS pra trás — encara o goblin SEMPRE e recua em
	# pulinhos discretos (evita o "moonwalk" do walk invertido); cruza na borda (A_ROLL).
	if not hero["hopping"]:
		hn.rotation_degrees = Vector3(0, (90.0 if gx > hx else -90.0), 0)  # mira constante
		hop_cd -= dt
		var gap := absf(hx - gx)
		var hca := ""
		if hero["anim"]: hca = hero["anim"].current_animation
		if gap < GAP and hop_cd <= 0.0:           # goblin colou → salta pra trás
			var away := signf(hx - gx)
			if away == 0.0: away = 1.0
			var target_x := hx + away * HOP_DIST
			if absf(target_x) > EDGE:
				_archer_cross(gx - away * GAP)    # encurralado na borda → cruza
			else:
				_hop_back(target_x)
		elif hca != A_SHOOT and hca != A_IDLE:    # distância ok: idle encarando (sem cortar tiro)
			hero["anim"].play(A_IDLE)

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
	_popup(_head(hero), Lang.t("↩ CRUZA!"), Color(0.8, 0.9, 1.0))
	var tw := create_tween()
	tw.tween_property(hn, "position", Vector3(target_x, hn.position.y, hn.position.z), 0.55)
	tw.tween_callback(func():
		hero["hopping"] = false
		if hero["anim"] and not hero["dead"]: hero["anim"].play(A_IDLE))

func _hop_back(target_x: float) -> void:
	hero["hopping"] = true
	var hn: Node3D = hero["node"]
	if hero["anim"]: hero["anim"].play(A_JUMP)
	var tw := create_tween()
	tw.tween_property(hn, "position", Vector3(target_x, hn.position.y, hn.position.z), 0.32) \
		.set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_OUT)
	tw.tween_callback(func():
		hero["hopping"] = false
		hop_cd = HOP_CD
		if hero["anim"] and not hero["dead"]: hero["anim"].play(A_IDLE))

func _kill(f: Dictionary) -> void:
	battle_over = true
	f["dead"] = true
	var node: Node3D = f["node"]
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel and _has_physical_bones(skel):
		# RAGDOLL real: a física assume o esqueleto e o boneco tomba de verdade
		if f["anim"]: f["anim"].stop()
		skel.physical_bones_start_simulation()
		var push := signf(node.position.x)        # empurra pra longe do centro
		if push == 0.0: push = 1.0
		for c in skel.get_children():
			if c is PhysicalBone3D and (c.bone_name in ["Spine", "Spine1", "Spine2", "Hips"]):
				(c as PhysicalBone3D).apply_central_impulse(Vector3(push * 2.5, 1.2, 0.0))
	elif f["anim"]:
		# fallback (rig sem PhysicalBone3D): toca a anim de morte, como antes
		var d: Animation = f["anim"].get_animation(A_DEATH)
		if d: d.loop_mode = Animation.LOOP_NONE
		f["anim"].play(A_DEATH)
		print("Ragdoll: rig sem PhysicalBone3D — use 'Create Physical Skeleton' no editor p/ fisica real.")
	if victory_label:
		var w: Dictionary = hero if f["name"] == foe["name"] else foe
		victory_label.text = Lang.t("%s venceu!") % w["name"]

func _has_physical_bones(skel: Skeleton3D) -> bool:
	for c in skel.get_children():
		if c is PhysicalBone3D:
			return true
	return false

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
