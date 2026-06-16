extends SubViewportContainer
class_name DollView
# ── Paper-doll de CORPO INTEIRO ao vivo (Ficha do Personagem) [FICHA_PERSONAGEM] ────
# Renderiza o herói INTEIRO vestido com o gear REAL num SubViewport isolado (own_world_3d),
# com giro lento p/ mostrar o equipamento de todos os lados. Reaproveita o pipeline do BustView
# (mesmas peças + partes-base nuas). Re-veste via apply(inv_arr) — sem fetch. [GODOT_PAPERDOLL]
# Gênero-aware [OUTFITS_FEMALE]: base Male/Female + recolor de armadura por raridade [SKIN_RARIDADE].

const CHAR_MALE := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const CHAR_FEMALE := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Female_Rigged.tscn")
const IDLE := "UAL1_Standard/Sword_Idle"
const BASE_PART_SLOT := {"Torso": "ARMOR", "Arms": "GLOVES", "Legs": "PANTS", "Feet": "BOOTS"}
const OutfitsLib := preload("res://Outfits.gd")   # peça por ITEM (Knight/Noble/Ranger/Peasant) [OUTFITS_CLASSE]
const Weapons := preload("res://Weapons.gd")   # arma/escudo (modelos 3D, mesmo da batalha)

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
var _class_id := ""                       # warriorClassId → tema das roupas [OUTFITS_CLASSE]
var _gender := "male"                     # MALE/FEMALE → base + peças [OUTFITS_FEMALE]

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
	_ensure_character(_gender)
	_ready_done = true

# (Re)instancia a base do personagem conforme o gênero (Male/Female compartilham o GeneralSkeleton).
func _ensure_character(gender: String) -> void:
	gender = OutfitsLib._norm_gender(gender)
	if _character != null and _gender == gender:
		return
	_gender = gender
	if _character != null:
		_character.queue_free()
	_body_meshes.clear()
	_props.clear()
	var scene: PackedScene = CHAR_FEMALE if gender == "female" else CHAR_MALE
	_character = scene.instantiate()
	_world.add_child(_character)
	skel = _character.find_child("GeneralSkeleton", true, false)
	var anim: AnimationPlayer = _character.find_child("AnimationPlayer", true, false)
	_collect_meshes(_character, _body_meshes)
	if anim:
		var idle := anim.get_animation(IDLE)
		if idle:
			idle.loop_mode = Animation.LOOP_LINEAR
		anim.play(IDLE)

func _base_path(part: String) -> String:
	return "res://assets/base/Base_%s_%s.gltf" % ["Female" if _gender == "female" else "Male", part]

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
# class_id = warriorClassId (tema das roupas); gender = MALE/FEMALE (base + peças). [OUTFITS_CLASSE][OUTFITS_FEMALE]
func apply(inv_arr: Array, class_id := "", gender := "") -> void:
	if not _ready_done:
		return
	_class_id = class_id
	if gender != "":
		_ensure_character(gender)
	if skel == null:
		return
	for c in skel.get_children():
		if c is MeshInstance3D and c.has_meta("outfit"):
			c.queue_free()
	var equipped: Array = inv_arr.filter(func(it): return it is Dictionary and it.get("equipped") == true)
	var dressed: Array = []
	for it in equipped:
		var ty := str(it.get("type", ""))
		if OutfitsLib.is_armor_slot(ty):
			var path := OutfitsLib.piece_path_item(it, ty, _gender)   # tema do ITEM + gênero
			if path != "" and ResourceLoader.exists(path):
				var sc: PackedScene = load(path)
				if sc:
					_attach(sc, OutfitsLib.theme_for_item(it), int(it.get("rarity", 1)))
					dressed.append(ty)
	for m: MeshInstance3D in _body_meshes:
		m.visible = false
	var head: PackedScene = load(_base_path("Head"))
	if head:
		_attach(head)
	for part in BASE_PART_SLOT:
		if not dressed.has(BASE_PART_SLOT[part]):
			var p: PackedScene = load(_base_path(part))
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

# Anexa a peça ao esqueleto. Se theme!="" e rarity>0, recolore a armadura pela raridade. [SKIN_RARIDADE]
func _attach(scene: PackedScene, theme := "", rarity := 0) -> void:
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
		if theme != "" and rarity > 0:
			OutfitsLib.recolor_mesh(mi, theme, rarity)
	inst.queue_free()

func _collect_meshes(node: Node, out: Array) -> void:
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		_collect_meshes(c, out)
