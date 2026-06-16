extends SubViewportContainer
class_name BustView
# ── Busto 3D AO VIVO do personagem (topbar do Shell) ───────────────────────────────
# Renderiza cabeça+tronco do herói vestido com o gear REAL (via Api autoload) num
# SubViewport ISOLADO (own_world_3d). Reaproveita o pipeline do PaperDollLive. [PLANO_UI_SHELL_GODOT]

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const IDLE := "UAL1_Standard/Sword_Idle"
const BASE_HEAD := "res://assets/base/Base_Male_Head.gltf"
const BASE_PART := {
	"res://assets/base/Base_Male_Torso.gltf": "ARMOR",
	"res://assets/base/Base_Male_Arms.gltf":  "GLOVES",
	"res://assets/base/Base_Male_Legs.gltf":  "PANTS",
	"res://assets/base/Base_Male_Feet.gltf":  "BOOTS",
}
const OutfitsLib := preload("res://Outfits.gd")   # peça por CLASSE [OUTFITS_CLASSE]

var skel: Skeleton3D
var _world: Node3D
var _body_meshes: Array = []
var _ready_done := false
var _class_id := ""                       # warriorClassId → tema das roupas [OUTFITS_CLASSE]

func _ready() -> void:
	stretch = true
	var sv := SubViewport.new()
	sv.own_world_3d = true                 # mundo isolado (não vaza pro World3D do jogo) [own_world_3d]
	sv.transparent_bg = true
	sv.msaa_3d = Viewport.MSAA_2X
	add_child(sv)
	_world = Node3D.new()
	sv.add_child(_world)
	var cam := Camera3D.new()               # enquadra OMBROS→CABEÇA (retrato): sobe o quadro p/ tirar a cintura
	cam.position = Vector3(0.0, 1.55, 1.15)   # +y sobe o enquadramento (antes 1.42 = pegava a cintura)
	cam.rotation_degrees = Vector3(-4, 0, 0)  # tilt menor → não volta a mirar no peito
	cam.fov = 30.0
	_world.add_child(cam)
	var key := DirectionalLight3D.new()
	key.rotation_degrees = Vector3(-35, -40, 0)
	key.light_energy = 1.4
	_world.add_child(key)
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0, 0, 0, 0)
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.55, 0.52, 0.6)
	env.ambient_light_energy = 1.0
	var we := WorldEnvironment.new()
	we.environment = env
	_world.add_child(we)
	var character := CHAR.instantiate()
	_world.add_child(character)
	skel = character.find_child("GeneralSkeleton", true, false)
	var anim: AnimationPlayer = character.find_child("AnimationPlayer", true, false)
	_collect_meshes(character, _body_meshes)
	if anim:
		var idle := anim.get_animation(IDLE)
		if idle:
			idle.loop_mode = Animation.LOOP_LINEAR
		anim.play(IDLE)
	_ready_done = true
	await refresh()

# Busca o inventário (+ classe) e re-veste (standalone). O Shell usa apply() direto p/ não re-buscar.
func refresh() -> void:
	if not _ready_done or skel == null:
		return
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var cls := _class_id
	var w = await api.get_warrior()
	if w.get("ok") and w.get("json") is Dictionary:
		cls = str(w["json"].get("warriorClassId", cls))
	var inv = await api.get_inventory()
	if inv.get("ok") and inv.get("json") is Array:
		apply(inv["json"], cls)

# Re-veste o busto a partir de uma lista de inventário já carregada (sem fetch).
# class_id = warriorClassId → tema das roupas (Knight/Noble/Ranger/Peasant). [OUTFITS_CLASSE]
func apply(inv_arr: Array, class_id := "") -> void:
	if not _ready_done or skel == null:
		return
	if class_id != "":
		_class_id = class_id
	# tira roupas anteriores (mantém só os ossos/base); re-veste do zero
	for c in skel.get_children():
		if c is MeshInstance3D and c.has_meta("outfit"):
			c.queue_free()
	var equipped: Array = inv_arr.filter(func(it): return it is Dictionary and it.get("equipped") == true)
	var dressed: Array = []
	for it in equipped:
		var ty := str(it.get("type", ""))
		if OutfitsLib.is_armor_slot(ty):
			var path := OutfitsLib.piece_path_item(it, ty)   # tema do ITEM [OUTFITS_CLASSE]
			if path != "" and ResourceLoader.exists(path):
				var sc: PackedScene = load(path)
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
		mi.set_meta("outfit", true)   # marca p/ poder re-vestir
	inst.queue_free()

func _collect_meshes(node: Node, out: Array) -> void:
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		_collect_meshes(c, out)
