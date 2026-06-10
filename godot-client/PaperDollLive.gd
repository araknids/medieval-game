extends Node3D
# ── Paper-doll AO VIVO (Fase 2) — veste o boneco com o equip REAL do jogador ────
# Faz login no backend, lê GET /api/inventory e, para cada item EQUIPADO, gruda a
# peça Ranger do slot correspondente no GeneralSkeleton. (Só temos o set Ranger por
# ora → qualquer ARMOR vira a túnica Ranger, qualquer HELMET vira o capuz, etc.)
# Rode esta cena (PaperDollLive.tscn) com F6. Configure usuário/senha no Inspector.
# Plano: docs/PLANO_GODOT_3D.md (Fase 2) [GODOT_PAPERDOLL]

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const IDLE := "UAL1_Standard/Sword_Idle"

## Credenciais de teste. O admin seed é "adm"/"adm123". Troque pelo seu login.
@export var username := "adm"
@export var password := "adm123"
## Deixe vazio p/ usar a URL padrão do BackendClient (Railway). "http://localhost:8080" no dev local.
@export var base_url_override := ""

# ItemType (backend) -> cena da peça Ranger. Slots sem peça (WEAPON/RING/NECKLACE/SHIELD) são ignorados.
const PIECES := {
	"ARMOR":    "res://assets/outfits/ranger/Male_Ranger_Body.gltf",
	"PANTS":    "res://assets/outfits/ranger/Male_Ranger_Legs.gltf",
	"BOOTS":    "res://assets/outfits/ranger/Male_Ranger_Feet_Boots.gltf",
	"GLOVES":   "res://assets/outfits/ranger/Male_Ranger_Arms.gltf",
	"HELMET":   "res://assets/outfits/ranger/Male_Ranger_Head_Hood.gltf",
	"SHOULDER": "res://assets/outfits/ranger/Male_Ranger_Acc_Pauldron.gltf",
}

var skel: Skeleton3D

func _ready() -> void:
	_setup_scene()
	var character := CHAR.instantiate()
	add_child(character)
	skel = character.find_child("GeneralSkeleton", true, false)
	var anim: AnimationPlayer = character.find_child("AnimationPlayer", true, false)
	if skel == null:
		push_error("GeneralSkeleton não encontrado no personagem"); return
	if anim:
		var idle := anim.get_animation(IDLE)
		if idle: idle.loop_mode = Animation.LOOP_LINEAR
		anim.play(IDLE)
	await _load_and_dress()

func _load_and_dress() -> void:
	var client := BackendClient.new()
	if base_url_override != "":
		client.base_url = base_url_override
	add_child(client)
	print("=== PAPER-DOLL LIVE === login '%s' @ %s" % [username, client.base_url])

	var lr = await client.login(username, password)
	if not lr.get("ok"):
		print(">>> LOGIN FALHOU — status %s | erro: %s | raw: %s" % [lr.get("status"), lr.get("error", ""), lr.get("raw", "")])
		print(">>> Dica: 'adm/adm123' só vale no DEV local. Em PROD use SUA conta (mude Username/Password no Inspector do nó PaperDollLive).")
		return
	print("login OK — buscando inventário…")

	var inv = await client.get_inventory()
	if not inv.get("ok") or not (inv.get("json") is Array):
		print(">>> INVENTORY FALHOU — status %s | raw: %s" % [inv.get("status"), inv.get("raw", "")])
		return

	var items: Array = inv["json"]
	var equipped: Array = items.filter(func(it): return it is Dictionary and it.get("equipped") == true)
	print("itens no inventário: %d · equipados: %d" % [items.size(), equipped.size()])

	var dressed := 0
	for it in equipped:
		var ty := str(it.get("type", ""))
		if PIECES.has(ty):
			var scene: PackedScene = load(PIECES[ty])
			if scene:
				_attach_outfit(scene)
				dressed += 1
				print("  ✚ %s [%s] → %s" % [it.get("name", "?"), ty, String(PIECES[ty]).get_file()])
			else:
				push_warning("  peça não carregou: %s" % PIECES[ty])
		else:
			print("  – %s [%s]: sem peça de armadura mapeada (arma/anel/colar/escudo)" % [it.get("name", "?"), ty])

	if dressed == 0:
		print(">>> NENHUMA peça vestida. Equipe armadura (Elmo/Peito/Calça/Luvas/Botas/Ombreira) na web e rode de novo.")
	else:
		print(">>> %d peça(s) vestida(s) do equip real. " % dressed)

## Reparenteia as MeshInstance3D do outfit sob o Skeleton3D do personagem, mantendo o skin
## (binds por NOME de osso → o esqueleto compartilhado anima a peça junto). [GODOT_PAPERDOLL]
func _attach_outfit(scene: PackedScene) -> void:
	var inst := scene.instantiate()
	var meshes: Array = []
	_collect_meshes(inst, meshes)
	for mi: MeshInstance3D in meshes:
		var skin := mi.skin
		mi.get_parent().remove_child(mi)
		skel.add_child(mi)
		mi.transform = Transform3D.IDENTITY
		mi.skin = skin
		mi.skeleton = NodePath("..")   # aponta pro Skeleton3D pai (compartilhado)
	inst.queue_free()

func _collect_meshes(node: Node, out: Array) -> void:
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		_collect_meshes(c, out)

func _setup_scene() -> void:
	var cam := Camera3D.new()
	cam.position = Vector3(0.0, 1.1, 3.2)
	cam.rotation_degrees = Vector3(-8, 0, 0)
	add_child(cam)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, -30, 0)
	sun.light_energy = 1.2
	add_child(sun)
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.17, 0.17, 0.22)
	env.ambient_light_energy = 0.9
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
