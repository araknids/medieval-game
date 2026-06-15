extends RefCounted
# ── ARMAS + ESCUDO (modelos 3D reais) ────────────────────────────────────────────
# Carrega os modelos do pack "medieval weapons" (assets/weapons/*.obj) e prende no
# esqueleto do personagem (mão/antebraço). Substitui as armas procedurais antigas.
# Uso: const Weapons := preload("res://Weapons.gd"); var wp := Weapons.new()
#      wp.attach_weapon(node, "sword", rarity, grip);  wp.attach_shield(node, opts)
# Os números (escala/posição/rotação) são tunáveis nas tabelas abaixo. [ARMAS_3D]

# [RARIDADE] cores/brilho por raridade (mantidos p/ uso futuro — emissão por raridade).
const RARITY_TINT := [Color(0.82, 0.84, 0.88), Color(0.45, 0.85, 0.45), Color(0.35, 0.60, 1.0), Color(0.72, 0.40, 0.95), Color(1.0, 0.78, 0.28)]
const RARITY_GLOW := [0.0, 0.5, 1.0, 1.7, 2.6]

const KINDS := ["sword", "greatsword", "axe", "spear", "mace", "shortbow", "longbow", "crossbow"]

const DIR := "res://assets/weapons/"

# tipo visual fino → modelo (arquivo em assets/weapons/, sem extensão). [ARMAS_3D]
# (não há crossbow no pack → usa um arco dourado como stand-in por ora.)
const MODELS := {
	"sword": "Sword", "greatsword": "Claymore", "axe": "Axe", "spear": "Spear",
	"mace": "Hammer_Small", "shortbow": "Bow_Wooden", "longbow": "Bow_Wooden2", "crossbow": "Bow_Golden",
}
const SHIELD_MODEL := "Shield_Heater"

# Transform na mão por tipo: [escala, pos_y, pos_z, rotação(graus)]. pos_x vem do `grip`.
# Modelos: Y-up, lâmina/comprimento no +Y, origem perto do punho/guarda. Ajuste fino aqui. [ARMAS_3D]
const HAND_XF := {
	"sword":      [0.20, 0.05, 0.04, Vector3(0, 0, -90)],
	"greatsword": [0.20, 0.05, 0.04, Vector3(0, 0, -90)],
	"axe":        [0.20, 0.05, 0.04, Vector3(0, 180, -90)],   # cabeça estava virada pra trás → flip Y
	"spear":      [0.18, 0.05, 0.04, Vector3(0, 0, -90)],
	"mace":       [0.22, 0.05, 0.04, Vector3(0, 180, -90)],   # idem
	"shortbow":   [0.24, 0.07, 0.04, Vector3(0, 180, 0)],     # curva estava pra trás → flip Y
	"longbow":    [0.24, 0.07, 0.04, Vector3(0, 180, 0)],
	"crossbow":   [0.24, 0.07, 0.04, Vector3(0, 180, 0)],
}

# Escudo no antebraço: escala + base de posição (somada aos opts slide/push/side) + rotação. [ARMAS_3D]
const SHIELD_SCALE := 0.20
const SHIELD_BASE := Vector3(0.0, 0.10, 0.10)   # x=lado, y=ao longo do braço, z=frente
const SHIELD_ROT := Vector3(0, 0, 0)

# Tipo visual FINO pelo NOME (espelha backend WeaponType.fromName — a API não manda o tipo).
func weapon_kind(item_name: String, category: String) -> String:
	var n := item_name.to_lower()
	if "crossbow" in n or "besta" in n: return "crossbow"
	if "long bow" in n or "longbow" in n or "arco longo" in n: return "longbow"
	if "short bow" in n or "shortbow" in n or "arco curto" in n or "bow" in n or "arco" in n: return "shortbow"
	if category == "RANGED": return "shortbow"
	if "greatsword" in n or "great sword" in n or "two-handed" in n or "montante" in n or "espada longa" in n or "espada de duas" in n: return "greatsword"
	if "axe" in n or "machado" in n or "hatchet" in n: return "axe"
	if "mace" in n or "marreta" in n or "maul" in n or "hammer" in n or "martelo" in n or "club" in n or "clava" in n: return "mace"
	if "spear" in n or "lança" in n or "lanca" in n or "lance" in n or "pike" in n or "halberd" in n: return "spear"
	return "sword"

func is_bow_kind(kind: String) -> bool:
	return kind in ["shortbow", "longbow", "crossbow", "bow"]

# Desenha a arma no esqueleto do `node`. Arco→LeftHand, melee→RightHand (ou force_bone).
# Retorna o BoneAttachment3D (p/ o chamador remover ao reequipar). rarity mantido (sem tint por ora).
func attach_weapon(node: Node3D, kind: String, rarity := 1, grip := 0.10, force_bone := "") -> Node3D:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return null
	var bone := force_bone
	if bone == "":
		bone = "LeftHand" if is_bow_kind(kind) else "RightHand"
	var ba := BoneAttachment3D.new()
	skel.add_child(ba)
	ba.bone_name = bone   # bind DEPOIS de entrar na árvore (resolve o osso, inclusive no SubViewport)
	var xf: Array = HAND_XF.get(kind, HAND_XF["sword"])
	var holder := Node3D.new()
	holder.position = Vector3(grip, float(xf[1]), float(xf[2]))
	holder.rotation_degrees = xf[3]
	ba.add_child(holder)
	var model = _load_model(str(MODELS.get(kind, "Sword")))
	if model != null:
		model.scale = Vector3(xf[0], xf[0], xf[0])
	else:
		model = _fallback(Vector3(0.06, 0.9, 0.06))   # modelo não importado → stick visível
	holder.add_child(model)
	return ba

# Escudo na off-hand (antebraço esquerdo). opts: {slide, push, side, rarity}. Filho do osso → gira junto.
func attach_shield(node: Node3D, opts := {}) -> Node3D:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return null
	var bone := "LeftLowerArm" if skel.find_bone("LeftLowerArm") != -1 else "LeftHand"
	var ba := BoneAttachment3D.new()
	skel.add_child(ba)
	ba.bone_name = bone
	var holder := Node3D.new()
	holder.position = SHIELD_BASE + Vector3(
		float(opts.get("side", 0.0)),
		float(opts.get("slide", 0.0)),
		float(opts.get("push", 0.0)))
	holder.rotation_degrees = SHIELD_ROT
	ba.add_child(holder)
	var model = _load_model(SHIELD_MODEL)
	if model != null:
		model.scale = Vector3(SHIELD_SCALE, SHIELD_SCALE, SHIELD_SCALE)
	else:
		model = _fallback(Vector3(0.5, 0.6, 0.06))
	holder.add_child(model)
	return ba

# Carrega o modelo .glb (cena) instanciado (ou null se ainda não importado pelo Godot).
func _load_model(name: String) -> Node3D:
	var p := DIR + name + ".glb"
	if not ResourceLoader.exists(p):
		return null
	var scene = load(p)
	if scene is PackedScene:
		return scene.instantiate()
	return null

# Caixinha de fallback (modelo ainda não importado) — só p/ não ficar invisível.
func _fallback(size: Vector3) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.5, 0.5, 0.55)
	mi.material_override = m
	return mi
