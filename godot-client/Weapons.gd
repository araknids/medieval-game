extends RefCounted
# ── Construtor de ARMAS + ESCUDO procedurais (compartilhado) ─────────────────────
# Uso: const Weapons := preload("res://Weapons.gd"); var wp := Weapons.new()
#      wp.attach_weapon(node, "greatsword", rarity, grip);  wp.attach_shield(node, opts)
# Métodos de INSTÂNCIA (igual Monsters.gd/Scenery.gd). Espelha o que estava no BattleReplay.

# [RARIDADE] cor + brilho do metal pela raridade do item (1 comum → 5 lendário).
const RARITY_TINT := [Color(0.82, 0.84, 0.88), Color(0.45, 0.85, 0.45), Color(0.35, 0.60, 1.0), Color(0.72, 0.40, 0.95), Color(1.0, 0.78, 0.28)]
const RARITY_GLOW := [0.0, 0.5, 1.0, 1.7, 2.6]

const KINDS := ["sword", "greatsword", "axe", "spear", "mace", "shortbow", "longbow", "crossbow"]

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

# Desenha a arma no esqueleto do `node` (procura GeneralSkeleton). Arco/besta na LeftHand;
# melee na RightHand (rot -90; +Y local = direção da arma). rarity 1-5 tinge/brilha o metal.
func attach_weapon(node: Node3D, kind: String, rarity := 1, grip := 0.10) -> void:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return
	var r := clampi(rarity, 1, 5)
	var steel := RARITY_TINT[r - 1] as Color   # r=1 → aço normal
	var ge := RARITY_GLOW[r - 1] as float       # emissão (0 no comum)
	var ba := BoneAttachment3D.new()
	if is_bow_kind(kind):
		ba.bone_name = "LeftHand"
		skel.add_child(ba)
		_attach_bow(ba, kind, steel, ge)
		return
	ba.bone_name = "RightHand"
	skel.add_child(ba)
	var holder := Node3D.new()
	holder.position = Vector3(grip, 0.05, 0.04)   # grip = ao longo da mão; MENOR = cabo mais pra dentro
	holder.rotation_degrees = Vector3(0, 0, -90)
	ba.add_child(holder)
	var wood := Color(0.35, 0.22, 0.12)
	match kind:
		"greatsword":
			_box(holder, Vector3(0.028, 0.82, 0.10), Vector3(0, 0.54, 0), steel, 0.7, steel, ge)      # lâmina longa
			_box(holder, Vector3(0.07, 0.04, 0.28),  Vector3(0, 0.10, 0), Color(0.28, 0.22, 0.14), 0.3)  # guarda larga
			_box(holder, Vector3(0.032, 0.22, 0.032),Vector3(0, -0.04, 0), wood, 0.1)              # cabo (2 mãos)
			_box(holder, Vector3(0.06, 0.06, 0.06),  Vector3(0, -0.18, 0), Color(0.70, 0.60, 0.20), 0.5)  # pomo
		"axe":
			_box(holder, Vector3(0.028, 0.62, 0.028), Vector3(0, 0.20, 0), wood, 0.1)        # cabo longo
			_box(holder, Vector3(0.02, 0.16, 0.17),   Vector3(0, 0.46, 0.07), steel, 0.7, steel, ge)  # lâmina
		"spear":
			_box(holder, Vector3(0.024, 0.95, 0.024), Vector3(0, 0.30, 0), wood, 0.1)         # haste
			_box(holder, Vector3(0.04, 0.16, 0.04),   Vector3(0, 0.82, 0), steel, 0.7, steel, ge)  # ponta
		"mace":
			_box(holder, Vector3(0.03, 0.42, 0.03),   Vector3(0, 0.12, 0), wood, 0.1)         # cabo
			_sphere(holder, 0.075, Vector3(0, 0.38, 0), steel, 0.6, steel, ge)                # cabeça
		_:  # sword
			_box(holder, Vector3(0.022, 0.5, 0.075),  Vector3(0,  0.34, 0), steel, 0.7, steel, ge)    # lâmina
			_box(holder, Vector3(0.05, 0.035, 0.20),  Vector3(0,  0.07, 0), Color(0.28, 0.22, 0.14), 0.3)  # guarda
			_box(holder, Vector3(0.028, 0.13, 0.028), Vector3(0, -0.02, 0), wood, 0.1)             # cabo
			_box(holder, Vector3(0.05, 0.05, 0.05),   Vector3(0, -0.10, 0), Color(0.70, 0.60, 0.20), 0.5)  # pomo

func _attach_bow(ba: Node3D, kind: String, glow_col := Color.WHITE, ge := 0.0) -> void:
	var wood := Color(0.45, 0.30, 0.16)
	var cord := glow_col if ge > 0.0 else Color(0.85, 0.82, 0.70)
	if kind == "crossbow":
		_box(ba, Vector3(0.035, 0.05, 0.40), Vector3(0.10, 0.06, 0.10), wood, 0.1)                  # coronha (madeira)
		_box(ba, Vector3(0.40, 0.03, 0.03),  Vector3(0.10, 0.08, 0.26), glow_col, 0.6, glow_col, ge)  # braço metálico (brilha)
		_box(ba, Vector3(0.35, 0.006, 0.006),Vector3(0.10, 0.08, 0.25), cord, 0.0, glow_col, ge)    # corda
		return
	var h := 0.95 if kind == "longbow" else 0.55
	_box(ba, Vector3(0.03, h, 0.03),        Vector3(0.10, 0.07, 0.04), wood, 0.1, glow_col, ge * 0.4)  # corpo
	_box(ba, Vector3(0.006, h * 0.95, 0.006), Vector3(0.10, 0.07, -0.02), cord, 0.0, glow_col, ge)   # corda

# Escudo (heater) na off-hand. [Fable] holder top_level + realinhado todo frame (skeleton_updated):
# POSIÇÃO ancora no antebraço (roll-safe), FACE pra frente (rumo ao centro), UP = mundo.
# opts: {slide, push, side, up, flip}.
func attach_shield(node: Node3D, opts := {}) -> void:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return
	var bone := "LeftLowerArm" if skel.find_bone("LeftLowerArm") != -1 else "LeftHand"
	var ba := BoneAttachment3D.new()
	ba.bone_name = bone
	skel.add_child(ba)
	var holder := Node3D.new()
	holder.top_level = true
	ba.add_child(holder)
	# [RARIDADE] borda/umbo (metal) tingem+brilham pela raridade; corpo fica madeira.
	var r := clampi(int(opts.get("rarity", 1)), 1, 5)
	var rim := RARITY_TINT[r - 1] as Color   # r=1 → aço normal
	var ge := RARITY_GLOW[r - 1] as float
	var wood := Color(0.40, 0.26, 0.14)
	_box(holder, Vector3(0.34, 0.42, 0.04),  Vector3(0, 0, 0), wood, 0.1)                # corpo (madeira)
	_box(holder, Vector3(0.36, 0.045, 0.05), Vector3(0, 0.21, 0), rim, 0.6, rim, ge)     # borda topo
	_box(holder, Vector3(0.36, 0.045, 0.05), Vector3(0, -0.21, 0), rim, 0.6, rim, ge)    # borda base
	_box(holder, Vector3(0.045, 0.42, 0.05), Vector3(0.17, 0, 0), rim, 0.6, rim, ge)     # borda direita
	_box(holder, Vector3(0.045, 0.42, 0.05), Vector3(-0.17, 0, 0), rim, 0.6, rim, ge)    # borda esquerda
	_sphere(holder, 0.055, Vector3(0, 0, 0.04), rim, 0.6, rim, ge)                       # umbo (frente, brilha)
	var s_slide := float(opts.get("slide", 0.13))
	var s_push := float(opts.get("push", 0.18))
	var s_side := float(opts.get("side", 0.0))
	var s_up := float(opts.get("up", 0.02))
	var flip := bool(opts.get("flip", false))
	var fixed_fwd = opts.get("forward", null)   # Vector3 fixo (viewer) OU null = calcula rumo ao centro (batalha)
	skel.skeleton_updated.connect(func() -> void:
		if not is_instance_valid(holder) or not is_instance_valid(node): return
		var fwd: Vector3
		if fixed_fwd != null:
			fwd = fixed_fwd
		else:
			fwd = Vector3(-signf(node.global_position.x), 0.0, 0.0)   # rumo ao centro/inimigo
			if fwd.length() < 0.01: fwd = Vector3.LEFT
		if flip: fwd = -fwd
		var rx := Vector3.UP.cross(fwd).normalized()
		var ry := fwd.cross(rx)
		var along := ba.global_transform.basis.y.normalized()
		var origin := ba.global_position + along * s_slide + fwd * s_push + rx * s_side + ry * s_up
		holder.global_transform = Transform3D(Basis(rx, ry, fwd), origin))

func _box(parent: Node, size: Vector3, pos: Vector3, col: Color, metallic: float, emit := Color.BLACK, emit_e := 0.0) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	mi.position = pos
	var m := StandardMaterial3D.new()
	m.albedo_color = col
	m.metallic = metallic
	if emit_e > 0.0:
		m.emission_enabled = true
		m.emission = emit
		m.emission_energy_multiplier = emit_e
	mi.material_override = m
	parent.add_child(mi)

func _sphere(parent: Node, radius: float, pos: Vector3, col: Color, metallic: float, emit := Color.BLACK, emit_e := 0.0) -> void:
	var mi := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = radius
	sm.height = radius * 2.0
	mi.mesh = sm
	mi.position = pos
	var m := StandardMaterial3D.new()
	m.albedo_color = col
	m.metallic = metallic
	if emit_e > 0.0:
		m.emission_enabled = true
		m.emission = emit
		m.emission_energy_multiplier = emit_e
	mi.material_override = m
	parent.add_child(mi)
