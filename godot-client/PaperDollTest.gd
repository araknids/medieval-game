extends Node3D
# ── Teste de paper-doll (Fase 1) ───────────────────────────────────────────────
# Gruda uma peça de armadura (Ranger Body) no GeneralSkeleton do personagem e toca
# uma animação de andar. Se a armadura deformar JUNTO com o corpo, o esqueleto
# compartilhado funcionou e dá pra mapear o resto das peças (ItemType -> malha).
# Rode esta cena (PaperDollTest.tscn) com F6.
# Plano: docs/PLANO_GODOT_3D.md (Fase 1) [GODOT_PAPERDOLL]

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const RANGER_BODY := preload("res://assets/outfits/ranger/Male_Ranger_Body.gltf")
const WALK := "UAL1_Standard/Walk"

func _ready() -> void:
	_setup_scene()
	var character := CHAR.instantiate()
	add_child(character)
	var skel: Skeleton3D = character.find_child("GeneralSkeleton", true, false)
	var anim: AnimationPlayer = character.find_child("AnimationPlayer", true, false)
	if skel == null:
		push_error("GeneralSkeleton NÃO encontrado no personagem"); return
	print("=== PAPER-DOLL TEST ===")
	print("ossos do personagem (amostra): ", _sample_bones(skel))

	# guarda as malhas ORIGINAIS do corpo (Superhero) antes de grudar a armadura
	var body_meshes: Array = []
	_collect_meshes(skel, body_meshes)

	_attach_outfit(skel, RANGER_BODY)

	# esconde o corpo nu → dá pra ver SE a armadura sozinha acompanha o andar
	for bm: MeshInstance3D in body_meshes:
		bm.visible = false
	print("corpo nu escondido (%d malhas) — vendo só a armadura" % body_meshes.size())

	if anim:
		var w := anim.get_animation(WALK)
		if w: w.loop_mode = Animation.LOOP_LINEAR
		anim.play(WALK)
		print("tocando Walk — observe se a ARMADURA acompanha o corpo")
	else:
		push_warning("AnimationPlayer não encontrado — sem animação")

## Pega todas as MeshInstance3D do outfit e as reparenteia sob o Skeleton3D do
## personagem, repontando o skin pro esqueleto compartilhado (mesmos nomes de osso).
func _attach_outfit(skel: Skeleton3D, scene: PackedScene) -> void:
	var inst := scene.instantiate()
	var meshes: Array = []
	_collect_meshes(inst, meshes)
	print("malhas no outfit: ", meshes.size())
	for mi: MeshInstance3D in meshes:
		var skin := mi.skin
		# DIAGNÓSTICO: os ossos que o skin da armadura espera existem no esqueleto?
		if skin:
			var total := skin.get_bind_count()
			var found := 0
			var sample: Array = []
			for i in total:
				var bn := String(skin.get_bind_name(i))
				if bn != "" and skel.find_bone(bn) >= 0:
					found += 1
				if i < 8:
					sample.append(bn if bn != "" else "(sem nome, idx %d)" % skin.get_bind_bone(i))
			print("  malha '%s': %d/%d ossos do skin batem no esqueleto. amostra binds: %s"
					% [mi.name, found, total, ", ".join(sample)])
		else:
			print("  malha '%s': SEM skin (não é skinada?)" % mi.name)
		mi.get_parent().remove_child(mi)
		mi.owner = null   # [OWNER_FIX] zera o owner antes de reparentar p/ o esqueleto (evita warning "owner inconsistent")
		skel.add_child(mi)
		mi.transform = Transform3D.IDENTITY
		mi.skin = skin
		mi.skeleton = NodePath("..")     # aponta pro Skeleton3D pai (compartilhado)
	inst.queue_free()

func _collect_meshes(node: Node, out: Array) -> void:
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		_collect_meshes(c, out)

func _sample_bones(skel: Skeleton3D) -> String:
	var names: Array = []
	for i in mini(8, skel.get_bone_count()):
		names.append(skel.get_bone_name(i))
	return ", ".join(names)

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
