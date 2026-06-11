extends RefCounted
# ── Helper compartilhado de MONSTROS (bundle Quaternius em res://assets/monsters/) ──
# Auto-escala pelo bounding box (altura-alvo) + pé no chão, e acha a anim idle.
# Usado pelo MonsterViewer (calibração) e, depois, pelo BattleReplay (inimigo PvE).
# Métodos de INSTÂNCIA (sem class_name, igual ao Scenery.gd). Assets gitignored.

const DIR := "res://assets/monsters/"
const TARGET_H := 1.8          # altura-alvo padrão (m) — o auto-fit escala o bicho pra isso
const FACE_OFFSET_DEG := 0.0   # giro global do bundle (ajustar depois de ver no viewer)

# Os 30 do bundle (nomes = nome do arquivo sem .glb). Hard-coded p/ rodar em build exportada
# (DirAccess em res:// só lista no editor).
const NAMES := [
	"Alien", "Alpaking", "Alpaking Evolved", "Armabee", "Armabee Evolved",
	"Birb", "Blue Demon", "Bunny", "Cactoro", "Cat", "Chicken", "Demon",
	"Dino", "Dragon", "Dragon Evolved", "Fish", "Frog", "Ghost", "Ghost Skull",
	"Glub", "Glub Evolved", "Goleling", "Goleling Evolved", "Green Blob",
	"Green Spiky Blob", "Hywirl", "Monkroose", "Mushnub", "Mushnub Evolved",
	"Mushroom King",
]

# Instancia o .glb (self-contained: mesh + rig + anims próprias). null se faltar.
func instance(mname: String) -> Node3D:
	var path := mname if mname.begins_with("res://") else \
			DIR + mname + ("" if mname.to_lower().ends_with(".glb") else ".glb")
	var ps: PackedScene = load(path)
	if ps == null:
		push_warning("monstro não carregou: %s" % path)
		return null
	return ps.instantiate() as Node3D

# AABB combinado de TODAS as malhas, no espaço LOCAL do root (independe da transform do root).
# (root precisa estar na árvore p/ global_transform valer.)
func local_aabb(root: Node3D) -> AABB:
	var acc := AABB()
	var has := false
	var inv := root.global_transform.affine_inverse()
	for vi: VisualInstance3D in _visuals(root):
		var a: AABB = vi.get_aabb()
		var rel: Transform3D = inv * vi.global_transform
		for i in 8:
			var corner := a.position + Vector3(
				a.size.x if (i & 1) else 0.0,
				a.size.y if (i & 2) else 0.0,
				a.size.z if (i & 4) else 0.0)
			var p := rel * corner
			if has:
				acc = acc.expand(p)
			else:
				acc = AABB(p, Vector3.ZERO); has = true
	return acc

# Escala o monstro pra `target_h` e encosta os pés no chão (y=0). Retorna {scale, height}.
func fit(node: Node3D, target_h := TARGET_H) -> Dictionary:
	var box := local_aabb(node)
	var h: float = maxf(box.size.y, 0.001)
	var s := target_h / h
	node.scale = Vector3(s, s, s)
	node.position.y = -box.position.y * s   # min.y * escala → sobe pra encostar no chão
	return {"scale": s, "height": h}

# Nome da anim idle (case-insensitive); senão a 1ª anim. "" se não houver AnimationPlayer/anim.
func find_idle(ap: AnimationPlayer) -> String:
	if ap == null: return ""
	var names := ap.get_animation_list()
	for n in names:
		if "idle" in String(n).to_lower():
			return String(n)
	return String(names[0]) if names.size() > 0 else ""

# Toca a idle (em loop) do monstro instanciado.
func play_idle(node: Node3D) -> void:
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	if ap == null: return
	var idle := find_idle(ap)
	if idle == "": return
	var a := ap.get_animation(idle)
	if a: a.loop_mode = Animation.LOOP_LINEAR
	ap.play(idle)

func _visuals(root: Node) -> Array:
	var out: Array = []
	var stack: Array = [root]
	while not stack.is_empty():
		var n: Node = stack.pop_back()
		if n is VisualInstance3D:
			out.append(n)
		for c in n.get_children():
			stack.append(c)
	return out
