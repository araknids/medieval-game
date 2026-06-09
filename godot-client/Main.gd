extends Node3D
# ── Fase 0 — prova de pipeline 3D ──────────────────────────────────────────────
# Personagem Quaternius rigado + animação (UAL1) + ARMA presa no osso da mão (RightHand).
# Câmera, luz, ambiente e chão são criados por CÓDIGO.
# Controles:  ESPAÇO = atacar  •  R = morrer (Death01)  •  T = voltar  •  Q = decepar braço  •  E = decepar perna
# Plano: docs/PLANO_GODOT_3D.md

const IDLE := "UAL1_Standard/Sword_Idle"
const ATTACK := "UAL1_Standard/Sword_Attack"
const DEATH := "UAL1_Standard/Death01"

var anim: AnimationPlayer
var skel: Skeleton3D
var severed := {}        # idx do osso decepado -> true (fica escondido)
var _debris: Array = []  # peças/sangue spawnados (limpa no reset)

func _ready() -> void:
	process_priority = 100   # roda DEPOIS da animação (senão ela "des-esconde" o membro decepado)
	_setup_scene()
	anim = find_child("AnimationPlayer", true, false)
	skel = find_child("GeneralSkeleton", true, false)
	print("=== FASE 0 ===")
	print("AnimationPlayer: ", anim)
	print("Skeleton: ", skel)
	if anim:
		print("Animações disponíveis: ", anim.get_animation_list())
		anim.animation_finished.connect(_on_anim_done)
		_play_loop(IDLE)
	else:
		push_warning("AnimationPlayer NÃO encontrado")
	_attach_weapon()

func _play_loop(n: String) -> void:
	if anim == null: return
	var a := anim.get_animation(n)
	if a:
		a.loop_mode = Animation.LOOP_LINEAR     # força o idle a repetir (não congela)
		anim.play(n)
		print("tocando (loop): ", n)
	else:
		push_warning("animação não existe: " + n)

func _setup_scene() -> void:
	var cam := Camera3D.new()
	cam.position = Vector3(0.0, 1.1, 3.2)
	cam.rotation_degrees = Vector3(-8, 0, 0)
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
	pm.size = Vector2(12, 12)
	ground.mesh = pm
	add_child(ground)
	# colisão do chão (pro ragdoll cair em cima, não atravessar)
	var floor_body := StaticBody3D.new()
	var floor_col := CollisionShape3D.new()
	floor_col.shape = WorldBoundaryShape3D.new()   # plano infinito em y=0
	floor_body.add_child(floor_col)
	add_child(floor_body)

func _attach_weapon() -> void:
	if skel == null:
		push_warning("GeneralSkeleton NÃO encontrado — arma não anexada")
		return
	var ba := BoneAttachment3D.new()
	ba.bone_name = "RightHand"
	skel.add_child(ba)
	var sword := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.04, 0.6, 0.04)
	sword.mesh = bm
	sword.position = Vector3(0.0, 0.28, 0.0)
	ba.add_child(sword)
	print("arma anexada no osso RightHand")

func _on_anim_done(a: StringName) -> void:
	print("animação terminou: ", a)
	if a == ATTACK:
		_play_loop(IDLE)

var _prev := {}
func _just(key: int) -> bool:                         # borda de subida da tecla (polling)
	var down := Input.is_physical_key_pressed(key)
	var was: bool = _prev.get(key, false)
	_prev[key] = down
	return down and not was

func _process(_dt: float) -> void:
	if _just(KEY_SPACE) or _just(KEY_ENTER) or _just(KEY_KP_ENTER):
		print("tecla ATAQUE")
		if anim: anim.play(ATTACK)
	if _just(KEY_R):
		print("tecla RAGDOLL")
		_ragdoll()
	if _just(KEY_T):
		print("tecla VOLTAR")
		_unragdoll()
	if _just(KEY_Q):
		_sever("RightUpperArm")    # decepa braço direito
	if _just(KEY_E):
		_sever("LeftUpperLeg")     # decepa perna esquerda
	# mantém os membros decepados escondidos (reaplica escala ~0 após a animação)
	if skel:
		for idx in severed:
			skel.set_bone_pose_scale(idx, Vector3(0.01, 0.01, 0.01))

func _ragdoll() -> void:
	# Por ora a "queda/morte" é por ANIMAÇÃO (Death01) — funciona na hora, sem setup no editor.
	# Ragdoll de FÍSICA real (flopar/desmembrar) entra na fase de polimento (precisa de PhysicalBone3D).
	if anim:
		var d := anim.get_animation(DEATH)
		if d: d.loop_mode = Animation.LOOP_NONE
		anim.play(DEATH)
		print("morte (Death01)")

func _unragdoll() -> void:
	if skel:
		for idx in severed:
			skel.set_bone_pose_scale(idx, Vector3.ONE)   # devolve o membro
	severed.clear()
	for d in _debris:
		if is_instance_valid(d): d.queue_free()
	_debris.clear()
	_play_loop(IDLE)

# ── Desmembramento (Rota A: esconde o osso + cospe peça + sangue) ──────────────
func _sever(bone_name: String) -> void:
	if skel == null: return
	var idx := skel.find_bone(bone_name)
	if idx < 0:
		print("osso não encontrado: ", bone_name); return
	if severed.has(idx): return
	severed[idx] = true
	var pos: Vector3 = (skel.global_transform * skel.get_bone_global_pose(idx)).origin
	_spawn_limb(pos)
	_spawn_blood(pos)
	print("DECEPOU: ", bone_name, " (bone ", idx, ")")

func _spawn_limb(pos: Vector3) -> void:
	var rb := RigidBody3D.new()
	var col := CollisionShape3D.new()
	var shape := CapsuleShape3D.new()
	shape.radius = 0.06
	shape.height = 0.5
	col.shape = shape
	rb.add_child(col)
	var mi := MeshInstance3D.new()
	var cm := CapsuleMesh.new()
	cm.radius = 0.06
	cm.height = 0.5
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.72, 0.46, 0.32)   # cor de pele
	mi.material_override = mat
	mi.mesh = cm
	rb.add_child(mi)
	add_child(rb)
	rb.global_position = pos
	rb.linear_velocity = Vector3(2.6, 3.2, 0.6)   # voa pra fora/cima
	rb.angular_velocity = Vector3(6, 2, 4)        # girando
	_debris.append(rb)

func _spawn_blood(pos: Vector3) -> void:
	var p := CPUParticles3D.new()
	var sphere := SphereMesh.new()
	sphere.radius = 0.03
	sphere.height = 0.06
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.6, 0.02, 0.05)     # vermelho sangue
	sphere.material = mat
	p.mesh = sphere
	p.amount = 30
	p.lifetime = 0.9
	p.one_shot = true
	p.explosiveness = 0.9
	p.direction = Vector3(1, 0.6, 0)
	p.spread = 65.0
	p.initial_velocity_min = 1.5
	p.initial_velocity_max = 3.5
	p.gravity = Vector3(0, -7, 0)
	add_child(p)
	p.global_position = pos
	p.emitting = true
	_debris.append(p)
	get_tree().create_timer(1.5).timeout.connect(p.queue_free)
