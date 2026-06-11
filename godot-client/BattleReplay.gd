extends Node3D
# ── Replay 3D dirigido por EVENTOS reais (Fase 3) ──────────────────────────────
# Faz login → POST /api/arena/fight → encena os `battleEvents` do backend (os MESMOS
# que o battleArena.js 2D toca). O backend já decidiu o resultado; aqui só damos corpo
# 3D aos eventos. Director DIRIGIDO POR SIMULAÇÃO: o movimento é contínuo (perseguição/
# kite) e cada evento de dano só dispara quando o lutador está em posição (com timeout)
# → nada de golpe no ar nem teleporte, e o movimento flui.
# Rode BattleReplay.tscn com F6. Credenciais via login.cfg (gitignorado) ou Inspector.
# Plano: docs/PLANO_GODOT_3D.md (Fase 3) · Evento: BattleSimulator.BattleEvent

const CHAR := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Male_rigged.tscn")
const LIB := "UAL1_Standard/"
const A_IDLE := LIB + "Sword_Idle"
const A_ATTACK := LIB + "Sword_Attack"
const A_SHOOT := LIB + "Spell_Simple_Shoot"
const A_HURT := LIB + "Hit_Chest"
const A_HURT_HEAD := LIB + "Hit_Head"
const A_WALK := LIB + "Walk"
const A_RUN := LIB + "Jog_Fwd"   # corrida — o inimigo vem CORRENDO pra cima
const A_ROLL := LIB + "Roll"   # cambalhota IN-PLACE (Roll_RM tem root-motion e "voa" com o nosso tween)
const A_DANCE := LIB + "Dance"   # warm-up durante a contagem
const A_DEATH := LIB + "Death01"
const BARW := 0.7

# combate dirigido por SIMULAÇÃO: o movimento é CONTÍNUO; cada evento de dano só dispara
# quando o lutador está em posição (com timeout). Mesmos eventos/resultado do backend.
const ATTACK_RANGE := 1.35   # alcance em que o melee conecta o golpe
const MELEE_SPEED := 2.8     # corrida do melee fechando distância (unid/s)
const ARCHER_SPEED := 2.2    # recuo do arqueiro (kite)
const ARCHER_PREF := 2.4     # arqueiro recua p/ manter ~esta distância do melee (obriga o melee a perseguir)
const FIELD_EDGE := 4.5      # borda do campo: o arqueiro recua até aqui (o guerreiro o alcança)
const DODGE_LAND := 2.8      # distância ATRÁS do inimigo em que o arqueiro aterrissa após o roll-através
const WINDUP := 0.18         # s — armar o golpe antes do impacto
const RECOVER := 0.12        # s — respiro após o impacto antes do próximo evento
const MAX_WAIT := 2.2        # s — timeout p/ disparar mesmo fora de posição (nunca trava)
const COUNTDOWN := 3.0       # s — contagem 3,2,1 antes da luta (warm-up)
const INSTANT_TYPES := ["spawn", "victory", "heal", "berserk", "backpedal", "pinned", "pointblank"]

# game-feel (polish de fluidez — sugestões do Fable)
const BLEND := 0.12          # cross-fade entre animações (mata o "pop" de troca)
const TURN_SPEED := 13.0     # virada suave (rad/s aprox; frame-rate independente)
const ACCEL := 12.0          # aceleração do movimento → chega à vel. máx em ~0.23s (sem partir/parar seco)
const HP_LERP := 9.0         # drenagem suave da barra de vida

# [GODOT_PAPERDOLL] paper-doll (igual ao PaperDollLive): veste o lutador com as peças Ranger.
const PIECES := {
	"ARMOR":    "res://assets/outfits/ranger/Male_Ranger_Body.gltf",
	"PANTS":    "res://assets/outfits/ranger/Male_Ranger_Legs.gltf",
	"BOOTS":    "res://assets/outfits/ranger/Male_Ranger_Feet_Boots.gltf",
	"GLOVES":   "res://assets/outfits/ranger/Male_Ranger_Arms.gltf",
	"HELMET":   "res://assets/outfits/ranger/Male_Ranger_Head_Hood.gltf",
	"SHOULDER": "res://assets/outfits/ranger/Male_Ranger_Acc_Pauldron.gltf",
}
const BASE_HEAD := "res://assets/base/Base_Male_Head.gltf"
const BASE_PART := {  # parte nua (pele cortada no Blender) -> slot que a cobre
	"res://assets/base/Base_Male_Torso.gltf": "ARMOR",
	"res://assets/base/Base_Male_Arms.gltf":  "GLOVES",
	"res://assets/base/Base_Male_Legs.gltf":  "PANTS",
	"res://assets/base/Base_Male_Feet.gltf":  "BOOTS",
}
const DEFAULT_OUTFIT := ["ARMOR", "PANTS", "BOOTS", "GLOVES", "HELMET", "SHOULDER"]  # vestido completo (inimigo / sem equip)

# geometria de enquadramento (em metros)
const COMBAT_X := 1.15   # |x| de referência p/ a câmera no 1v1 melee
const ENTRY_X := 4.2     # |x| onde os lutadores nascem (a sim os aproxima)

# tipos de evento (idênticos ao battleArena.js 2D)
const HIT_TYPES := ["attack", "crit", "volley", "extra"]       # carregam dano/HP
const SWING_TYPES := ["attack", "crit", "volley", "extra", "miss", "dodge"]  # atacante balança a arma
const RANGED_MARKERS := ["volley", "pinned", "pointblank", "backpedal"]  # delatam um lutador ranged

## Credenciais (sobrepostas por login.cfg se existir). adm/adm123 só vale no DEV local.
@export var username := "adm"
@export var password := "adm123"
## Vazio = URL padrão do BackendClient (Railway). "http://localhost:8080" no dev local.
@export var base_url_override := ""
## TESTE: pula o backend e usa um duelo MOCK (espada vs espada) — bom p/ ver o combate sem login.
@export var force_mock := false
## TESTE: no mock, faz o Bandido (espada) VENCER — p/ ver como fica quando o melee ganha o arqueiro.
@export var mock_enemy_wins := false

var events: Array = []
var fighters := {}          # name -> dict do lutador
var order: Array = []       # [left, right] na ordem de spawn
var player_equip: Array = []  # tipos de armadura EQUIPADOS pelo jogador (p/ vestir o lutador da esquerda)
var player_weapon := ""       # tipo visual da arma equipada do herói: sword|bow|axe|spear|mace
var cam: Camera3D

# kiting: ativo quando EXATAMENTE um lado é ranged (arco) e o outro melee
var kiting := false
var ranged_f := {}            # lutador que recua/atira
var melee_f := {}             # lutador que avança
var victory_label: Label
var status_label: Label

# director dirigido por simulação: movimento contínuo + cursor de evento com gatilho por posição
var phase := "loading"      # loading → fight → done
var idx := 0                # cursor do evento atual
var act_state := "approach" # approach (espera posição) → windup (arma) → recover (respira)
var act_timer := 0.0
var wait_timer := 0.0
var cur_windup := WINDUP     # windup/recover do golpe atual (variados p/ matar o "metrônomo")
var cur_recover := RECOVER
var countdown_t := 0.0       # cronômetro da contagem 3,2,1
var countdown_label: Label

func _ready() -> void:
	_setup_scene()
	_make_ui()
	await _load_events()
	if events.is_empty():
		return
	_build_fighters()
	_frame_camera()
	phase = "countdown"   # 3,2,1 antes de soltar a luta
	print("=== BATTLE REPLAY (sim-driven) === %d eventos · kiting=%s" % [events.size(), kiting])

# ── carga dos eventos: backend real, com fallback mock ──────────────────────────
# Sempre tenta logar p/ ler o EQUIP+ARMA reais do herói (o herói é sempre dinâmico).
# force_mock só troca os EVENTOS por um duelo fixo e força o Bandido a ser espadachim.
func _load_events() -> void:
	var cf := ConfigFile.new()
	if cf.load("res://login.cfg") == OK:
		username = str(cf.get_value("login", "user", username))
		password = str(cf.get_value("login", "pass", password))

	var client := BackendClient.new()
	if base_url_override != "":
		client.base_url = base_url_override
	add_child(client)
	_status("Conectando %s…" % client.base_url)

	var lr = await client.login(username, password)
	if not lr.get("ok"):
		_status("Login falhou (%s) — usando luta MOCK." % lr.get("status"))
		print(">>> LOGIN FALHOU: %s | %s — caindo no mock." % [lr.get("status"), lr.get("error", "")])
		events = _mock_events()
		return

	# inventário → armadura equipada (paper-doll) + ARMA equipada (visual dinâmico do herói)
	var inv = await client.get_inventory()
	if inv.get("ok") and inv.get("json") is Array:
		_read_player_gear(inv["json"])
		print(">>> herói: equip=%s arma=%s" % [str(player_equip), player_weapon])

	if force_mock:
		events = _mock_events()
		_status("Modo TESTE — sua arma real vs Bandido (espada)")
		print("=== force_mock: eventos MOCK, herói real, Bandido = espada ===")
		return

	_status("Lutando na arena…")
	var fr = await client.arena_fight()
	if not fr.get("ok") or not (fr.get("json") is Dictionary):
		_status("Arena falhou (%s) — usando luta MOCK." % fr.get("status"))
		print(">>> ARENA FALHOU: %s | raw: %s" % [fr.get("status"), fr.get("raw", "")])
		events = _mock_events()
		return

	var j: Dictionary = fr["json"]
	var be = j.get("battleEvents")
	if be is Array and be.size() >= 2:
		events = be
		var who := "venceu" if j.get("won") else "perdeu"
		_status("%s vs %s — você %s!" % [username, str(j.get("opponent", "?")), who])
		print(">>> arena OK: %s vs %s, won=%s, %d eventos" % [username, j.get("opponent"), j.get("won"), be.size()])
	else:
		_status("Sem battleEvents — usando luta MOCK.")
		print(">>> resposta sem battleEvents — mock. raw: %s" % fr.get("raw", ""))
		events = _mock_events()

# ── monta os lutadores a partir dos eventos de spawn ────────────────────────────
func _build_fighters() -> void:
	var spawns: Array = events.filter(func(e): return str(e.get("type", "")) == "spawn")
	if spawns.size() < 2:
		_status("Eventos sem 2 spawns — nada a encenar.")
		return
	var lname := str(spawns[0].get("actor", "Hero"))
	var rname := str(spawns[1].get("actor", "Foe"))
	# HERÓI (esquerda = challenger): arma e equip REAIS. Sem arma equipada → espada.
	var lweapon := player_weapon if player_weapon != "" else "sword"
	var lequip: Array = player_equip if player_equip.size() > 0 else DEFAULT_OUTFIT
	# INIMIGO (direita): no force_mock vira espadachim; na arena real segue o estilo dos eventos.
	var rweapon := "sword" if force_mock else ("bow" if _is_ranged(rname) else "sword")
	order = [
		_make_fighter(lname, -1, int(spawns[0].get("targetMaxHp", 100)), lweapon, lequip),
		_make_fighter(rname,  1, int(spawns[1].get("targetMaxHp", 100)), rweapon, DEFAULT_OUTFIT),
	]
	fighters[lname] = order[0]
	fighters[rname] = order[1]
	# kiting ativo quando só um lado é ranged: ele recua/atira, o outro avança.
	if order[0]["ranged"] != order[1]["ranged"]:
		kiting = true
		ranged_f = order[0] if order[0]["ranged"] else order[1]
		melee_f  = order[1] if order[0]["ranged"] else order[0]
		# posição inicial de kiting: frente a frente (sem entrada andando). O arqueiro começa
		# mais ao centro p/ ter espaço de recuar antes de cruzar; o melee, mais longe.
		var rn: Node3D = ranged_f["node"]
		var mn: Node3D = melee_f["node"]
		rn.position = Vector3(ranged_f["side"] * 2.6, 0, 0)
		mn.position = Vector3(melee_f["side"] * 4.4, 0, 0)

# [GODOT_PAPERDOLL] Veste UM lutador: esconde a base nua, põe a cabeça sempre, roupa no slot
# equipado e a pele cortada no slot vazio (a roupa cobre o resto → 0 clipping). Se as peças
# cortadas não existirem, mantém a base visível (nu, mas não invisível).
func _dress(node: Node3D, skel: Skeleton3D, equipped_types: Array) -> void:
	if skel == null: return
	var head: PackedScene = load(BASE_HEAD)
	if head == null:
		push_warning("paper-doll: %s não carregou — lutador fica com a base nua." % BASE_HEAD)
		return
	var body_meshes: Array = []
	_collect_meshes(node, body_meshes)   # base do addon (corpo+cabeça) — esconder inteira
	for m: MeshInstance3D in body_meshes:
		m.visible = false
	_attach_outfit_to(skel, head)        # rosto sempre
	for ty in PIECES:
		if ty in equipped_types:
			var sc: PackedScene = load(PIECES[ty])
			if sc: _attach_outfit_to(skel, sc)
	for path in BASE_PART:
		if not (str(BASE_PART[path]) in equipped_types):   # slot sem roupa → pele
			var p: PackedScene = load(path)
			if p: _attach_outfit_to(skel, p)

func _attach_outfit_to(skel: Skeleton3D, scene: PackedScene) -> void:
	var inst := scene.instantiate()
	var meshes: Array = []
	_collect_meshes(inst, meshes)
	for mi: MeshInstance3D in meshes:
		var skin := mi.skin
		mi.get_parent().remove_child(mi)
		skel.add_child(mi)
		mi.transform = Transform3D.IDENTITY
		mi.skin = skin
		mi.skeleton = NodePath("..")   # esqueleto compartilhado anima a peça junto
	inst.queue_free()

func _collect_meshes(node: Node, out: Array) -> void:
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		_collect_meshes(c, out)

# Lê o inventário: armadura equipada → player_equip; arma equipada → player_weapon (tipo visual).
func _read_player_gear(items: Array) -> void:
	for it in items:
		if not (it is Dictionary) or it.get("equipped") != true:
			continue
		var ty := str(it.get("type", ""))
		if PIECES.has(ty) and not (ty in player_equip):
			player_equip.append(ty)
		elif ty == "WEAPON":
			player_weapon = _weapon_kind(str(it.get("name", "")), str(it.get("weaponCategory", "")))

# Infere o tipo visual da arma pelo nome + categoria (backend só dá MELEE/RANGED).
func _weapon_kind(item_name: String, category: String) -> String:
	var n := item_name.to_lower()
	if category == "RANGED" or "bow" in n or "arco" in n or "crossbow" in n or "besta" in n:
		return "bow"
	if "axe" in n or "machado" in n or "hatchet" in n:
		return "axe"
	if "spear" in n or "lança" in n or "lanca" in n or "pike" in n or "halberd" in n:
		return "spear"
	if "mace" in n or "marreta" in n or "maul" in n or "hammer" in n or "martelo" in n or "club" in n:
		return "mace"
	return "sword"

func _is_ranged(who: String) -> bool:
	for e in events:
		if str(e.get("actor", "")) == who and str(e.get("type", "")) in RANGED_MARKERS:
			return true
	return false

func _make_fighter(fname: String, side: int, maxhp: int, weapon_kind: String, equipped_types: Array) -> Dictionary:
	var node := CHAR.instantiate()
	add_child(node)
	node.position = Vector3(ENTRY_X * side, 0, 0)
	node.scale = Vector3(0.92, 0.92, 0.92)
	var ranged := weapon_kind == "bow"
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	var f := {"name": fname, "node": node, "anim": ap, "side": side, "ranged": ranged,
			  "dead": false, "maxhp": max(1, maxhp), "hp": max(1, maxhp), "busy": false, "hopping": false,
			  "vel": 0.0, "shown_hp": float(max(1, maxhp)), "face_target": deg_to_rad(90.0 if -side > 0 else -90.0)}
	node.rotation.y = f["face_target"]   # nasce já virado pro centro (sem lerp do zero)
	_face(f, -side)   # seta o alvo de rotação (encara o oponente)
	_dress(node, skel, equipped_types)   # [GODOT_PAPERDOLL] veste antes da arma
	_attach_weapon(node, weapon_kind)
	# barra de vida + nome
	var bar := Node3D.new()
	add_child(bar)
	bar.add_child(_quad(BARW, 0.09, Color(0, 0, 0, 0.55), 0))
	var fill := _quad(BARW, 0.09, Color(0.25, 0.85, 0.35, 1.0), 1)
	bar.add_child(fill)
	var name_lbl := Label3D.new()
	name_lbl.text = fname + ("  🏹" if ranged else "")
	name_lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	name_lbl.no_depth_test = true
	name_lbl.pixel_size = 0.004
	name_lbl.font_size = 48
	name_lbl.position = Vector3(0, 0.14, 0)
	bar.add_child(name_lbl)
	f["bar"] = bar
	f["fill"] = fill
	if ap:
		var il := ap.get_animation(A_IDLE)
		if il: il.loop_mode = Animation.LOOP_LINEAR
		# one-shot (attack/shoot/hurt) terminou → libera o lutador e volta pro idle.
		# (walk/jump/idle são LOOP → nunca disparam animation_finished)
		ap.animation_finished.connect(func(_a):
			f["busy"] = false
			if not f["dead"] and not f["hopping"]: ap.play(A_IDLE, BLEND))   # não corta um roll em andamento
		ap.play(A_IDLE)
	return f

# ── director dirigido por SIMULAÇÃO ─────────────────────────────────────────────
# Todo frame: (1) move os lutadores de forma contínua; (2) avança o cursor de eventos,
# disparando o golpe só quando o atacante está em posição (ou após timeout). Assim o
# golpe nunca bate no ar nem teleporta — e o movimento flui.
func _process(dt: float) -> void:
	for f in order:
		if not is_instance_valid(f["node"]): continue
		var n := f["node"] as Node3D
		# virada SUAVE até o alvo (sem snap de 90°) — só vivo (o cadáver fica como caiu)
		if not f["dead"]:
			n.rotation.y = lerp_angle(n.rotation.y, f["face_target"], 1.0 - exp(-TURN_SPEED * dt))
		# barra: segue a cabeça + DRENA suave (shown_hp → hp) p/ leitura de impacto
		if f.has("bar"):
			f["bar"].global_position = n.global_position + Vector3(0, 2.05, 0)
			f["shown_hp"] = lerpf(f["shown_hp"], float(f["hp"]), 1.0 - exp(-HP_LERP * dt))
			_apply_hp_bar(f)
	match phase:
		"countdown": _countdown(dt)
		"fight":
			_move(dt)
			_advance(dt)

# Contagem 3,2,1 → Lutar! Os lutadores dançam (warm-up) e encaram o oponente.
func _countdown(dt: float) -> void:
	countdown_t += dt
	for f in order:
		if not f["dead"] and f["anim"]: _play_loop(f, A_DANCE)
	var remaining := COUNTDOWN - countdown_t
	if remaining > 0.0:
		countdown_label.text = str(int(ceil(remaining)))
	elif countdown_t < COUNTDOWN + 0.7:
		countdown_label.text = "Lutar!"
	else:
		countdown_label.text = ""
		for f in order:
			if f["anim"]: f["anim"].play(A_IDLE, BLEND)
		phase = "fight"

# Movimento contínuo: arco-vs-melee = perseguição/kite; senão = ambos fecham pro alcance.
func _move(dt: float) -> void:
	if order.size() < 2: return
	if kiting and not ranged_f.is_empty() and not melee_f.is_empty():
		_move_kite(dt)
	else:
		_move_clash(dt)

func _move_kite(dt: float) -> void:
	if ranged_f["dead"] or melee_f["dead"]: return
	var rn: Node3D = ranged_f["node"]
	var mn: Node3D = melee_f["node"]
	var side := signf(rn.position.x - mn.position.x)   # +1 = arqueiro à direita do melee
	if side == 0.0: side = 1.0
	var gap := absf(rn.position.x - mn.position.x)

	# Arqueiro rolando ATRAVÉS → CONGELA o guerreiro (só vira pra acompanhar) p/ ele ser
	# ultrapassado de verdade; senão o melee gruda no rolê e a troca de lado não aparece.
	if ranged_f["hopping"]:
		melee_f["vel"] = 0.0
		_face(melee_f, side)
		if not melee_f["busy"] and melee_f["anim"] and melee_f["anim"].current_animation != A_IDLE:
			melee_f["anim"].play(A_IDLE, BLEND)
		return

	# MELEE corre pra fechar até o alcance (com aceleração/frenagem → não parte/para seco)
	var prev_m := mn.position.x
	var desired_m := rn.position.x - side * (ATTACK_RANGE * 0.9)
	var new_m := _step_toward(melee_f, desired_m, MELEE_SPEED, dt)
	_face(melee_f, side)
	_locomotion(melee_f, prev_m, new_m)

	# ARQUEIRO: encara; pressionado recua ANDANDO; encurralado na borda PARA (o guerreiro o alcança)
	_face(ranged_f, -side)
	var prev_a := rn.position.x
	if gap < ARCHER_PREF:
		var next_x := clampf(rn.position.x + side * ARCHER_SPEED * dt, -FIELD_EDGE, FIELD_EDGE)
		rn.position = Vector3(next_x, 0, 0)
	if not ranged_f["busy"] and ranged_f["anim"]:
		if absf(rn.position.x - prev_a) > 0.004:
			if ranged_f["anim"].current_animation != A_WALK:
				ranged_f["anim"].play(A_WALK, BLEND, -1.0)      # walk em reverso = andar pra trás
		elif ranged_f["anim"].current_animation != A_IDLE:
			ranged_f["anim"].play(A_IDLE, BLEND)

func _move_clash(dt: float) -> void:
	if order.size() < 2: return
	# gap calculado UMA vez (posições atuais) → ambos param ao entrar no alcance, sem empurrar
	var gap := absf((order[0]["node"] as Node3D).position.x - (order[1]["node"] as Node3D).position.x)
	for i in 2:
		var f: Dictionary = order[i]
		if f["dead"] or f["hopping"]: continue
		var o: Dictionary = order[1 - i]
		var fn: Node3D = f["node"]
		var on: Node3D = o["node"]
		var s := signf(on.position.x - fn.position.x)
		if s == 0.0: s = float(-int(f["side"]))
		if gap > ATTACK_RANGE + 0.1:
			# ainda longe → corre pra fechar
			var prev := fn.position.x
			var desired := on.position.x - s * ATTACK_RANGE
			var new_x := _step_toward(f, desired, MELEE_SPEED, dt)
			_face(f, s)
			_locomotion(f, prev, new_x)
		else:
			# em alcance → PARA (não empurra o outro) e encara
			f["vel"] = 0.0
			_face(f, s)
			if not f["busy"] and f["anim"] and f["anim"].current_animation != A_IDLE:
				f["anim"].play(A_IDLE, BLEND)

# Move o lutador rumo a desired_x com ACELERAÇÃO + frenagem perto do alvo (planta o pé,
# em vez de partir/parar seco). Retorna o novo x. [game-feel]
func _step_toward(f: Dictionary, desired_x: float, max_speed: float, dt: float) -> float:
	var n: Node3D = f["node"]
	var dist := desired_x - n.position.x
	var target_v := clampf(absf(dist) / 0.22, 0.0, max_speed) * signf(dist)   # zona de frenagem
	var vel: float = move_toward(float(f["vel"]), target_v, ACCEL * dt)
	f["vel"] = vel
	var new_x: float = n.position.x + vel * dt
	n.position = Vector3(new_x, 0, 0)
	return new_x

# Run quando se move, idle quando parado (não interrompe golpe/flinch em andamento).
func _locomotion(f: Dictionary, prev_x: float, now_x: float) -> void:
	if f["busy"]: return
	var ap: AnimationPlayer = f["anim"]
	if ap == null: return
	if absf(now_x - prev_x) > 0.004:
		_play_loop(f, A_RUN)
	elif ap.current_animation != A_IDLE:
		ap.play(A_IDLE, BLEND)

# Toca uma animação em loop (com fallback p/ Walk se o clip não existir na lib).
func _play_loop(f: Dictionary, anim_name: String) -> void:
	var ap: AnimationPlayer = f["anim"]
	if ap == null: return
	var nm := anim_name if ap.has_animation(anim_name) else A_WALK
	if ap.current_animation == nm: return
	var a: Animation = ap.get_animation(nm)
	if a: a.loop_mode = Animation.LOOP_LINEAR
	ap.play(nm, BLEND)

# Roll-através: o arqueiro ROLA PRA FRENTE, passa pelo inimigo e aterrissa DODGE_LAND
# atrás dele (com espaço pra atacar). A duração do tween = duração do clip do Roll
# (velocidade natural, anim e deslocamento em sync — não congela nem desliza).
func _dodge_roll(dodger: Dictionary, attacker: Dictionary) -> void:
	if dodger["hopping"] or dodger["dead"]: return
	dodger["hopping"] = true
	dodger["vel"] = 0.0
	var n: Node3D = dodger["node"]
	var tn: Node3D = attacker["node"]
	var toward := signf(tn.position.x - n.position.x)   # direção do rolê: pra cima do inimigo
	if toward == 0.0: toward = 1.0
	var land_x := clampf(tn.position.x + toward * DODGE_LAND, -FIELD_EDGE, FIELD_EDGE)
	_face(dodger, toward)                                # encara a direção do rolê (pra frente)
	var ap: AnimationPlayer = dodger["anim"]
	var dur := 0.55
	if ap:
		var roll := A_ROLL if ap.has_animation(A_ROLL) else A_WALK
		var a: Animation = ap.get_animation(roll)
		if a and a.get_length() > 0.05:
			a.loop_mode = Animation.LOOP_NONE
			dur = a.get_length()
		ap.play(roll, BLEND)
	var tw := create_tween()
	tw.tween_property(n, "position", Vector3(land_x, 0, 0), dur)   # roll = velocidade constante
	tw.tween_callback(func():
		dodger["hopping"] = false
		if ap and not dodger["dead"]: ap.play(A_IDLE, BLEND))

# ── cursor de eventos: approach (espera posição) → windup → recover ─────────────
func _advance(dt: float) -> void:
	# eventos instantâneos (spawn/markers/heal/victory) resolvem na hora, sem ocupar tempo
	while idx < events.size() and str(events[idx].get("type", "")) in INSTANT_TYPES:
		_resolve(events[idx])
		idx += 1
	if idx >= events.size():
		_finish()
		return
	var e: Dictionary = events[idx]
	match act_state:
		"approach":
			wait_timer += dt
			if _ready_to_fire(e) or wait_timer >= MAX_WAIT:
				_begin(e)
				act_state = "windup"
				act_timer = 0.0
		"windup":
			act_timer += dt
			if act_timer >= cur_windup:
				_resolve(e)
				act_state = "recover"
				act_timer = 0.0
		"recover":
			act_timer += dt
			if act_timer >= cur_recover:
				idx += 1
				act_state = "approach"
				wait_timer = 0.0

# O golpe está pronto pra sair? Ranged atira quando não está rolando; melee precisa do alcance.
func _ready_to_fire(e: Dictionary) -> bool:
	var ty := str(e.get("type", ""))
	if not (ty in SWING_TYPES):
		return true
	var swinger := str(e.get("target", "")) if ty == "dodge" else str(e.get("actor", ""))
	var sw = fighters.get(swinger)
	if sw == null or sw["dead"]:
		return true
	if sw["ranged"]:
		return not sw["hopping"]
	var tname := str(e.get("actor", "")) if ty == "dodge" else str(e.get("target", ""))
	var tg = fighters.get(tname)
	if tg == null:
		return true
	return absf((sw["node"] as Node3D).position.x - (tg["node"] as Node3D).position.x) <= ATTACK_RANGE + 0.25

# Início do golpe: o atacante encara o alvo e balança a arma / arma o tiro.
func _begin(e: Dictionary) -> void:
	var ty := str(e.get("type", ""))
	# micro-timing: varia o ritmo (mata o "metrônomo"); crit telegrafado com mais anticipação
	cur_windup = WINDUP * (1.5 if ty == "crit" else randf_range(0.82, 1.3))
	cur_recover = RECOVER * randf_range(0.85, 1.3)
	var swinger := str(e.get("target", "")) if ty == "dodge" else str(e.get("actor", ""))
	var faces := str(e.get("actor", "")) if ty == "dodge" else str(e.get("target", ""))
	var sw = fighters.get(swinger)
	if sw == null or sw["dead"]:
		return
	var other = fighters.get(faces)
	if other:
		_face(sw, signf((other["node"] as Node3D).position.x - (sw["node"] as Node3D).position.x))
	sw["busy"] = true
	if sw["anim"]:
		sw["anim"].play(A_SHOOT if sw["ranged"] else A_ATTACK, BLEND)

# Resolve o evento: aplica dano/HP/flinch/flecha/popup/morte (espelha o antigo impact+step_end).
func _resolve(e: Dictionary) -> void:
	var ty := str(e.get("type", ""))
	var act = fighters.get(str(e.get("actor", "")))
	var tgt = fighters.get(str(e.get("target", "")))
	var dmg := int(e.get("damage", 0))
	if ty == "spawn":
		_handle_spawn(e)
	elif ty == "victory":
		if tgt and not tgt["dead"]: _kill(tgt)
	elif ty == "heal" and act:
		act["hp"] = int(e.get("targetHp", act["hp"])); _update_hp(act)
		_popup(_head(act), "+%d" % dmg, Color(0.49, 0.99, 0.6), false)
	elif ty == "berserk" and act:
		_popup(_head(act), "BERSERK", Color(1, 0.4, 0.2), true)
	elif ty in HIT_TYPES and tgt:
		if dmg > 0 and not tgt["dead"]:
			if act and act["ranged"]:
				_shoot_arrow(act, tgt)
			var head := str(e.get("hitZone", "")) == "head"
			if tgt["anim"]: tgt["anim"].play(A_HURT_HEAD if head else A_HURT, BLEND)
			tgt["busy"] = true
			var big := ty == "crit"
			_popup(_chest(tgt), "-%d" % dmg, Color(1, 0.32, 0.32) if big else Color(1, 1, 1), big)
			var elem := str(e.get("element", ""))
			if elem == "SUPER": _popup(_head(tgt), "✦", Color(1, 0.82, 0.29), false)
			elif elem == "RESIST": _popup(_head(tgt), "🛡", Color(0.5, 0.69, 1), false)
			# combo no CRIT: 2º golpe de espada no impacto (só temos Sword_Attack → reusa)
			if big and act and not act["ranged"] and act["anim"]:
				act["anim"].play(A_ATTACK, 0.05)
				act["busy"] = true
		tgt["hp"] = int(e.get("targetHp", tgt["hp"]))
		_update_hp(tgt)
		if tgt["hp"] <= 0: _kill(tgt)
	elif ty == "miss" and tgt:
		_popup(_head(tgt), "MISS", Color(0.62, 0.81, 1), false)
	elif ty == "dodge":
		var dodger = fighters.get(str(e.get("actor", "")))   # no dodge, o actor é quem esquiva
		if dodger:
			_popup(_head(dodger), "DODGE", Color(0.62, 0.81, 1), false)
			if tgt:   # rola PRA FRENTE através do atacante, caindo atrás dele com espaço
				_dodge_roll(dodger, tgt)
		if tgt: tgt["hp"] = int(e.get("targetHp", tgt["hp"])); _update_hp(tgt)   # reflect no atacante

func _handle_spawn(e: Dictionary) -> void:
	var who := str(e.get("actor", ""))
	var f = fighters.get(who)
	if f:   # re-init de HP no meio do stream (gauntlet/Torre)
		f["hp"] = int(e.get("targetMaxHp", f["maxhp"]))
		f["maxhp"] = max(f["maxhp"], f["hp"])
		f["dead"] = false
		_update_hp(f)
		if f["anim"]: f["anim"].play(A_IDLE)

func _finish() -> void:
	if phase == "done": return
	phase = "done"
	# identifica vencedor (vivo c/ mais HP) e perdedor; garante o perdedor DERROTADO + barra 0
	var winner: Dictionary = {}
	var loser: Dictionary = {}
	for f in order:
		if f["dead"]:
			loser = f
		elif winner.is_empty() or int(f["hp"]) > int(winner["hp"]):
			winner = f
	if loser.is_empty():   # decisão (ninguém morreu): o de menor HP perde
		for f in order:
			if not winner.is_empty() and f["name"] != winner["name"]:
				loser = f
		if not loser.is_empty(): _kill(loser)
	for f in order:        # zera de vez a barra de todos os mortos
		if f["dead"]:
			f["shown_hp"] = 0.0
			_apply_hp_bar(f)
	if not winner.is_empty() and victory_label:
		victory_label.text = "%s venceu!" % winner["name"]
		winner["busy"] = false
		if not loser.is_empty():
			_stand_over(winner, loser)          # vem pra frente do corpo
		elif winner["anim"]:
			winner["anim"].play(A_IDLE, BLEND)

# O vencedor caminha até ficar À FRENTE do corpo que acabou de matar e fica em guarda.
func _stand_over(winner: Dictionary, loser: Dictionary) -> void:
	var wn: Node3D = winner["node"]
	var ln: Node3D = loser["node"]
	var dir := signf(wn.position.x - ln.position.x)   # lado em que o vencedor está
	if dir == 0.0: dir = 1.0
	var stand_x := ln.position.x + dir * 0.9
	_face(winner, -dir)                               # encara o corpo
	if winner["anim"]: _play_loop(winner, A_RUN)
	var tw := create_tween()
	tw.tween_property(wn, "position", Vector3(stand_x, 0, 0), 0.5).set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_OUT)
	tw.tween_callback(func():
		if winner["anim"]: winner["anim"].play(A_IDLE, BLEND))

# ── helpers de cena / lutador (espelham Battle.gd) ──────────────────────────────
func _face(f: Dictionary, dir: float) -> void:
	if dir == 0.0: dir = 1.0
	f["face_target"] = deg_to_rad(90.0 if dir > 0 else -90.0)   # o _process gira suave até aqui

# Desenha a arma pelo TIPO (sword|bow|axe|spear|mace). Bow vai na LeftHand; o resto na
# RightHand num holder (rot -90; +Y local = direção da arma) com peças simples.
func _attach_weapon(node: Node3D, kind: String) -> void:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return
	var ba := BoneAttachment3D.new()
	if kind == "bow":
		ba.bone_name = "LeftHand"
		skel.add_child(ba)
		_box(ba, Vector3(0.03, 0.6, 0.03), Vector3(0.10, 0.07, 0.04), Color(0.45, 0.3, 0.16), 0.0)
		return
	ba.bone_name = "RightHand"
	skel.add_child(ba)
	var holder := Node3D.new()
	holder.position = Vector3(0.27, 0.05, 0.04)
	holder.rotation_degrees = Vector3(0, 0, -90)
	ba.add_child(holder)
	var steel := Color(0.82, 0.84, 0.88)
	var wood := Color(0.35, 0.22, 0.12)
	match kind:
		"axe":
			_box(holder, Vector3(0.028, 0.62, 0.028), Vector3(0, 0.20, 0), wood, 0.1)        # cabo longo
			_box(holder, Vector3(0.02, 0.16, 0.17),   Vector3(0, 0.46, 0.07), steel, 0.7)    # lâmina do machado
		"spear":
			_box(holder, Vector3(0.024, 0.95, 0.024), Vector3(0, 0.30, 0), wood, 0.1)         # haste
			_box(holder, Vector3(0.04, 0.16, 0.04),   Vector3(0, 0.82, 0), steel, 0.7)        # ponta
		"mace":
			_box(holder, Vector3(0.03, 0.42, 0.03),   Vector3(0, 0.12, 0), wood, 0.1)         # cabo
			_sphere(holder, 0.075, Vector3(0, 0.38, 0), Color(0.55, 0.56, 0.6), 0.6)          # cabeça
		_:  # sword (default)
			_box(holder, Vector3(0.022, 0.5, 0.075),  Vector3(0,  0.34, 0), steel, 0.7)           # lâmina
			_box(holder, Vector3(0.05, 0.035, 0.20),  Vector3(0,  0.07, 0), Color(0.28, 0.22, 0.14), 0.3)  # guarda
			_box(holder, Vector3(0.028, 0.13, 0.028), Vector3(0, -0.02, 0), wood, 0.1)             # cabo
			_box(holder, Vector3(0.05, 0.05, 0.05),   Vector3(0, -0.10, 0), Color(0.70, 0.60, 0.20), 0.5)  # pomo

func _box(parent: Node, size: Vector3, pos: Vector3, col: Color, metallic: float) -> void:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	mi.position = pos
	var m := StandardMaterial3D.new()
	m.albedo_color = col
	m.metallic = metallic
	mi.material_override = m
	parent.add_child(mi)

func _sphere(parent: Node, radius: float, pos: Vector3, col: Color, metallic: float) -> void:
	var mi := MeshInstance3D.new()
	var sm := SphereMesh.new()
	sm.radius = radius
	sm.height = radius * 2.0
	mi.mesh = sm
	mi.position = pos
	var m := StandardMaterial3D.new()
	m.albedo_color = col
	m.metallic = metallic
	mi.material_override = m
	parent.add_child(mi)

func _shoot_arrow(a: Dictionary, b: Dictionary) -> void:
	var arrow := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = Vector3(0.03, 0.03, 0.5)
	arrow.mesh = bm
	var mat := StandardMaterial3D.new()
	mat.albedo_color = Color(0.5, 0.35, 0.2)
	arrow.material_override = mat
	add_child(arrow)
	var start: Vector3 = _chest(a)
	var endp: Vector3 = _chest(b)
	var dist := start.distance_to(endp)
	# arco: ponto de controle no meio, um pouco acima (flecha sobe e desce)
	arrow.set_meta("p0", start)
	arrow.set_meta("p1", (start + endp) * 0.5 + Vector3(0, 0.2 + dist * 0.05, 0))
	arrow.set_meta("p2", endp)
	arrow.global_position = start
	var tw := create_tween()
	tw.tween_method(_arrow_step.bind(arrow), 0.0, 1.0, clampf(dist / 14.0, 0.12, 0.32))
	tw.tween_callback(arrow.queue_free)

# Move a flecha por uma bézier quadrática (arco) e a orienta na direção do voo. [game-feel]
func _arrow_step(t: float, arrow: MeshInstance3D) -> void:
	if not is_instance_valid(arrow): return
	var p0: Vector3 = arrow.get_meta("p0")
	var p1: Vector3 = arrow.get_meta("p1")
	var p2: Vector3 = arrow.get_meta("p2")
	var pos := p0.lerp(p1, t).lerp(p1.lerp(p2, t), t)
	var t2 := minf(t + 0.04, 1.0)
	var ahead := p0.lerp(p1, t2).lerp(p1.lerp(p2, t2), t2)
	arrow.global_position = pos
	if ahead.distance_to(pos) > 0.0005:
		arrow.look_at(ahead)

func _kill(f: Dictionary) -> void:
	if f["dead"]: return
	f["dead"] = true
	f["hp"] = 0          # vitória por decisão/timeout pode matar com HP>0
	f["shown_hp"] = 0.0  # zera a barra NA HORA da morte (o drain suave é só p/ golpes não-fatais)
	_apply_hp_bar(f)
	var node: Node3D = f["node"]
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel and _has_physical_bones(skel):
		if f["anim"]: f["anim"].stop()
		skel.physical_bones_start_simulation()
		var push := signf(node.position.x)
		if push == 0.0: push = 1.0
		for c in skel.get_children():
			if c is PhysicalBone3D and (c.bone_name in ["Spine", "Spine1", "Spine2", "Hips"]):
				(c as PhysicalBone3D).apply_central_impulse(Vector3(push * 2.5, 1.2, 0.0))
	elif f["anim"]:
		var d: Animation = f["anim"].get_animation(A_DEATH)
		if d: d.loop_mode = Animation.LOOP_NONE
		f["anim"].play(A_DEATH, BLEND)

func _has_physical_bones(skel: Skeleton3D) -> bool:
	for c in skel.get_children():
		if c is PhysicalBone3D:
			return true
	return false

# O alvo é f["hp"]; a barra DRENA suave no _process (shown_hp). Mantido p/ os call sites.
func _update_hp(_f: Dictionary) -> void:
	pass

func _apply_hp_bar(f: Dictionary) -> void:
	var ratio: float = clampf(f["shown_hp"] / float(f["maxhp"]), 0.0, 1.0)
	var fill: MeshInstance3D = f["fill"]
	fill.scale = Vector3(max(0.001, ratio), 1.0, 1.0)
	fill.position = Vector3(-BARW * 0.5 * (1.0 - ratio), 0.0, 0.0)
	(fill.material_override as StandardMaterial3D).albedo_color = \
		Color(0.85, 0.25, 0.25).lerp(Color(0.25, 0.85, 0.35), ratio)

func _head(f: Dictionary) -> Vector3:
	return (f["node"] as Node3D).global_position + Vector3(0, 1.7, 0)

func _chest(f: Dictionary) -> Vector3:
	return (f["node"] as Node3D).global_position + Vector3(0, 1.2, 0)

func _quad(w: float, h: float, col: Color, prio: int) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(w, h)
	mi.mesh = q
	var mat := StandardMaterial3D.new()
	mat.albedo_color = col
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA if col.a < 1.0 else BaseMaterial3D.TRANSPARENCY_DISABLED
	mat.render_priority = prio
	mat.no_depth_test = true
	mi.material_override = mat
	return mi

func _popup(pos: Vector3, text: String, color: Color, big: bool) -> void:
	var lbl := Label3D.new()
	lbl.text = text
	lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	lbl.no_depth_test = true
	lbl.modulate = color
	lbl.pixel_size = 0.008 if big else 0.006
	lbl.font_size = 80 if big else 64
	add_child(lbl)
	lbl.global_position = pos
	var tw := create_tween().set_parallel(true)
	tw.tween_property(lbl, "global_position", pos + Vector3(0, 0.8, 0), 0.8)
	tw.tween_property(lbl, "modulate:a", 0.0, 0.8)
	get_tree().create_timer(0.9).timeout.connect(lbl.queue_free)

func _setup_scene() -> void:
	cam = Camera3D.new()
	cam.position = Vector3(0.0, 3.0, 5.5)   # default de 1v1; _frame_camera() reenquadra após o spawn
	cam.look_at_from_position(cam.position, Vector3(0, 1.0, 0), Vector3.UP)
	add_child(cam)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, -30, 0)
	sun.light_energy = 1.2
	sun.shadow_enabled = true
	add_child(sun)
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.15, 0.15, 0.2)
	env.ambient_light_energy = 0.85
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	var ground := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(24, 24)
	var gmat := StandardMaterial3D.new()
	gmat.albedo_color = Color(0.22, 0.2, 0.18)
	ground.material_override = gmat
	ground.mesh = pm
	add_child(ground)
	var floor_body := StaticBody3D.new()
	var floor_col := CollisionShape3D.new()
	floor_col.shape = WorldBoundaryShape3D.new()
	floor_body.add_child(floor_col)
	add_child(floor_body)

# Enquadra a câmera pela quantidade de lutadores: 1v1 fica perto; formações grandes
# afastam e sobem. (Hoje só há 1v1; o termo por-lutador já deixa pronto p/ 3×5 etc.)
func _frame_camera() -> void:
	if cam == null: return
	var n := order.size()
	# kiting espalha os lutadores até a borda → enquadra mais largo
	var half := FIELD_EDGE if kiting else COMBAT_X + maxf(0.0, n - 2) * 0.9
	var dist := clampf((half + 1.4) * 1.9, 5.0, 16.0)
	cam.position = Vector3(0, dist * 0.55, dist)
	cam.look_at(Vector3(0, 1.0, 0), Vector3.UP)

func _make_ui() -> void:
	var layer := CanvasLayer.new()
	add_child(layer)
	victory_label = Label.new()
	victory_label.add_theme_font_size_override("font_size", 42)
	victory_label.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	victory_label.offset_top = 40
	victory_label.offset_bottom = 110
	victory_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	layer.add_child(victory_label)
	status_label = Label.new()
	status_label.add_theme_font_size_override("font_size", 18)
	status_label.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	status_label.offset_top = -44
	status_label.offset_bottom = -12
	status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	layer.add_child(status_label)
	countdown_label = Label.new()
	countdown_label.add_theme_font_size_override("font_size", 120)
	countdown_label.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	countdown_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	countdown_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	layer.add_child(countdown_label)

func _status(msg: String) -> void:
	if status_label: status_label.text = msg

# Luta MOCK (fallback se o login/arena falhar) — exercita todos os tipos de evento.
# Luta MOCK curta e "punchy" (poucos golpes, dano alto) — fácil de seguir e bem sincronizada.
# mock_enemy_wins=true → o Bandido (espada) vence (testa o melee ganhando o arqueiro).
func _mock_events() -> Array:
	# Narrativa: o arqueiro PEPPERA (vários hits pequenos) enquanto o guerreiro corre até ele;
	# o guerreiro chega e dá POUCOS golpes GRANDES; aí a troca fica equilibrada.
	# (Os hits do guerreiro só disparam quando ele alcança — o sim segura por alcance.)
	if mock_enemy_wins:
		# o guerreiro (espada) vence: dá 2 golpões, o arqueiro SALTA pra longe, e ele alcança de novo.
		return [
			{"type": "spawn", "actor": "Você", "target": "", "damage": 0, "targetHp": 100, "targetMaxHp": 100, "element": "", "hitZone": ""},
			{"type": "spawn", "actor": "Bandido", "target": "", "damage": 0, "targetHp": 100, "targetMaxHp": 100, "element": "", "hitZone": ""},
			{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 11, "targetHp": 89, "targetMaxHp": 100, "element": "", "hitZone": "body"},
			{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 9,  "targetHp": 80, "targetMaxHp": 100, "element": "", "hitZone": "legs"},
			{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 12, "targetHp": 68, "targetMaxHp": 100, "element": "", "hitZone": "body"},
			{"type": "crit",   "actor": "Bandido", "target": "Você", "damage": 28, "targetHp": 72, "targetMaxHp": 100, "element": "SUPER", "hitZone": "head"},  # golpão 1
			{"type": "attack", "actor": "Bandido", "target": "Você", "damage": 24, "targetHp": 48, "targetMaxHp": 100, "element": "", "hitZone": "body"},      # golpão 2
			{"type": "dodge",  "actor": "Você", "target": "Bandido", "damage": 0,  "targetHp": 68, "targetMaxHp": 100, "element": "", "hitZone": ""},          # SALTA pra longe
			{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 10, "targetHp": 58, "targetMaxHp": 100, "element": "", "hitZone": "body"},      # peppera de novo
			{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 9,  "targetHp": 49, "targetMaxHp": 100, "element": "", "hitZone": "legs"},
			{"type": "attack", "actor": "Bandido", "target": "Você", "damage": 26, "targetHp": 22, "targetMaxHp": 100, "element": "", "hitZone": "body"},      # alcançou de novo
			{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 11, "targetHp": 38, "targetMaxHp": 100, "element": "", "hitZone": "body"},
			{"type": "attack", "actor": "Bandido", "target": "Você", "damage": 22, "targetHp": 0,  "targetMaxHp": 100, "element": "", "hitZone": "body"},      # finaliza
			{"type": "victory", "actor": "Bandido", "target": "Você", "damage": 0, "targetHp": 0, "targetMaxHp": 100, "element": "", "hitZone": ""},
		]
	# o arqueiro vence: peppera muito no começo, salta quando o guerreiro chega, e fecha.
	return [
		{"type": "spawn", "actor": "Você", "target": "", "damage": 0, "targetHp": 100, "targetMaxHp": 100, "element": "", "hitZone": ""},
		{"type": "spawn", "actor": "Bandido", "target": "", "damage": 0, "targetHp": 100, "targetMaxHp": 100, "element": "", "hitZone": ""},
		{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 10, "targetHp": 90, "targetMaxHp": 100, "element": "", "hitZone": "body"},
		{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 12, "targetHp": 78, "targetMaxHp": 100, "element": "", "hitZone": "legs"},
		{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 9,  "targetHp": 69, "targetMaxHp": 100, "element": "", "hitZone": "body"},
		{"type": "attack", "actor": "Bandido", "target": "Você", "damage": 24, "targetHp": 76, "targetMaxHp": 100, "element": "", "hitZone": "body"},          # golpão 1
		{"type": "attack", "actor": "Bandido", "target": "Você", "damage": 22, "targetHp": 54, "targetMaxHp": 100, "element": "", "hitZone": "body"},          # golpão 2
		{"type": "dodge",  "actor": "Você", "target": "Bandido", "damage": 0,  "targetHp": 69, "targetMaxHp": 100, "element": "", "hitZone": ""},              # SALTA pra longe
		{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 13, "targetHp": 56, "targetMaxHp": 100, "element": "", "hitZone": "body"},          # peppera e fecha
		{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 11, "targetHp": 45, "targetMaxHp": 100, "element": "", "hitZone": "legs"},
		{"type": "crit",   "actor": "Você", "target": "Bandido", "damage": 28, "targetHp": 17, "targetMaxHp": 100, "element": "SUPER", "hitZone": "head"},
		{"type": "attack", "actor": "Você", "target": "Bandido", "damage": 17, "targetHp": 0,  "targetMaxHp": 100, "element": "", "hitZone": "body"},
		{"type": "victory", "actor": "Você", "target": "Bandido", "damage": 0, "targetHp": 0, "targetMaxHp": 100, "element": "", "hitZone": ""},
	]
