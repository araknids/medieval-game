extends Node3D
# ── Dois guerreiros duelando no fundo do menu (castelo) — pura decoração. [MENU_DUEL] ──
# Reusa o rig Quaternius (Male_rigged) + as peças Ranger (paper-doll) + a espada procedural
# (Weapons) + as anims de espada do BattleReplay, e roda um loop simples: um ataca, o outro
# reage, ambos voltam pro idle. Defensivo: se qualquer asset faltar, só não aparece (o menu
# continua funcionando). Personagem 3D: docs/PLANO_GODOT_3D.md

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const CHAR_FEMALE := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Female_Rigged.tscn")  # [OUTFITS_FEMALE]
const OutfitsLib := preload("res://Outfits.gd")   # sets novos (tema/gênero/cor/variante) — sorteados a cada entrada
const Weapons := preload("res://Weapons.gd")
const UAL2_PATH := "res://addons/quaternius_ik_rigged/UAL2_Standard.glb"
const LIB := "UAL1_Standard/"
const LIB2 := "UAL2_Standard/"
const IDLE := LIB + "Sword_Idle"
const HURTS := [LIB + "Hit_Chest", LIB + "Hit_Head"]   # reação variada (peito/cabeça)
const ROLL := LIB + "Roll"                              # esquiva ocasional (in-place, sem sangue)
const DEATH := LIB + "Death01"                          # [MAPA_TORRE] pose de morte (corpos tombados do fundo)
# golpes variados: 3 da UAL2 (A/B/combo) + o Sword_Attack da UAL1
const ATTACKS := [LIB2 + "Sword_Regular_A", LIB2 + "Sword_Regular_B", LIB2 + "Sword_Regular_Combo", LIB + "Sword_Attack"]
const SHOOT := LIB + "Spell_Simple_Shoot"   # arco/ranged: anim de TIRO (não golpe de espada) [MENU_DUEL]
const BLEND := 0.12
# Arma aleatória dos lutadores — só MELEE (as anims do duelo são de espada). [MENU_DUEL]
const MELEE_KINDS := ["sword", "greatsword", "axe", "spear", "mace"]
const WALK := LIB + "Walk"      # andar (reverso = recuar) no kiting do arqueiro [MENU_DUEL]
const RUN := LIB + "Jog_Fwd"    # [MAPA_TORRE] corrida — o inimigo do cerco vem CORRENDO do portão
# Kiting (arco × melee): o arqueiro recua atirando; quando o melee COLA, ele rola ATRAVÉS pro outro lado.
const KITE_EDGE := 3.6          # |x| máx no pátio (encosta na "parede" e clampa)
const KITE_RANGE := 1.45        # distância que o melee tenta fechar do arqueiro
const DODGE_GAP := 1.75         # melee chegou a isto → arqueiro ROLA pro outro lado (atravessa) p/ reganhar distância
const KITE_MELEE_SPEED := 2.0   # perseguição do melee
const KITE_ARCHER_SPEED := 2.4  # recuo do arqueiro (kita > melee; as pausas de tiro deixam o melee colar → dodge)
const KITE_LAND := 3.0          # onde o arqueiro POUSA após atravessar o melee (clampado ao pátio)
const HOP_SPEED := 5.5          # velocidade horizontal do rolê de esquiva (relocaliza DE VERDADE, sem tween)

# Peças Ranger por slot (mesmo set do PaperDollLive) + a cabeça-base (rosto).
const BASE_HEAD := "res://assets/base/Base_Male_Head.gltf"
const RANGER := [
	"res://assets/outfits/ranger/Male_Ranger_Body.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Legs.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Feet_Boots.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Arms.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Head_Hood.gltf",
	"res://assets/outfits/ranger/Male_Ranger_Acc_Pauldron.gltf",
]

# Posições/porte no pátio (fáceis de ajustar). z+ = mais perto da câmera (castelo: cam em z=18).
const POS_L := Vector3(-1.2, 0.0, 4.0)
const POS_R := Vector3(1.2, 0.0, 4.0)
const SCALE := 1.2

# [MAPA_TORRE] CERCO INFINITO (só no cursed_tower): inimigos sombrios saem do PORTÃO, caminham até o
# herói, morrem em 5-10 golpes e AFUNDAM no chão (sem acumular). O herói nunca morre.
const GATE_SPAWN := Vector3(11.6, 0.0, 2.0)   # boca do portão da fortaleza
const SIEGE_RUN_SPEED := 5.4                   # corre (não anda) até o herói
var siege_mode := false                        # ligado pelo MenuFx
var _siege_timer := 0.0
var _siege_respawn := 0.0

var _fighters: Array = []   # [{node, anim}]
var _atk := 0
var _timer := 1.0
var _rng := RandomNumberGenerator.new()
var _gen := 0   # geração do setup() — aborta um setup antigo se um novo começar (relogin rápido)

func _ready() -> void:
	_rng.randomize()   # cada abertura = coreografia + armas aleatórias diferentes [MENU_DUEL]
	# luz quente de preenchimento (destaca o duelo em qualquer cenário, mesmo os noturnos)
	var key := OmniLight3D.new()
	key.light_color = Color(1.0, 0.86, 0.62)
	key.light_energy = 2.6
	key.omni_range = 13.0
	key.position = Vector3(0, 4.0, 7.5)
	add_child(key)
	# os lutadores são montados por setup() — o App chama no boot e a cada login/logout.

# (Re)monta os 2 lutadores conforme o login. LOGADO: esquerda = você (sua arma equipada real);
# senão aleatório. DIREITA: sempre um oponente aleatório. Idempotente (limpa antes de remontar).
func setup() -> void:
	_gen += 1
	var my := _gen
	_clear_fighters()
	var left := {}
	if Api.token != "":
		left = await _player_loadout()   # gear REAL (arma + inventário + gênero) do jogador
		if my != _gen:                   # um setup mais novo começou → aborta este
			return
	if left.has("inv"):                  # logado: ESQUERDA = você, com seus itens reais
		_spawn(POS_L, 90.0, str(left["kind"]), int(left["rarity"]), left)
	else:                                # deslogado / falha → aleatório
		_spawn(POS_L, 90.0, _rand_kind(), _rng.randi_range(1, 5))
	if siege_mode:                       # [MAPA_TORRE] cerco: 1º inimigo sai do portão (loop infinito)
		_siege_respawn = 0.0
		_spawn_siege_enemy()
	else:
		_spawn(POS_R, -90.0, _rand_kind(), _rng.randi_range(1, 5))   # oponente sempre aleatório

# Lê /api/warrior (gênero) + /api/inventory (equip REAL) → o herói do fundo usa EXATAMENTE o seu gear.
# Devolve {kind, rarity, inv, gender} (inv = lista do inventário p/ vestir por item) ou {} se falhar.
func _player_loadout() -> Dictionary:
	var gender := "male"
	var w = await Api.get_warrior()
	if w.get("ok") and w.get("json") is Dictionary:
		gender = str(w["json"].get("gender", "male")).to_lower()
	var r = await Api.get_inventory()
	if not (r.get("ok") and r.get("json") is Array):
		return {}
	var inv: Array = r["json"]
	var kind := "sword"
	var rar := 1
	for it in inv:                                  # arma equipada (tipo + raridade) p/ a mão
		if it is Dictionary and it.get("equipped") == true and str(it.get("type", "")) == "WEAPON":
			kind = Weapons.new().weapon_kind(str(it.get("name", "")), str(it.get("weaponCategory", "")))
			rar = int(it.get("rarity", 1))
			break
	return {"kind": kind, "rarity": rar, "inv": inv, "gender": gender}

func _rand_kind() -> String:
	return MELEE_KINDS[_rng.randi() % MELEE_KINDS.size()]

# ── [MAPA_TORRE] CERCO INFINITO ──────────────────────────────────────────────────
# Inimigo SOMBRIO (tema knight escurecido) sai do PORTÃO e caminha até o herói. (look_override
# força o tema; _tint_dark escurece pra "sombra da fortaleza".)
func _spawn_siege_enemy() -> void:
	var gender := "female" if _rng.randi() % 5 == 0 else "male"
	var rar := _rng.randi_range(1, 2)
	var look := {"theme": "knight", "gender": gender, "rarity": rar, "seed": "siege_%d" % _rng.randi()}
	var before := _fighters.size()
	_spawn(GATE_SPAWN, -90.0, _rand_kind(), rar, {}, look)   # sai do portão, encara -X (o herói)
	if _fighters.size() <= before:                           # rig não carregou → não corrompe o herói
		return
	var e: Dictionary = _fighters[_fighters.size() - 1]
	e["sstate"] = "walk"
	e["shits"] = 0
	e["skill_at"] = _rng.randi_range(5, 10)
	_tint_dark(e["node"])

# Máquina de estados do cerco: walk (caminha do portão) → fight (apanha do herói) → dying (morre + afunda).
func _siege_step(dt: float) -> void:
	if _fighters.is_empty() or not is_instance_valid(_fighters[0].get("node")):
		return
	if _fighters.size() < 2:                 # entre inimigos: espera o próximo sair do portão
		_siege_respawn -= dt
		if _siege_respawn <= 0.0:
			_siege_respawn = 1.0             # piso (evita spam por frame se o spawn falhar)
			_spawn_siege_enemy()
		return
	var hero: Dictionary = _fighters[0]
	var e: Dictionary = _fighters[1]
	var en: Node3D = e["node"]
	if not is_instance_valid(en):
		return
	var st: String = str(e.get("sstate", "walk"))
	if st == "walk":
		en.position = en.position.move_toward(POS_R, SIEGE_RUN_SPEED * dt)
		var ap_e: AnimationPlayer = e["anim"]
		if ap_e and ap_e.current_animation != RUN:
			var ra := ap_e.get_animation(RUN)
			if ra:
				ra.loop_mode = Animation.LOOP_LINEAR   # corrida em loop (senão "trava" a cada ciclo)
			ap_e.play(RUN, BLEND)
		if en.position.distance_to(POS_R) < 0.2:
			en.position = POS_R
			e["sstate"] = "fight"
			_siege_timer = 0.5
			if ap_e:
				ap_e.play(IDLE, BLEND)
	elif st == "fight":
		_siege_timer -= dt
		if _siege_timer <= 0.0:
			_siege_timer = _rng.randf_range(0.55, 0.9)
			_siege_hero_strike(hero, e)
			e["shits"] = int(e.get("shits", 0)) + 1
			if int(e["shits"]) >= int(e.get("skill_at", 7)):
				e["sstate"] = "dying"
				_siege_kill(e)
			elif _rng.randf() < 0.3:
				_siege_enemy_jab(e, hero)

# Herói golpeia o inimigo (sempre): investe + ataque (ou tiro se arco) → inimigo reage + sangra.
func _siege_hero_strike(hero: Dictionary, e: Dictionary) -> void:
	var ap_h: AnimationPlayer = hero["anim"]
	var hn: Node3D = hero["node"]
	var en: Node3D = e["node"]
	if not (is_instance_valid(hn) and is_instance_valid(en)):
		return
	var ranged: bool = hero.get("ranged", false)
	if ap_h:
		var clip: String = SHOOT if ranged else ATTACKS[_rng.randi() % ATTACKS.size()]
		var an := ap_h.get_animation(clip)
		if an:
			an.loop_mode = Animation.LOOP_NONE
		ap_h.play(clip, BLEND)
	var dirx := signf(en.position.x - hn.position.x)
	if ranged:
		_arrow(hn, en)
	else:
		var home := hn.position
		var tw := hn.create_tween()
		tw.tween_property(hn, "position", home + Vector3(dirx * 0.38, 0, 0), 0.16).set_trans(Tween.TRANS_SINE)
		tw.tween_interval(0.10)
		tw.tween_property(hn, "position", home, 0.30).set_trans(Tween.TRANS_SINE)
	var ap_e: AnimationPlayer = e["anim"]
	get_tree().create_timer(0.26).timeout.connect(func() -> void:
		if not is_instance_valid(en) or str(e.get("sstate", "")) == "dying":
			return   # se já morreu nesse golpe, deixa a animação de morte tocar (não reage)
		if ap_e:
			var react: String = HURTS[_rng.randi() % HURTS.size()]
			var h := ap_e.get_animation(react)
			if h:
				h.loop_mode = Animation.LOOP_NONE
			ap_e.play(react, BLEND)
		_blood(en.global_position + Vector3(0, 1.15, 0), Vector3(dirx, 0, 0)))

# Inimigo dá um bote no herói de vez em quando — herói reage, mas NUNCA morre (sem sangue forte).
func _siege_enemy_jab(e: Dictionary, hero: Dictionary) -> void:
	var ap_e: AnimationPlayer = e["anim"]
	var ap_h: AnimationPlayer = hero["anim"]
	var en: Node3D = e["node"]
	var hn: Node3D = hero["node"]
	if not (is_instance_valid(en) and is_instance_valid(hn)):
		return
	if ap_e:
		var clip: String = ATTACKS[_rng.randi() % ATTACKS.size()]
		var an := ap_e.get_animation(clip)
		if an:
			an.loop_mode = Animation.LOOP_NONE
		ap_e.play(clip, BLEND)
	get_tree().create_timer(0.26).timeout.connect(func() -> void:
		if not is_instance_valid(hn):
			return
		if ap_h:
			var react: String = HURTS[_rng.randi() % HURTS.size()]
			var h := ap_h.get_animation(react)
			if h:
				h.loop_mode = Animation.LOOP_NONE
			ap_h.play(react, BLEND))

# Inimigo MORRE: sai do alvo, toca Death (congela deitado), sangra e AFUNDA no chão (desvanece). Marca
# `dodging=true` p/ o animation_finished do _spawn NÃO levantar o corpo de volta pro idle.
func _siege_kill(e: Dictionary) -> void:
	var en: Node3D = e["node"]
	var ap: AnimationPlayer = e["anim"]
	e["dodging"] = true
	_fighters.erase(e)
	_siege_respawn = 3.0
	if not is_instance_valid(en):
		return
	if ap:
		var d := ap.get_animation(DEATH)
		if d:
			d.loop_mode = Animation.LOOP_NONE
		ap.play(DEATH, BLEND)
	_blood(en.global_position + Vector3(0, 1.1, 0), Vector3(-1, 0, 0))
	var node := en
	get_tree().create_timer(2.6).timeout.connect(func() -> void:
		if is_instance_valid(node):
			var tw := node.create_tween()
			tw.tween_property(node, "position:y", node.position.y - 1.5, 1.4)   # afunda no chão amaldiçoado
			tw.tween_callback(node.queue_free))

# Escurece todas as malhas visíveis de um boneco (soldado SOMBRIO da fortaleza). Duplica o material
# por instância (sem vazar pros outros).
func _tint_dark(node: Node3D) -> void:
	var meshes: Array = []
	_collect_meshes(node, meshes)
	for mi: MeshInstance3D in meshes:
		if not mi.visible or mi.mesh == null:
			continue
		for s in mi.mesh.get_surface_count():
			var mat = mi.get_active_material(s)
			if mat is BaseMaterial3D:
				var m: BaseMaterial3D = mat.duplicate()
				m.albedo_color = Color(0.42, 0.42, 0.5)   # sombra da fortaleza
				mi.set_surface_override_material(s, m)

# [MAPA_TORRE] Corpos TOMBADOS em FULL-PLATE (Quaternius, tema knight) — decoração SÓ do fundo do menu
# (não entra na batalha real). Chamado pelo MenuFx no cenário cursed_tower. Pose de morte (Death01)
# congelada no último frame. Não entram em `_fighters` → o setup()/login não os remove.
func spawn_fallen(positions: Array) -> void:
	for i in positions.size():
		var pos: Vector3 = positions[i]
		var gender := "female" if _rng.randi() % 4 == 0 else "male"   # maioria homem, 1/4 mulher
		var node := (CHAR_FEMALE if gender == "female" else CHAR).instantiate()
		if node == null:
			continue
		add_child(node)
		node.position = pos
		node.rotation_degrees = Vector3(0, _rng.randf_range(0, 360), 0)
		node.scale = Vector3.ONE * SCALE
		var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
		var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
		if skel:
			_dress(node, skel, {"theme": "knight", "gender": gender, "rarity": _rng.randi_range(1, 3), "seed": "corpse_%d" % i})
		if ap:
			var d := ap.get_animation(DEATH)
			if d:
				d.loop_mode = Animation.LOOP_NONE
				ap.play(DEATH)
				ap.seek(maxf(0.0, d.length - 0.05), true)   # PRÓXIMO do fim (deitado) e descansa lá (LOOP_NONE); sem pause() (que sumia o corpo)

func _clear_fighters() -> void:
	for f in _fighters:
		var n = f.get("node")
		if is_instance_valid(n):
			n.queue_free()
	_fighters.clear()
	_atk = 0
	_timer = 1.0

# Look SORTEADO (tema/gênero/cor/variante) — varia a cada setup() (= a cada entrada no jogo). [OUTFITS]
func _rand_look() -> Dictionary:
	var themes: Array = OutfitsLib.THEME_ORDER
	return {
		"theme":  str(themes[_rng.randi() % themes.size()]),
		"gender": "female" if _rng.randi() % 2 == 1 else "male",
		"rarity": _rng.randi_range(1, 5),
		"seed":   "menu_%d" % _rng.randi(),   # varia a peça-variante (elmo/ombreira/peitoral)
	}

func _spawn(pos: Vector3, yaw_deg: float, weapon_kind: String, weapon_rarity: int, player := {}, look_override := {}) -> void:
	var is_player: bool = player.has("inv")            # ESQUERDA logada = você (gear real); senão aleatório
	var look: Dictionary = player if is_player else (look_override if not look_override.is_empty() else _rand_look())
	var gender := str(look.get("gender", "male"))
	var node := (CHAR_FEMALE if gender == "female" else CHAR).instantiate()
	if node == null:
		return
	add_child(node)
	node.position = pos
	node.rotation_degrees = Vector3(0, yaw_deg, 0)
	node.scale = Vector3.ONE * SCALE
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	# liga a lib de espadas (UAL2 — variações de golpe)
	if ap and not ap.has_animation_library("UAL2_Standard"):
		var lib2 = load(UAL2_PATH)
		if lib2 is AnimationLibrary:
			ap.add_animation_library("UAL2_Standard", lib2)
	# veste: você = itens REAIS (por item); oponente = set sorteado. + arma na mão (tipo + raridade)
	if skel:
		if is_player:
			_dress_from_inv(node, skel, player["inv"], gender)
		else:
			_dress(node, skel, look)
		Weapons.new().attach_weapon(node, weapon_kind, weapon_rarity)
	# estado do lutador: ranged (arco→kiting) + base_y/dodging/busy p/ o movimento [MENU_DUEL]
	var fighter := {"node": node, "anim": ap, "ranged": Weapons.new().is_bow_kind(weapon_kind),
		"base_y": pos.y, "dodging": false, "busy": false}
	# idle em loop; one-shot (ataque/tiro/hurt/roll) volta pro idle ao terminar e libera o "busy"
	if ap:
		var il := ap.get_animation(IDLE)
		if il:
			il.loop_mode = Animation.LOOP_LINEAR
		var wl := ap.get_animation(WALK)   # locomoção do kiting precisa repetir (senão "trava" a cada ciclo)
		if wl:
			wl.loop_mode = Animation.LOOP_LINEAR
		ap.animation_finished.connect(func(_a: StringName) -> void:
			fighter["busy"] = false
			if is_instance_valid(node) and not fighter.get("dodging", false):   # não corta o rolê em andamento
				ap.play(IDLE, BLEND))
		ap.play(IDLE)
	_fighters.append(fighter)

func _dress(node: Node3D, skel: Skeleton3D, look := {}) -> void:
	var theme := str(look.get("theme", "ranger"))
	var gender := "female" if str(look.get("gender", "male")) == "female" else "male"
	var rarity := int(look.get("rarity", 1))
	var seed_name := str(look.get("seed", ""))
	var g := "Female" if gender == "female" else "Male"
	# esconde todas as malhas base (Superhero) ANTES de vestir
	var base: Array = []
	_collect_meshes(node, base)
	for m: MeshInstance3D in base:
		m.visible = false
	# rosto (gênero-aware)
	var head = load("res://assets/base/Base_%s_Head.gltf" % g)
	if head is PackedScene:
		_attach_outfit(head, skel)
	# set completo por tema/gênero/variante + recolor por raridade
	var dressed := {}
	for ty in OutfitsLib.ARMOR_SLOTS:
		var path := OutfitsLib.piece_path_theme(theme, ty, gender, seed_name)
		if path != "" and ResourceLoader.exists(path):
			var sc = load(path)
			if sc is PackedScene:
				_attach_outfit(sc, skel, theme, rarity)
				dressed[ty] = true
	_dress_nude_gaps(skel, dressed, g)

# Veste com os ITENS REAIS do jogador (mesmo do boneco da ficha): cada peça equipada vem do tema/variante
# do PRÓPRIO item + recolor pela raridade dele. [OUTFITS] (herói do fundo = você, com seu gear exato)
func _dress_from_inv(node: Node3D, skel: Skeleton3D, inv_arr: Array, gender: String) -> void:
	var g := "Female" if gender == "female" else "Male"
	var base: Array = []
	_collect_meshes(node, base)
	for m: MeshInstance3D in base:
		m.visible = false
	var head = load("res://assets/base/Base_%s_Head.gltf" % g)
	if head is PackedScene:
		_attach_outfit(head, skel)
	var dressed := {}
	for it in inv_arr:
		if it is Dictionary and it.get("equipped") == true:
			var ty := str(it.get("type", ""))
			if OutfitsLib.is_armor_slot(ty):
				var path := OutfitsLib.piece_path_item(it, ty, gender)   # tema+variante do ITEM
				if path != "" and ResourceLoader.exists(path):
					var sc = load(path)
					if sc is PackedScene:
						_attach_outfit(sc, skel, OutfitsLib.theme_for_item(it), int(it.get("rarity", 1)))
						dressed[ty] = true
	_dress_nude_gaps(skel, dressed, g)

# Põe a pele nua (cortada no Blender) nos slots de CORPO sem peça vestida (defensivo: zero buraco).
func _dress_nude_gaps(skel: Skeleton3D, dressed: Dictionary, g: String) -> void:
	var part_slot := {"Torso": "ARMOR", "Arms": "GLOVES", "Legs": "PANTS", "Feet": "BOOTS"}
	for part in part_slot:
		if not dressed.has(part_slot[part]):
			var p = load("res://assets/base/Base_%s_%s.gltf" % [g, part])
			if p is PackedScene:
				_attach_outfit(p, skel)

func _attach_outfit(scene: PackedScene, skel: Skeleton3D, theme := "", rarity := 0) -> void:
	var inst := scene.instantiate()
	var meshes: Array = []
	_collect_meshes(inst, meshes)
	for mi: MeshInstance3D in meshes:
		var skin := mi.skin
		mi.get_parent().remove_child(mi)
		mi.owner = null   # [OWNER_FIX] zera o owner antes de reparentar p/ o esqueleto (evita warning "owner inconsistent")
		skel.add_child(mi)
		mi.transform = Transform3D.IDENTITY
		mi.skin = skin
		mi.skeleton = NodePath("..")
		if theme != "" and rarity > 0:
			OutfitsLib.recolor_mesh(mi, theme, rarity)
	inst.queue_free()

func _collect_meshes(n: Node, out: Array) -> void:
	if n is MeshInstance3D:
		out.append(n)
	for c in n.get_children():
		_collect_meshes(c, out)

func _process(dt: float) -> void:
	if siege_mode:                      # [MAPA_TORRE] cerco infinito tem sua própria máquina de estados
		_siege_step(dt)
		return
	if _fighters.size() < 2:
		return
	var r := _ranged_idx()              # >=0 → arco×melee (kiting); -1 → duelo melee normal
	if r >= 0:
		_kite_move(dt, r, 1 - r)        # movimento contínuo (persegue/recua) todo frame
	_timer -= dt
	if _timer <= 0.0:
		_timer = _rng.randf_range(0.65, 1.05)
		if r >= 0:
			_kite_beat(r, 1 - r)        # arqueiro atira (ou pula se foi alcançado)
		else:
			_atk = 1 - _atk
			_swing(_atk, 1 - _atk)

# Índice do arqueiro se for ARCO×MELEE (kiting); -1 se ambos iguais (duelo normal). [MENU_DUEL]
func _ranged_idx() -> int:
	if _fighters.size() < 2:
		return -1
	var a: bool = _fighters[0].get("ranged", false)
	var b: bool = _fighters[1].get("ranged", false)
	if a == b:
		return -1
	return 0 if a else 1

# Vira o lutador p/ +X (dir>=0) ou -X (dir<0). yaw 90 = encara +X; -90 = encara -X (igual ao spawn).
func _face(f: Dictionary, dir: float) -> void:
	var n: Node3D = f["node"]
	if is_instance_valid(n):
		n.rotation_degrees.y = 90.0 if dir >= 0.0 else -90.0

# Kiting contínuo (por frame): melee persegue, arqueiro recua ANDANDO; encurralado na borda → pula através.
func _kite_move(dt: float, r: int, m: int) -> void:
	var R: Dictionary = _fighters[r]
	var M: Dictionary = _fighters[m]
	var rn: Node3D = R["node"]
	var mn: Node3D = M["node"]
	if not (is_instance_valid(rn) and is_instance_valid(mn)):
		return
	var side := signf(rn.position.x - mn.position.x)   # +1 = arqueiro à direita do melee
	if side == 0.0:
		side = 1.0
	var gap := absf(rn.position.x - mn.position.x)
	# MELEE persegue o arqueiro — só quando NÃO está golpeando (planta o golpe, sem deslizar)
	_face(M, side)
	if not M.get("busy", false):
		var desired_m := rn.position.x - side * KITE_RANGE
		mn.position.x = move_toward(mn.position.x, desired_m, KITE_MELEE_SPEED * dt)
		var ap_m: AnimationPlayer = M["anim"]
		if ap_m:
			var want_m: String = WALK if absf(mn.position.x - desired_m) > 0.03 else IDLE
			if ap_m.current_animation != want_m:
				ap_m.play(want_m, BLEND)
	# ── ARQUEIRO ──
	if R.get("dodging", false):
		_dodge_step(dt, R, rn)              # rolê EM ANDAMENTO: move até o pouso (frame-based, robusto)
		return
	_face(R, -side)                         # encara o melee
	if gap <= DODGE_GAP:                     # melee COLOU → ele GOLPEIA e o arqueiro rola ATRAVÉS (esquiva)
		if not M.get("busy", false):
			_melee_swing(m)                 # o golpe que o arqueiro está esquivando (sincronizado)
		_start_dodge(r, m)
		return
	var ap_r: AnimationPlayer = R["anim"]
	if not R.get("busy", false):             # senão (atirando) fica parado mirando
		var next_x := clampf(rn.position.x + side * KITE_ARCHER_SPEED * dt, -KITE_EDGE, KITE_EDGE)
		var moved := absf(next_x - rn.position.x) > 0.001
		rn.position.x = next_x
		if ap_r:
			if moved:
				if ap_r.current_animation != WALK:
					ap_r.play(WALK, BLEND, -1.0)   # walk em REVERSO = recuar mirando
			elif ap_r.current_animation != IDLE:
				ap_r.play(IDLE, BLEND)

# Batida do kiting: melee no alcance → golpe (o arqueiro esquiva via _kite_move); senão o arqueiro ATIRA.
func _kite_beat(r: int, m: int) -> void:
	var R: Dictionary = _fighters[r]
	var rn: Node3D = R["node"]
	# o arqueiro ATIRA no beat quando tem espaço (não no meio da esquiva nem de um tiro). O golpe do
	# melee agora sai SINCRONIZADO com a esquiva, lá no _kite_move (não mais aqui).
	if is_instance_valid(rn) and not R.get("dodging", false) and not R.get("busy", false):
		_kite_shoot(r, m)

func _kite_shoot(r: int, m: int) -> void:
	var R: Dictionary = _fighters[r]
	var M: Dictionary = _fighters[m]
	var rn: Node3D = R["node"]
	var mn: Node3D = M["node"]
	if not (is_instance_valid(rn) and is_instance_valid(mn)):
		return
	var ap_r: AnimationPlayer = R["anim"]
	if ap_r:
		var a := ap_r.get_animation(SHOOT)
		if a:
			a.loop_mode = Animation.LOOP_NONE
		R["busy"] = true
		ap_r.play(SHOOT, BLEND)
	_arrow(rn, mn)
	# melee reage (hurt + sangue) quando a flecha chega
	var ap_m: AnimationPlayer = M["anim"]
	var hitdir := Vector3(mn.position.x - rn.position.x, 0, 0)
	var target := mn
	get_tree().create_timer(0.24).timeout.connect(func() -> void:
		if not is_instance_valid(target):
			return
		if ap_m:
			var react: String = HURTS[_rng.randi() % HURTS.size()]
			var h := ap_m.get_animation(react)
			if h:
				h.loop_mode = Animation.LOOP_NONE
			M["busy"] = true
			ap_m.play(react, BLEND)
		_blood(target.global_position + Vector3(0, 1.15, 0), hitdir))

func _melee_swing(m: int) -> void:
	var M: Dictionary = _fighters[m]
	var ap: AnimationPlayer = M["anim"]
	if ap:
		var clip: String = ATTACKS[_rng.randi() % ATTACKS.size()]
		var a := ap.get_animation(clip)
		if a:
			a.loop_mode = Animation.LOOP_NONE
		M["busy"] = true
		ap.play(clip, BLEND)

# Inicia a esquiva: o arqueiro vai ROLAR ATRAVÉS do melee e pousar do OUTRO LADO (alvo guardado em hop_to).
# O movimento real é feito por frame no _dodge_step (robusto — sem tween que se atrapalha). [MENU_DUEL]
func _start_dodge(r: int, m: int) -> void:
	var R: Dictionary = _fighters[r]
	var M: Dictionary = _fighters[m]
	var rn: Node3D = R["node"]
	var mn: Node3D = M["node"]
	if not (is_instance_valid(rn) and is_instance_valid(mn)):
		return
	var through := signf(mn.position.x - rn.position.x)   # direção ATRAVÉS do melee → outro lado
	if through == 0.0:
		through = 1.0
	R["hop_from"] = rn.position.x
	R["hop_to"] = clampf(mn.position.x + through * KITE_LAND, -KITE_EDGE, KITE_EDGE)   # pousa além do melee
	R["dodging"] = true
	R["busy"] = true                                      # cancela o tiro p/ esquivar
	_face(R, through)                                     # encara a direção do rolê
	var ap_r: AnimationPlayer = R["anim"]
	if ap_r:
		var roll: String = ROLL if ap_r.has_animation(ROLL) else WALK
		var a: Animation = ap_r.get_animation(roll)
		if a:
			a.loop_mode = Animation.LOOP_NONE
		ap_r.play(roll, BLEND)

# Passo do rolê (todo frame): move o arqueiro até hop_to a HOP_SPEED + arco de pulo; termina ao chegar.
func _dodge_step(dt: float, R: Dictionary, rn: Node3D) -> void:
	var target_x: float = R.get("hop_to", rn.position.x)
	var from_x: float = R.get("hop_from", rn.position.x)
	var base_y: float = R.get("base_y", 0.0)
	rn.position.x = move_toward(rn.position.x, target_x, HOP_SPEED * dt)
	var span := maxf(0.01, absf(target_x - from_x))
	var prog := clampf(1.0 - absf(rn.position.x - target_x) / span, 0.0, 1.0)
	rn.position.y = base_y + sin(prog * PI) * 0.45        # arco do pulo (pico no meio do caminho)
	if absf(rn.position.x - target_x) < 0.03:             # chegou no outro lado → encerra a esquiva
		rn.position.x = target_x
		rn.position.y = base_y
		R["dodging"] = false
		R["busy"] = false
		var ap: AnimationPlayer = R["anim"]
		if ap:
			ap.play(IDLE, BLEND)

# Um golpe: atacante INVESTE (lunge) pra frente + toca o ataque; defensor reage (Hit_Chest)
# e SANGRA no impacto. Ambos voltam pro idle sozinhos (animation_finished). Sem await.
func _swing(attacker: int, defender: int) -> void:
	var ap_a: AnimationPlayer = _fighters[attacker]["anim"]
	var ap_d: AnimationPlayer = _fighters[defender]["anim"]
	var na: Node3D = _fighters[attacker]["node"]
	var nd: Node3D = _fighters[defender]["node"]
	if not (is_instance_valid(na) and is_instance_valid(nd)):
		return
	var ranged: bool = _fighters[attacker].get("ranged", false)
	# atacante: ARCO → tiro (Spell_Simple_Shoot); MELEE → golpe aleatório de espada (A/B/combo/attack)
	if ap_a:
		var clip: String = SHOOT if ranged else ATTACKS[_rng.randi() % ATTACKS.size()]
		var an := ap_a.get_animation(clip)
		if an:
			an.loop_mode = Animation.LOOP_NONE
		ap_a.play(clip, BLEND)
	if ranged:
		_arrow(na, nd)   # arqueiro atira PARADO (sem investida) — a flecha voa até o alvo
	else:
		# investida melee: avança um passo pro oponente e recua (dá movimento)
		var dirx := signf(nd.position.x - na.position.x)
		var home := na.position
		var tw := na.create_tween()
		tw.tween_property(na, "position", home + Vector3(dirx * 0.38, 0, 0), 0.16).set_trans(Tween.TRANS_SINE)
		tw.tween_interval(0.10)
		tw.tween_property(na, "position", home, 0.30).set_trans(Tween.TRANS_SINE)
	# defensor: ~25% ESQUIVA (rola, sem sangue); senão LEVA o golpe (hurt variado + sangue)
	var dodge := _rng.randf() < 0.25
	var react: String = ROLL if dodge else HURTS[_rng.randi() % HURTS.size()]
	var hitdir := Vector3(nd.position.x - na.position.x, 0, 0)
	get_tree().create_timer(0.26).timeout.connect(func() -> void:
		if not is_instance_valid(nd):
			return
		if ap_d:
			var h := ap_d.get_animation(react)
			if h:
				h.loop_mode = Animation.LOOP_NONE
			ap_d.play(react, BLEND)
		if not dodge:
			_blood(nd.global_position + Vector3(0, 1.15, 0), hitdir))

# Flecha do arqueiro: vara fina que voa do atacante até o alvo e some. Decoração. [MENU_DUEL]
func _arrow(from_node: Node3D, to_node: Node3D) -> void:
	if not (is_instance_valid(from_node) and is_instance_valid(to_node)):
		return
	var arrow := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.035, 0.035, 0.55)   # comprida no eixo Z → vira "flecha"
	arrow.mesh = bm
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.55, 0.40, 0.18)
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	arrow.material_override = mat
	add_child(arrow)
	var start := from_node.global_position + Vector3(0, 1.35, 0)
	var endp := to_node.global_position + Vector3(0, 1.15, 0)
	arrow.global_position = start
	arrow.look_at(endp)   # aponta a vara pro alvo
	var tw := arrow.create_tween()
	tw.tween_property(arrow, "global_position", endp, 0.22)
	tw.tween_callback(arrow.queue_free)

# Jato de sangue por partículas (versão enxuta do GORE do BattleReplay). Some sozinho.
func _blood(pos: Vector3, dir: Vector3) -> void:
	var p := GPUParticles3D.new()
	p.one_shot = true
	p.explosiveness = 1.0
	p.amount = 24
	p.lifetime = 0.9
	var m := ParticleProcessMaterial.new()
	var d := Vector3.UP
	if dir.length() > 0.01:
		d = (dir.normalized() + Vector3.UP * 0.6).normalized()
	m.direction = d
	m.spread = 32.0
	m.initial_velocity_min = 1.6
	m.initial_velocity_max = 4.8
	m.gravity = Vector3(0, -9.0, 0)
	m.damping_min = 0.4
	m.damping_max = 1.6
	m.scale_min = 0.5
	m.scale_max = 1.35
	var g := Gradient.new()
	g.set_color(0, Color(0.55, 0.02, 0.02))
	g.add_point(0.55, Color(0.22, 0.0, 0.0))
	g.set_color(2, Color(0.22, 0.0, 0.0, 0.0))
	var gt := GradientTexture1D.new()
	gt.gradient = g
	m.color_ramp = gt
	p.process_material = m
	var quad := QuadMesh.new()
	quad.size = Vector2(0.085, 0.085)
	var qm := StandardMaterial3D.new()
	qm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	qm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	qm.vertex_color_use_as_albedo = true
	qm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	quad.material = qm
	p.draw_pass_1 = quad
	add_child(p)
	p.global_position = pos
	p.emitting = true
	get_tree().create_timer(1.1).timeout.connect(func() -> void:
		if is_instance_valid(p):
			p.queue_free())
