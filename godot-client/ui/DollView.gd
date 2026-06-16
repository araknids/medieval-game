extends SubViewportContainer
class_name DollView
# ── Paper-doll de CORPO INTEIRO ao vivo (Ficha do Personagem) [FICHA_PERSONAGEM] ────
# Renderiza o herói INTEIRO vestido com o gear REAL num SubViewport isolado (own_world_3d),
# com giro lento p/ mostrar o equipamento de todos os lados. Reaproveita o pipeline do BustView
# (mesmas peças Ranger + partes-base nuas). Re-veste via apply(inv_arr) — sem fetch. [GODOT_PAPERDOLL]

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const IDLE := "UAL1_Standard/Sword_Idle"
const BASE_HEAD := "res://assets/base/Base_Male_Head.gltf"
const BASE_PART := {
	"res://assets/base/Base_Male_Torso.gltf": "ARMOR",
	"res://assets/base/Base_Male_Arms.gltf":  "GLOVES",
	"res://assets/base/Base_Male_Legs.gltf":  "PANTS",
	"res://assets/base/Base_Male_Feet.gltf":  "BOOTS",
}
const PIECES := {
	"ARMOR":    "res://assets/outfits/ranger/Male_Ranger_Body.gltf",
	"PANTS":    "res://assets/outfits/ranger/Male_Ranger_Legs.gltf",
	"BOOTS":    "res://assets/outfits/ranger/Male_Ranger_Feet_Boots.gltf",
	"GLOVES":   "res://assets/outfits/ranger/Male_Ranger_Arms.gltf",
	"HELMET":   "res://assets/outfits/ranger/Male_Ranger_Head_Hood.gltf",
	"SHOULDER": "res://assets/outfits/ranger/Male_Ranger_Acc_Pauldron.gltf",
}

const Weapons := preload("res://Weapons.gd")   # arma/escudo procedurais (mesmo da batalha)

@export var spin := true                 # giro lento (mostra o gear de todos os lados)
const SPIN_DEG_PER_SEC := 16.0
const SPIN_RESUME := 2.5                  # s parado após arrastar → volta o giro automático
const DRAG_RAD_PER_PX := 0.01            # sensibilidade do girar-arrastando

var skel: Skeleton3D
var _world: Node3D
var _character: Node3D
var _body_meshes: Array = []
var _ready_done := false
var _dragging := false
var _idle := 999.0                       # começa girando; arrastar zera, e SPIN_RESUME depois retoma
var _wp := Weapons.new()
var _props: Array = []                    # arma/escudo anexados (BoneAttachment3D) — removidos ao reequipar

func _ready() -> void:
	stretch = true
	mouse_filter = Control.MOUSE_FILTER_STOP   # captura o arrasto p/ girar o boneco
	var sv := SubViewport.new()
	sv.own_world_3d = true                 # mundo isolado (não vaza pro World3D do jogo)
	sv.transparent_bg = true
	sv.msaa_3d = Viewport.MSAA_2X
	add_child(sv)
	_world = Node3D.new()
	sv.add_child(_world)
	var cam := Camera3D.new()               # câmera de CORPO INTEIRO, com zoom (FOV teleobjetiva, sem distorção)
	cam.position = Vector3(0.0, 1.0, 3.2)
	cam.rotation_degrees = Vector3(-3, 0, 0)
	cam.fov = 44.0                          # < 75 (default) = mais perto/maior, mantendo o corpo todo
	_world.add_child(cam)
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-40, -35, 0)
	key.light_energy = 1.35
	_world.add_child(key)
	var fill := DirectionalLight3D.new()    # luz de preenchimento suave (tira o lado escuro chapado)
	fill.rotation_degrees = Vector3(-20, 130, 0)
	fill.light_energy = 0.4
	_world.add_child(fill)
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0, 0, 0, 0)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.55, 0.52, 0.6)
	env.ambient_light_energy = 1.0
	var we := WorldEnvironment.new()
	we.environment = env
	_world.add_child(we)
	_character = CHAR.instantiate()
	_world.add_child(_character)
	skel = _character.find_child("GeneralSkeleton", true, false)
	var anim: AnimationPlayer = _character.find_child("AnimationPlayer", true, false)
	_collect_meshes(_character, _body_meshes)
	if anim:
		var idle := anim.get_animation(IDLE)
		if idle:
			idle.loop_mode = Animation.LOOP_LINEAR
		anim.play(IDLE)
	_ready_done = true

func _process(delta: float) -> void:
	if _dragging or _character == null:
		return
	_idle += delta
	if spin and _idle >= SPIN_RESUME:       # giro automático só quando ninguém está arrastando há um tempo
		_character.rotate_y(deg_to_rad(SPIN_DEG_PER_SEC) * delta)

# Girar arrastando com o mouse (botão esquerdo). Pausa o giro automático enquanto arrasta.
func _gui_input(event: InputEvent) -> void:
	if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
		_dragging = event.pressed
		_idle = 0.0
	elif event is InputEventMouseMotion and _dragging and _character != null:
		_character.rotate_y(-event.relative.x * DRAG_RAD_PER_PX)
		_idle = 0.0

# Re-veste o boneco a partir de uma lista de inventário já carregada (sem fetch). Mesma lógica do BustView.
func apply(inv_arr: Array) -> void:
	if not _ready_done or skel == null:
		return
	for c in skel.get_children():
		if c is MeshInstance3D and c.has_meta("outfit"):
			c.queue_free()
	var equipped: Array = inv_arr.filter(func(it): return it is Dictionary and it.get("equipped") == true)
	var dressed: Array = []
	for it in equipped:
		var ty := str(it.get("type", ""))
		if PIECES.has(ty):
			var sc: PackedScene = load(PIECES[ty])
			if sc:
				_attach(sc)
				dressed.append(ty)
	for m: MeshInstance3D in _body_meshes:
		m.visible = false
	var head: PackedScene = load(BASE_HEAD)
	if head:
		_attach(head)
	for path in BASE_PART:
		if not dressed.has(BASE_PART[path]):
			var p: PackedScene = load(path)
			if p:
				_attach(p)
	_apply_weapons(equipped)

# Anexa arma (mão) + escudo (antebraço) a partir do equip; remove os antigos antes. [FICHA_PERSONAGEM]
func _apply_weapons(equipped: Array) -> void:
	# Remoção À PROVA DE FALHAS: tira TODO prop marcado do esqueleto (não confia só no array _props,
	# que pode dessincronizar). Senão a arma antiga continua na mão ao desequipar.
	if skel != null:
		for c in skel.get_children():
			if c.has_meta("delveprop"):
				c.queue_free()
	_props.clear()
	for it in equipped:
		var ty := str(it.get("type", ""))
		if ty == "WEAPON":
			var kind := _wp.weapon_kind(str(it.get("name", "")), str(it.get("weaponCategory", "")))
			# força a mão DIREITA no boneco → arco não fica no mesmo lado do escudo (esquerdo)
			var node := _wp.attach_weapon(_character, kind, int(it.get("rarity", 1)), 0.10, "RightHand")
			if node != null:
				node.set_meta("delveprop", true)
				_props.append(node)
		elif ty == "SHIELD":
			# escudo é filho do osso do antebraço → já gira junto com o boneco (sem Callable)
			var snode := _wp.attach_shield(_character, {"rarity": int(it.get("rarity", 1))})
			if snode != null:
				snode.set_meta("delveprop", true)
				_props.append(snode)

func _attach(scene: PackedScene) -> void:
	if skel == null:
		return
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
		mi.set_meta("outfit", true)
	inst.queue_free()

func _collect_meshes(node: Node, out: Array) -> void:
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		_collect_meshes(c, out)
