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
const Scenery := preload("res://Scenery.gd")
const Monsters := preload("res://Monsters.gd")
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

# UAL2 (lib que o dono baixou) — variações de golpe de espada (A/B/C) + combo no crit
const LIB2 := "UAL2_Standard/"
const UAL2_PATH := "res://addons/quaternius_ik_rigged/UAL2_Standard.glb"
const SWORD_ATTACKS := [LIB2 + "Sword_Regular_A", LIB2 + "Sword_Regular_B"]   # C ficou bugado → fora
const SWORD_COMBO := LIB2 + "Sword_Regular_Combo"
const BARW := 0.7

# combate dirigido por SIMULAÇÃO: o movimento é CONTÍNUO; cada evento de dano só dispara
# quando o lutador está em posição (com timeout). Mesmos eventos/resultado do backend.
const ATTACK_RANGE := 1.6    # distância em que o melee para p/ golpear (longe o bastante p/ não encostar a cabeça, mas a espada conecta)
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

# Câmera: 3 presets de enquadramento por TAMANHO da luta.
#   1 = 1v1 (perto) · 2 = até 3×3 · 3 = 5×5 (largo, mais alto).
# Troque AO VIVO com as teclas 1/2/3. cam_preset=0 (default) escolhe sozinho pela qtde de lutadores.
const CAM_PRESETS := [
	{"dist": 4.6,  "height": 2.3,  "look_y": 1.2},   # 1 — 1v1
	{"dist": 8.2,  "height": 4.4,  "look_y": 1.5},   # 2 — até 3×3
	{"dist": 12.5, "height": 7.2,  "look_y": 1.9},   # 3 — 5×5
]

# tipos de evento (idênticos ao battleArena.js 2D)
const HIT_TYPES := ["attack", "crit", "volley", "extra"]       # carregam dano/HP
const SWING_TYPES := ["attack", "crit", "volley", "extra", "miss", "dodge"]  # atacante balança a arma
const RANGED_MARKERS := ["volley", "pinned", "pointblank", "backpedal"]  # delatam um lutador ranged
const SCENARIOS := ["mining", "beach", "garimpa", "dungeon", "arena", "city", "castle"]  # mapas p/ sorteio
# Bestas (nomes estilo backend) p/ o modo "monster" sortear — todos casam um monstro em Monsters.pick_for.
const SHOWCASE_FOES := ["Young Dragon", "Lesser Demon", "Stone Golem", "Sea Serpent", "Mountain Troll",
	"Rock Spider", "Mine Wraith", "Giant Boar", "Dark Lich", "Colossal Crab", "Crystal Aberration", "Cursed Drowned"]

# [GORE] sangue (sem economia) — cores + caps de performance
const BLOOD_HIGH := Color(0.55, 0.02, 0.02)
const BLOOD_LOW := Color(0.22, 0.0, 0.0)
const POOL_TINT := Color(0.35, 0.0, 0.0)
const MAX_SPRAYS := 12
const MAX_POOLS := 26
# [GORE] desmembramento: pedaços/membros voando (RigidBody) — cores de carne/sangue + cap
const MAX_GIBS := 40
const GORE_COLORS := [Color(0.5, 0.08, 0.08), Color(0.42, 0.05, 0.05), Color(0.62, 0.22, 0.18), Color(0.45, 0.12, 0.1)]

## Credenciais (sobrepostas por login.cfg se existir). adm/adm123 só vale no DEV local.
@export var username := "adm"
@export var password := "adm123"
## Vazio = URL padrão do BackendClient (Railway). "http://localhost:8080" no dev local.
@export var base_url_override := ""
## Fonte da luta:
##   "arena"   = duelo PvP real do backend (oponente = OUTRO PLAYER → humano).
##   "monster" = luta MOCK local contra um MONSTRO (herói real vs bicho) — sempre funciona, sem backend.
##   "tower"   = PvE REAL da Torre (enter+fight) — luta garantida, mas inimigo HUMANOIDE/eldritch.
##   "quest"   = PvE REAL de quest de reino — rende BESTAS (monstro de verdade). Limitado pelo cap diário;
##               se não houver encontro disponível, cai no mock de monstro.
@export_enum("arena", "monster", "tower", "quest") var fight_source := "arena"
## Cenário de fundo. VAZIO = sorteia um mapa aleatório a cada luta. Fixe um nome (mining/beach/
## garimpa/dungeon/arena/city/castle) p/ travar, ou "coliseum" p/ o coliseu procedural antigo.
@export var scenario := ""
## TESTE: pula o backend e usa um duelo MOCK (espada vs espada) — bom p/ ver o combate sem login.
@export var force_mock := false
## TESTE: no mock, faz o Bandido (espada) VENCER — p/ ver como fica quando o melee ganha o arqueiro.
@export var mock_enemy_wins := false
## Troca o INIMIGO (direita) por um monstro ESPECÍFICO do bundle (override manual de teste).
## Vazio = decide pelo NOME do inimigo (Monsters.pick_for). Ex.: "Demon", "Dragon", "Ghost Skull".
@export var enemy_monster := ""
## TESTE: finge que o inimigo do backend tem ESTE nome (p/ ver o mapa nome→monstro sem PvE real).
## Ex.: "Young Dragon" → Dragon · "Stone Golem" → Goleling Evolved · "Orc Warrior" → humano.
@export var force_enemy_name := ""
## TESTE: força o tipo VISUAL da arma do herói (sem equipar no jogo). Vazio = arma real do inventário.
## Valores: sword | greatsword | axe | spear | mace | shortbow | longbow | crossbow
@export var force_weapon := ""
## TESTE: força um ESCUDO na off-hand do herói (some com arco). Vazio/false = só se equipado de verdade.
@export var force_shield := false
## (Legado) escala manual do monstro — hoje o tamanho vem do roster (Monsters.size_for) + auto-fit.
@export var monster_scale := 1.0
## Giro extra do monstro em Y (graus) se ele nascer de lado/de costas. Tente 0, 90, 180, -90.
@export var monster_face_offset_deg := 0.0
## Câmera: 0 = AUTO (escolhe pela qtde de lutadores) · 1 = 1v1 · 2 = até 3×3 · 3 = 5×5. Teclas 1/2/3 trocam ao vivo.
@export var cam_preset := 0
## Pós-processo grimdark (vinheta + grade + bloom/SSAO) nos mapas do Scenery. [GODOT_GRIMDARK]
@export var grimdark := true

var events: Array = []
var fighters := {}          # name -> dict do lutador
var order: Array = []       # [left, right] na ordem de spawn
var player_equip: Array = []  # tipos de armadura EQUIPADOS pelo jogador (p/ vestir o lutador da esquerda)
var player_weapon := ""       # tipo visual fino: sword|greatsword|axe|spear|mace|shortbow|longbow|crossbow
var cam: Camera3D
var mons := Monsters.new()  # helper de monstros (instancia + auto-fit + roster/mapa)
var cam_view := 1            # preset de câmera ATIVO (1/2/3) — ver CAM_PRESETS
var cam_hint: Label          # dica no canto: "📷 Cam N  [1/2/3]"

# kiting: ativo quando EXATAMENTE um lado é ranged (arco) e o outro melee
var kiting := false
var ranged_f := {}            # lutador que recua/atira
var melee_f := {}             # lutador que avança
var victory_label: Label
var status_label: Label
var chosen_scenario := ""   # mapa sorteado/usado nesta luta
var fight_scene := ""        # "scene" que o backend mandou (coast/sea/cave/fortress/tower/arena) → casa o mapa
var _spray_live := 0        # [GORE] emissores de sangue vivos (cap)
var _pools: Array = []      # [GORE] poças/decais ativos (cap)
var _gibs: Array = []       # [GORE] pedaços/membros (RigidBody) ativos (cap)

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
	_setup_camera()
	_make_ui()
	await _load_events()        # define fight_scene (scene do backend)
	if events.is_empty():
		return
	_setup_map()                # monta o mapa JÁ sabendo o reino da luta (scene → mapa)
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

	if fight_source == "monster":   # luta MOCK local contra um MONSTRO (herói real vs bicho)
		var foe: String = enemy_monster if enemy_monster != "" else SHOWCASE_FOES[randi() % SHOWCASE_FOES.size()]
		events = _mock_monster_events(foe)
		_status("Monstro: %s (mock local)" % foe)
		print("=== fight_source=monster: herói real vs %s ===" % foe)
		return

	# Fonte da luta (real, do backend). Cada uma devolve json.battleEvents no MESMO formato.
	var ok := false
	match fight_source:
		"tower": ok = await _load_tower(client)   # PvE garantido (inimigo humanoide/eldritch)
		"quest": ok = await _load_quest(client)   # PvE de besta (monstro de verdade)
		_:       ok = await _load_arena(client)   # PvP (oponente humano)
	if ok:
		return
	# falhou → fallback: arena cai no duelo MOCK; PvE cai num MOCK de monstro (ainda mostra bicho)
	if fight_source == "arena":
		events = _mock_events()
		_status("Arena indisponível — luta MOCK.")
	else:
		var foe: String = SHOWCASE_FOES[randi() % SHOWCASE_FOES.size()]
		events = _mock_monster_events(foe)
		_status("PvE indisponível — mock de monstro (%s)." % foe)

# Extrai json.battleEvents (qualquer fonte) → events + status. true se veio luta válida (>=2 spawns).
# label_keys = chaves do NOME do oponente (opponent/bossName/monsterName); win_key = chave de vitória.
func _apply_fight_json(j: Dictionary, label_keys: Array, win_key: String) -> bool:
	var be = j.get("battleEvents")
	if not (be is Array and be.size() >= 2):
		return false
	events = be
	fight_scene = str(j.get("scene", ""))   # casa o mapa com o reino da luta (_setup_map)
	var foe := "?"
	for k in label_keys:
		if j.has(k) and str(j[k]) != "":
			foe = str(j[k]); break
	var who := "venceu" if j.get(win_key, false) else "perdeu"
	_status("%s vs %s — você %s!" % [username, foe, who])
	print(">>> luta OK (%s): vs %s, %d eventos, scene=%s" % [fight_source, foe, be.size(), str(j.get("scene", ""))])
	return true

func _load_arena(client) -> bool:
	_status("Lutando na arena…")
	var fr = await client.arena_fight()
	if fr.get("ok") and fr.get("json") is Dictionary:
		return _apply_fight_json(fr["json"], ["opponent"], "won")
	print(">>> ARENA FALHOU: %s | raw: %s" % [fr.get("status"), fr.get("raw", "")])
	return false

func _load_tower(client) -> bool:
	_status("Subindo a Torre…")
	var fr = await client.tower_fight()
	# tower_fight exige run ativa; se não houver, entra e tenta de novo
	if not (fr.get("ok") and fr.get("json") is Dictionary and fr["json"].get("battleEvents") is Array):
		await client.tower_enter()
		fr = await client.tower_fight()
	if fr.get("ok") and fr.get("json") is Dictionary:
		return _apply_fight_json(fr["json"], ["bossName"], "won")
	print(">>> TORRE FALHOU: %s | raw: %s" % [fr.get("status"), fr.get("raw", "")])
	return false

# Reinos com BESTAS (não humanoides) primeiro. Dirige pelo GET de quests (não chuta questType).
const QUEST_KINGDOMS := ["MAR_ABENCOADO", "GRUTAS_DE_CRISTAL", "FISHING", "MINING", "COMBAT"]
const QUEST_COMBAT_HINT := ["HUNT", "SLAY", "GUARD", "DEFEND", "CULL", "RAID", "WARLORD", "MONSTER", "BEAST", "KRAKEN", "PATROL"]

func _load_quest(client) -> bool:
	for kingdom in QUEST_KINGDOMS:
		var lr = await client.quest_list(kingdom)
		if not (lr.get("ok") and lr.get("json") is Array):
			print(">>> quest[%s]: lista falhou (status %s)" % [kingdom, lr.get("status")])
			continue
		# escolhe uma quest COMEÇÁVEL, NÃO-interativa (sem optionId), de preferência "de combate"
		var pick := {}
		var n_start := 0   # quantas dava p/ começar (diagnóstico)
		var n_done := 0    # quantas já feitas hoje (cap diário)
		for q in lr["json"]:
			if not (q is Dictionary): continue
			if q.get("doneToday", false): n_done += 1
			if not q.get("canStart", false): continue
			if q.get("interactive", false): continue
			n_start += 1
			var qid := str(q.get("id", ""))
			var is_combat := false
			for h in QUEST_COMBAT_HINT:
				if h in qid: is_combat = true; break
			if is_combat:
				pick = q; break
			elif pick.is_empty():
				pick = q
		if pick.is_empty():
			print(">>> quest[%s]: nenhuma começável não-interativa (%d feitas hoje, %d começáveis)" % [kingdom, n_done, n_start])
			continue
		var qtype := str(pick.get("id", ""))
		_status("Quest %s (%s)…" % [str(pick.get("displayName", qtype)), kingdom])
		var sr = await client.quest_start(kingdom, qtype)
		if not (sr.get("ok") and sr.get("json") is Dictionary):
			print(">>> quest[%s]: start '%s' falhou (status %s, raw %s)" % [kingdom, qtype, sr.get("status"), sr.get("raw", "")])
			continue
		var qnum := int(sr["json"].get("id", 0))
		if qnum == 0:
			print(">>> quest[%s]: start '%s' sem id" % [kingdom, qtype])
			continue
		var cr = await client.quest_collect(kingdom, qnum)
		if not (cr.get("ok") and cr.get("json") is Dictionary):
			print(">>> quest[%s]: collect %d falhou (status %s)" % [kingdom, qnum, cr.get("status")])
			continue
		var j: Dictionary = cr["json"]
		if j.get("lunaPending", false):   # Luna interrompeu → ignora (retoma a missão) e usa o resultado
			print(">>> quest[%s]: Luna interrompeu → /luna/ignore" % kingdom)
			var luna = await client.quest_luna(kingdom, qnum, "ignore")
			if luna.get("ok") and luna.get("json") is Dictionary:
				j = luna["json"]
		if _apply_fight_json(j, ["monsterName"], "monsterDefeated"):
			return true
		# resolveu mas sem battleEvents → sem encontro de monstro nessa quest
		print(">>> quest[%s]: '%s' resolveu SEM encontro (monsterEncountered=%s) → próximo reino" % [kingdom, qtype, str(j.get("monsterEncountered", false))])
	print(">>> QUEST: nenhum encontro de monstro em nenhum reino (cap diário / sem combate).")
	return false

# ── monta os lutadores a partir dos eventos de spawn ────────────────────────────
func _build_fighters() -> void:
	var spawns: Array = events.filter(func(e): return str(e.get("type", "")) == "spawn")
	if spawns.size() < 2:
		_status("Eventos sem 2 spawns — nada a encenar.")
		return
	var lname := str(spawns[0].get("actor", "Hero"))
	var rname := str(spawns[1].get("actor", "Foe"))
	# HERÓI (esquerda = challenger): arma e equip REAIS. Sem arma equipada → espada.
	# force_weapon (Inspector) sobrepõe p/ TESTE — ver qualquer tipo sem equipar no jogo.
	var lweapon := force_weapon if force_weapon != "" else (player_weapon if player_weapon != "" else "sword")
	var lequip: Array = (player_equip if player_equip.size() > 0 else DEFAULT_OUTFIT).duplicate()
	if force_shield and not ("SHIELD" in lequip):
		lequip.append("SHIELD")   # TESTE: força o escudo
	# INIMIGO (direita): no force_mock vira espadachim; na arena real segue o estilo dos eventos.
	var rweapon := "sword" if force_mock else ("bow" if _is_ranged(rname) else "sword")
	# Representação do inimigo: monstro do bundle (besta) ou humano (cavaleiro/bandido/PvP).
	#   enemy_monster (override manual) > nome do backend (rname) via Monsters.pick_for.
	var emeta: Dictionary = {}
	if enemy_monster != "":
		emeta = mons.meta_for(enemy_monster)
	elif not force_mock:
		var enemy_name := force_enemy_name if force_enemy_name != "" else rname
		var pick := mons.pick_for(enemy_name)
		if pick.get("kind") == "monster":
			emeta = pick
			print(">>> inimigo '%s' → monstro %s (h=%.1f)" % [enemy_name, emeta["file"], emeta["target_h"]])
	order = [
		_make_fighter(lname, -1, int(spawns[0].get("targetMaxHp", 100)), lweapon, lequip, {}),
		_make_fighter(rname,  1, int(spawns[1].get("targetMaxHp", 100)), rweapon, DEFAULT_OUTFIT, emeta),
	]
	fighters[lname] = order[0]
	fighters[rname] = order[1]
	# kiting ativo quando só um lado é ranged: ele recua/atira, o outro avança.
	if order[0]["ranged"] != order[1]["ranged"]:
		kiting = true
		ranged_f = order[0] if order[0]["ranged"] else order[1]
		melee_f  = order[1] if order[0]["ranged"] else order[0]
		# posição inicial de kiting: SIMÉTRICA (ambos à mesma distância do centro)
		var rn: Node3D = ranged_f["node"]
		var mn: Node3D = melee_f["node"]
		rn.position = Vector3(ranged_f["side"] * 3.0, ranged_f["base_y"], 0)
		mn.position = Vector3(melee_f["side"] * 3.0, melee_f["base_y"], 0)

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
		elif ty == "SHIELD" and not ("SHIELD" in player_equip):
			player_equip.append("SHIELD")   # marca p/ desenhar o escudo na off-hand (_make_fighter)
		elif ty == "WEAPON":
			player_weapon = _weapon_kind(str(it.get("name", "")), str(it.get("weaponCategory", "")))

# Infere o tipo visual da arma pelo nome + categoria (backend só dá MELEE/RANGED).
# Tipo visual FINO da arma pelo NOME (espelha backend WeaponType.fromName — a API não manda o tipo).
# Ordem importa: ranged finos antes do "bow" genérico; greatsword antes de sword.
func _weapon_kind(item_name: String, category: String) -> String:
	var n := item_name.to_lower()
	if "crossbow" in n or "besta" in n: return "crossbow"
	if "long bow" in n or "longbow" in n or "arco longo" in n: return "longbow"
	if "short bow" in n or "shortbow" in n or "arco curto" in n or "bow" in n or "arco" in n: return "shortbow"
	if category == "RANGED": return "shortbow"   # rede de segurança (nome ranged sem palavra de arco)
	if "greatsword" in n or "great sword" in n or "two-handed" in n or "montante" in n or "espada longa" in n or "espada de duas" in n: return "greatsword"
	if "axe" in n or "machado" in n or "hatchet" in n: return "axe"
	if "mace" in n or "marreta" in n or "maul" in n or "hammer" in n or "martelo" in n or "club" in n or "clava" in n: return "mace"
	if "spear" in n or "lança" in n or "lanca" in n or "lance" in n or "pike" in n or "halberd" in n: return "spear"
	return "sword"

# Arma de longa distância (arco/besta)? — controla kiting + flecha + slot de mão (LeftHand). "bow" = legado.
func _is_bow_kind(kind: String) -> bool:
	return kind in ["shortbow", "longbow", "crossbow", "bow"]

func _is_ranged(who: String) -> bool:
	for e in events:
		if str(e.get("actor", "")) == who and str(e.get("type", "")) in RANGED_MARKERS:
			return true
	return false

func _make_fighter(fname: String, side: int, maxhp: int, weapon_kind: String, equipped_types: Array, monster_meta := {}) -> Dictionary:
	var is_monster := not monster_meta.is_empty()
	var node: Node3D = mons.instance(str(monster_meta.get("file", ""))) if is_monster else CHAR.instantiate()
	if node == null:   # monstro não carregou → cai no humano p/ não travar a cena
		node = CHAR.instantiate(); is_monster = false
	add_child(node)
	node.position = Vector3(ENTRY_X * side, 0, 0)
	var base_y := 0.0
	var bar_off := 2.05
	if is_monster:
		# auto-fit pelo bounding box → tamanho do roster + pé no chão / hover (voador)
		var th := float(monster_meta.get("target_h", Monsters.TARGET_H))
		var fit := mons.fit(node, th, float(monster_meta.get("hover", 0.0)))
		base_y = node.position.y                        # fit já setou y = ground_y + hover
		bar_off = th - float(fit["ground_y"]) + 0.4     # acima da cabeça (independe do hover)
	else:
		node.scale = Vector3(0.92, 0.92, 0.92)
	# monstro é sempre melee (sem arco/kite); herói/humano segue a arma equipada
	var ranged := _is_bow_kind(weapon_kind) and not is_monster
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	# liga a lib UAL2 (variações de espada) — só no humano; monstro usa as próprias anims
	if ap and not is_monster and not ap.has_animation_library("UAL2_Standard"):
		var lib2 = load(UAL2_PATH)
		if lib2 is AnimationLibrary:
			ap.add_animation_library("UAL2_Standard", lib2)
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	var yaw_off := deg_to_rad(monster_face_offset_deg) if is_monster else 0.0
	var f := {"name": fname, "node": node, "anim": ap, "side": side, "ranged": ranged,
			  "dead": false, "maxhp": max(1, maxhp), "hp": max(1, maxhp), "busy": false, "hopping": false,
			  "vel": 0.0, "shown_hp": float(max(1, maxhp)), "is_monster": is_monster, "yaw_offset": yaw_off,
			  "base_y": base_y, "bar_off": bar_off,
			  "amap": (_monster_anim_map(ap) if is_monster else {}),
			  "face_target": deg_to_rad(90.0 if -side > 0 else -90.0) + yaw_off}
	node.rotation.y = f["face_target"]   # nasce já virado pro centro (sem lerp do zero)
	_face(f, -side)   # seta o alvo de rotação (encara o oponente)
	if not is_monster:
		_dress(node, skel, equipped_types)   # [GODOT_PAPERDOLL] veste antes da arma
		_attach_weapon(node, weapon_kind)
		# escudo na off-hand (LeftHand) — só com arma MELEE (arco usa as duas mãos)
		if ("SHIELD" in equipped_types) and not _is_bow_kind(weapon_kind):
			_attach_shield(node)
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
		var il := ap.get_animation(_clip(f, "idle"))
		if il: il.loop_mode = Animation.LOOP_LINEAR
		# one-shot (attack/shoot/hurt) terminou → libera o lutador e volta pro idle.
		# (walk/jump/idle são LOOP → nunca disparam animation_finished)
		ap.animation_finished.connect(func(_a):
			f["busy"] = false
			if not f["dead"] and not f["hopping"]: ap.play(_clip(f, "idle"), BLEND))   # não corta um roll em andamento
		ap.play(_clip(f, "idle"))
	return f

# ── monstro (bundle Quaternius) ─────────────────────────────────────────────────
const MONSTERS_DIR := "res://assets/monsters/"

# Instancia um .glb de monstro do bundle (self-contained: mesh + rig + anims próprias).
func _instance_monster(file: String) -> Node3D:
	var path := file
	if not path.begins_with("res://"):
		path = MONSTERS_DIR + file + ("" if file.to_lower().ends_with(".glb") else ".glb")
	var scene = load(path)
	if scene == null:
		push_warning("monstro não encontrado: %s — usando humano." % path)
		return null
	return scene.instantiate() as Node3D

# Mapeia as anims do monstro (os nomes variam por bicho) → papéis do replay, por palavra-chave.
# Assim qualquer monstro do bundle funciona sem hard-code do set de animações dele.
func _monster_anim_map(ap: AnimationPlayer) -> Dictionary:
	if ap == null: return {}
	var names := ap.get_animation_list()
	var pick := func(keys: Array) -> String:
		for k in keys:
			for n in names:
				if k in String(n).to_lower():
					return String(n)
		return ""
	var m := {}
	m["idle"]      = pick.call(["idle"])
	m["run"]       = pick.call(["run", "gallop", "jog", "fly", "walk"])
	m["walk"]      = pick.call(["walk", "run", "fly"])
	m["attack"]    = pick.call(["attack", "bite", "punch", "headbutt", "clobber", "spit", "cast", "melee", "swip"])
	m["hurt"]      = pick.call(["hit", "recieve", "receive", "hurt", "damage", "flinch", "stun"])
	m["death"]     = pick.call(["death", "die", "dead", "defeat"])
	m["dance"]     = pick.call(["cheer", "dance", "yes", "jump", "idle"])
	m["roll"]      = pick.call(["roll", "dodge", "jump"])
	m["hurt_head"] = m["hurt"]
	m["shoot"]     = m["attack"]
	var fallback := String(names[0]) if names.size() > 0 else ""
	for key in m.keys():   # papel sem clip → cai no idle (ou na 1ª anim do bicho)
		if m[key] == "":
			m[key] = m["idle"] if m["idle"] != "" else fallback
	# loop nos contínuos; one-shot nos pontuais (p/ animation_finished disparar e voltar pro idle)
	for key in ["idle", "run", "walk", "dance"]:
		var a := ap.get_animation(m[key])
		if a: a.loop_mode = Animation.LOOP_LINEAR
	for key in ["attack", "hurt", "death", "roll"]:
		var a := ap.get_animation(m[key])
		if a: a.loop_mode = Animation.LOOP_NONE
	print(">>> monstro anims=%s → map=%s" % [str(names), str(m)])
	return m

# Resolve o clip de uma ação ("idle","run","attack","hurt","death",…) p/ ESTE lutador:
# monstro usa o próprio mapa; humano usa as constantes UAL de sempre.
func _clip(f: Dictionary, role: String) -> String:
	if f.get("is_monster", false):
		var m: Dictionary = f.get("amap", {})
		return str(m.get(role, m.get("idle", A_IDLE)))
	match role:
		"idle": return A_IDLE
		"run": return A_RUN
		"walk": return A_WALK
		"dance": return A_DANCE
		"hurt": return A_HURT
		"hurt_head": return A_HURT_HEAD
		"death": return A_DEATH
		"roll": return A_ROLL
		"shoot": return A_SHOOT
		_: return A_IDLE

# ── director dirigido por SIMULAÇÃO ─────────────────────────────────────────────
# Todo frame: (1) move os lutadores de forma contínua; (2) avança o cursor de eventos,
# disparando o golpe só quando o atacante está em posição (ou após timeout). Assim o
# golpe nunca bate no ar nem teleporta — e o movimento flui.
func _process(dt: float) -> void:
	# câmera: 1/2/3 trocam o preset de enquadramento AO VIVO (1v1 · 3×3 · 5×5)
	if Input.is_key_pressed(KEY_1) and cam_view != 1: cam_view = 1; _apply_camera()
	elif Input.is_key_pressed(KEY_2) and cam_view != 2: cam_view = 2; _apply_camera()
	elif Input.is_key_pressed(KEY_3) and cam_view != 3: cam_view = 3; _apply_camera()
	for f in order:
		if not is_instance_valid(f["node"]): continue
		var n := f["node"] as Node3D
		# virada SUAVE até o alvo (sem snap de 90°) — só vivo (o cadáver fica como caiu)
		if not f["dead"]:
			n.rotation.y = lerp_angle(n.rotation.y, f["face_target"], 1.0 - exp(-TURN_SPEED * dt))
		# barra: segue a cabeça + DRENA suave (shown_hp → hp) p/ leitura de impacto
		if f.has("bar"):
			f["bar"].global_position = n.global_position + Vector3(0, f.get("bar_off", 2.05), 0)
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
		if not f["dead"] and f["anim"]: _play_loop(f, _clip(f, "dance"))
	var remaining := COUNTDOWN - countdown_t
	if remaining > 0.0:
		countdown_label.text = str(int(ceil(remaining)))
	elif countdown_t < COUNTDOWN + 0.7:
		countdown_label.text = "Lutar!"
	else:
		countdown_label.text = ""
		for f in order:
			if f["anim"]: f["anim"].play(_clip(f, "idle"), BLEND)
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

	# Arqueiro rolando ATRAVÉS → CONGELA o guerreiro p/ ele ser ultrapassado de verdade.
	# NÃO re-vira aqui (o arqueiro cruza e o side inverte → daria giro de 180° em pé, feio);
	# o melee mantém a direção e só vira DEPOIS do roll, ao recomeçar a perseguição (1 giro natural).
	if ranged_f["hopping"]:
		melee_f["vel"] = 0.0
		if not melee_f["busy"] and melee_f["anim"] and melee_f["anim"].current_animation != _clip(melee_f, "idle"):
			melee_f["anim"].play(_clip(melee_f, "idle"), BLEND)
		return

	# MELEE corre pra fechar até o alcance (com aceleração/frenagem → não parte/para seco)
	var prev_m := mn.position.x
	var desired_m := rn.position.x - side * ATTACK_RANGE
	var new_m := _step_toward(melee_f, desired_m, MELEE_SPEED, dt)
	_face(melee_f, side)
	_locomotion(melee_f, prev_m, new_m)

	# ARQUEIRO: encara; pressionado recua ANDANDO; encurralado na borda PARA (o guerreiro o alcança)
	_face(ranged_f, -side)
	var prev_a := rn.position.x
	if gap < ARCHER_PREF:
		var next_x := clampf(rn.position.x + side * ARCHER_SPEED * dt, -FIELD_EDGE, FIELD_EDGE)
		rn.position = Vector3(next_x, ranged_f["base_y"], 0)
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
			if not f["busy"] and f["anim"] and f["anim"].current_animation != _clip(f, "idle"):
				f["anim"].play(_clip(f, "idle"), BLEND)

# Move o lutador rumo a desired_x com ACELERAÇÃO + frenagem perto do alvo (planta o pé,
# em vez de partir/parar seco). Retorna o novo x. [game-feel]
func _step_toward(f: Dictionary, desired_x: float, max_speed: float, dt: float) -> float:
	var n: Node3D = f["node"]
	var dist := desired_x - n.position.x
	var target_v := clampf(absf(dist) / 0.22, 0.0, max_speed) * signf(dist)   # zona de frenagem
	var vel: float = move_toward(float(f["vel"]), target_v, ACCEL * dt)
	f["vel"] = vel
	var new_x: float = n.position.x + vel * dt
	n.position = Vector3(new_x, f["base_y"], 0)
	return new_x

# Run quando se move, idle quando parado (não interrompe golpe/flinch em andamento).
func _locomotion(f: Dictionary, prev_x: float, now_x: float) -> void:
	if f["busy"]: return
	var ap: AnimationPlayer = f["anim"]
	if ap == null: return
	if absf(now_x - prev_x) > 0.004:
		_play_loop(f, _clip(f, "run"))
	elif ap.current_animation != _clip(f, "idle"):
		ap.play(_clip(f, "idle"), BLEND)

# Toca uma animação em loop (com fallback p/ Walk se o clip não existir na lib).
func _play_loop(f: Dictionary, anim_name: String) -> void:
	var ap: AnimationPlayer = f["anim"]
	if ap == null: return
	var nm := anim_name if ap.has_animation(anim_name) else _clip(f, "walk")
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
	tw.tween_property(n, "position", Vector3(land_x, dodger["base_y"], 0), dur)   # roll = velocidade constante
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
		var clip: String
		if sw.get("is_monster", false):
			clip = _clip(sw, "attack")          # monstro tem 1 ataque próprio
		elif sw["ranged"]:
			clip = A_SHOOT
		else:
			clip = _combo_clip(sw["anim"]) if ty == "crit" else _rand_sword(sw["anim"])
		sw["anim"].play(clip, BLEND)

# Golpe de espada aleatório (A/B/C da UAL2; fallback Sword_Attack da UAL1).
func _rand_sword(ap: AnimationPlayer) -> String:
	var pool: Array = SWORD_ATTACKS.filter(func(a): return ap.has_animation(a))
	if pool.is_empty(): return A_ATTACK
	return pool[randi() % pool.size()]

# Combo do crit (UAL2); fallback p/ um golpe normal se não existir.
func _combo_clip(ap: AnimationPlayer) -> String:
	return SWORD_COMBO if ap.has_animation(SWORD_COMBO) else _rand_sword(ap)

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
			if tgt["anim"]: tgt["anim"].play(_clip(tgt, "hurt_head" if head else "hurt"), BLEND)
			tgt["busy"] = true
			var big := ty == "crit"
			_popup(_chest(tgt), "-%d" % dmg, Color(1, 0.32, 0.32) if big else Color(1, 1, 1), big)
			var elem := str(e.get("element", ""))
			if elem == "SUPER": _popup(_head(tgt), "✦", Color(1, 0.82, 0.29), false)
			elif elem == "RESIST": _popup(_head(tgt), "🛡", Color(0.5, 0.69, 1), false)
			# [GORE] sangue na zona do golpe, na direção atacante→alvo
			var bdir := Vector3.RIGHT
			if act: bdir = ((tgt["node"] as Node3D).global_position - (act["node"] as Node3D).global_position) * Vector3(1, 0, 1)
			# guarda a direção/força do golpe p/ o desmembramento direcional no _kill
			tgt["last_hit_dir"] = bdir.normalized() if bdir.length() > 0.01 else Vector3.RIGHT
			tgt["last_hit_crit"] = big
			var bpos := _hit_pos(tgt, str(e.get("hitZone", "")))
			_blood_spray(bpos, bdir, dmg, big, elem)
			if big: _blood_mist(bpos)
			if big or randf() < 0.45:
				_blood_pool((tgt["node"] as Node3D).global_position + bdir.normalized() * randf_range(0.2, 0.6), big)
		tgt["hp"] = int(e.get("targetHp", tgt["hp"]))
		_update_hp(tgt)
		if tgt["hp"] <= 0: _kill(tgt)
	elif ty == "miss" and tgt:
		_popup(_head(tgt), "MISS", Color(0.62, 0.81, 1), false)
	elif ty == "dodge":
		var dodger = fighters.get(str(e.get("actor", "")))   # no dodge, o actor é quem esquiva
		if dodger:
			_popup(_head(dodger), "DODGE", Color(0.62, 0.81, 1), false)
			# só o ARQUEIRO rola através (kiting); melee-vs-melee esquiva no lugar (sem roll)
			if tgt and dodger["ranged"]:
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
		if f["anim"]: f["anim"].play(_clip(f, "idle"))

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
		if not loser.is_empty() and not winner["ranged"]:
			_stand_over(winner, loser)          # MELEE vem pra frente do corpo
		else:
			# arqueiro (ou sem perdedor): fica ONDE ESTÁ, só encara o corpo
			if not loser.is_empty():
				_face(winner, signf((loser["node"] as Node3D).position.x - (winner["node"] as Node3D).position.x))
			if winner["anim"]: winner["anim"].play(_clip(winner, "idle"), BLEND)

# O vencedor caminha até ficar À FRENTE do corpo que acabou de matar e fica em guarda.
func _stand_over(winner: Dictionary, loser: Dictionary) -> void:
	var wn: Node3D = winner["node"]
	var ln: Node3D = loser["node"]
	var dir := signf(wn.position.x - ln.position.x)   # lado em que o vencedor está
	if dir == 0.0: dir = 1.0
	var stand_x := ln.position.x + dir * 0.9
	_face(winner, -dir)                               # encara o corpo
	if winner["anim"]: _play_loop(winner, _clip(winner, "run"))
	var tw := create_tween()
	tw.tween_property(wn, "position", Vector3(stand_x, winner["base_y"], 0), 0.5).set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_OUT)
	tw.tween_callback(func():
		if winner["anim"]: winner["anim"].play(_clip(winner, "idle"), BLEND))

# ── helpers de cena / lutador (espelham Battle.gd) ──────────────────────────────
func _face(f: Dictionary, dir: float) -> void:
	if dir == 0.0: dir = 1.0
	# + yaw_offset corrige o monstro que nasce de lado/de costas (humano = 0)
	f["face_target"] = deg_to_rad(90.0 if dir > 0 else -90.0) + f.get("yaw_offset", 0.0)   # o _process gira suave até aqui

# Desenha a arma pelo TIPO fino. Arco/besta vão na LeftHand; melee na RightHand num holder
# (rot -90; +Y local = direção da arma) com peças simples. 8 tipos do WeaponType.
func _attach_weapon(node: Node3D, kind: String) -> void:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return
	var ba := BoneAttachment3D.new()
	if _is_bow_kind(kind):
		ba.bone_name = "LeftHand"
		skel.add_child(ba)
		_attach_bow(ba, kind)
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
		"greatsword":   # espada de 2 mãos: lâmina mais longa/larga + guarda larga + cabo comprido
			_box(holder, Vector3(0.028, 0.82, 0.10), Vector3(0, 0.54, 0), steel, 0.7)              # lâmina longa
			_box(holder, Vector3(0.07, 0.04, 0.28),  Vector3(0, 0.10, 0), Color(0.28, 0.22, 0.14), 0.3)  # guarda larga
			_box(holder, Vector3(0.032, 0.22, 0.032),Vector3(0, -0.04, 0), wood, 0.1)              # cabo longo (2 mãos)
			_box(holder, Vector3(0.06, 0.06, 0.06),  Vector3(0, -0.18, 0), Color(0.70, 0.60, 0.20), 0.5)  # pomo
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

# Arco/besta na LeftHand. shortbow/longbow = arco vertical (stave + corda); crossbow = horizontal (coronha + braço).
func _attach_bow(ba: Node3D, kind: String) -> void:
	var wood := Color(0.45, 0.30, 0.16)
	if kind == "crossbow":
		_box(ba, Vector3(0.035, 0.05, 0.40), Vector3(0.10, 0.06, 0.10), wood, 0.1)                  # coronha (pra frente)
		_box(ba, Vector3(0.40, 0.03, 0.03),  Vector3(0.10, 0.08, 0.26), Color(0.30, 0.25, 0.18), 0.2)  # braço transversal
		_box(ba, Vector3(0.35, 0.006, 0.006),Vector3(0.10, 0.08, 0.25), Color(0.85, 0.82, 0.70), 0.0)  # corda
		return
	var h := 0.95 if kind == "longbow" else 0.55                                                      # longbow alto, shortbow baixo
	_box(ba, Vector3(0.03, h, 0.03),        Vector3(0.10, 0.07, 0.04), wood, 0.1)                     # corpo do arco
	_box(ba, Vector3(0.006, h * 0.95, 0.006), Vector3(0.10, 0.07, -0.02), Color(0.85, 0.82, 0.70), 0.0)  # corda

# Escudo (heater) na off-hand — corpo de madeira + borda metálica + umbo. Preso na LeftHand,
# face virada pra frente. Posição/rotação são chute (calibrar por screenshot, igual arco/besta).
func _attach_shield(node: Node3D) -> void:
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel == null: return
	var ba := BoneAttachment3D.new()
	ba.bone_name = "LeftHand"
	skel.add_child(ba)
	var holder := Node3D.new()
	holder.position = Vector3(-0.12, 0.06, 0.10)      # OUTRO lado da mão (costas, não a palma) + à frente
	holder.rotation_degrees = Vector3(0, 0, 0)        # painel VERTICAL virado pra frente (+Z local), igual ao plano do arco
	ba.add_child(holder)
	var wood := Color(0.40, 0.26, 0.14)
	var rim := Color(0.58, 0.60, 0.64)
	_box(holder, Vector3(0.34, 0.42, 0.04),  Vector3(0, 0, 0), wood, 0.1)        # corpo (mais alto que largo)
	_box(holder, Vector3(0.36, 0.045, 0.05), Vector3(0, 0.21, 0), rim, 0.6)      # borda topo
	_box(holder, Vector3(0.36, 0.045, 0.05), Vector3(0, -0.21, 0), rim, 0.6)     # borda base
	_box(holder, Vector3(0.045, 0.42, 0.05), Vector3(0.17, 0, 0), rim, 0.6)      # borda direita
	_box(holder, Vector3(0.045, 0.42, 0.05), Vector3(-0.17, 0, 0), rim, 0.6)     # borda esquerda
	_sphere(holder, 0.055, Vector3(0, 0, 0.04), rim, 0.6)                        # umbo central

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
	# direção do golpe fatal (cai pra "longe do centro" se a morte não veio de um golpe — vitória/decisão)
	var dir: Vector3 = f.get("last_hit_dir", Vector3(signf(node.position.x), 0, 0))
	if dir.length() < 0.01: dir = Vector3.RIGHT
	dir = dir.normalized()
	var brutal: bool = f.get("last_hit_crit", false)
	# [GORE] golpe fatal: jato de sangue (pra cima + na direção) + névoa + gotejamento + poça grande
	_blood_spray(_chest(f), Vector3.UP * 1.6 + dir * 0.8, 40 if brutal else 30, true)
	_blood_mist(_chest(f))
	_blood_drip(f)
	# [GORE] DESMEMBRAMENTO: membros/pedaços voam na direção do golpe (humano E monstro)
	_gore_burst(_chest(f), dir, 9 if brutal else 5)
	get_tree().create_timer(0.8).timeout.connect(func():
		if is_instance_valid(f["node"]): _blood_pool((f["node"] as Node3D).global_position, true))
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	if skel and _has_physical_bones(skel):
		if f["anim"]: f["anim"].stop()
		skel.physical_bones_start_simulation()
		var force := 3.4 if brutal else 2.6
		for c in skel.get_children():   # ragdoll EMPURRADO na direção do golpe
			if c is PhysicalBone3D and (c.bone_name in ["Spine", "Spine1", "Spine2", "Hips"]):
				(c as PhysicalBone3D).apply_central_impulse(Vector3(dir.x * force, 1.5, dir.z * force))
	elif f["anim"]:
		var death_clip := _clip(f, "death")
		var d: Animation = f["anim"].get_animation(death_clip)
		if d: d.loop_mode = Animation.LOOP_NONE
		f["anim"].play(death_clip, BLEND)

# [GORE] DESMEMBRAMENTO: dispara `count` pedaços (membros=cápsula, cabeça=esfera, naco=cubo) como
# RigidBody3D voando na direção `dir` + pra cima, com giro. Caem no chão e somem (~4s). Cap MAX_GIBS.
func _gore_burst(pos: Vector3, dir: Vector3, count: int) -> void:
	for i in count:
		var rb := RigidBody3D.new()
		var mi := MeshInstance3D.new()
		var col := CollisionShape3D.new()
		var kind := i % 6
		if kind <= 1:                       # MEMBRO (braço/perna)
			var cm := CapsuleMesh.new(); cm.radius = 0.07; cm.height = 0.36
			mi.mesh = cm
			var cs := CapsuleShape3D.new(); cs.radius = 0.07; cs.height = 0.36
			col.shape = cs
		elif kind == 2:                     # "cabeça"
			var sm := SphereMesh.new(); sm.radius = 0.13; sm.height = 0.26
			mi.mesh = sm
			var ss := SphereShape3D.new(); ss.radius = 0.13
			col.shape = ss
		else:                               # naco de carne
			var bm := BoxMesh.new(); bm.size = Vector3(0.13, 0.13, 0.13)
			mi.mesh = bm
			var bs := BoxShape3D.new(); bs.size = Vector3(0.13, 0.13, 0.13)
			col.shape = bs
		var mat := StandardMaterial3D.new()
		mat.albedo_color = GORE_COLORS[i % GORE_COLORS.size()]
		mat.roughness = 0.85
		mi.material_override = mat
		rb.add_child(mi)
		rb.add_child(col)
		add_child(rb)
		rb.global_position = pos + Vector3(randf_range(-0.2, 0.2), randf_range(-0.1, 0.35), randf_range(-0.2, 0.2))
		rb.linear_velocity = Vector3(dir.x * randf_range(1.5, 3.5) + randf_range(-1.0, 1.0),
				randf_range(2.5, 4.5),
				dir.z * randf_range(1.5, 3.5) + randf_range(-1.0, 1.0))
		rb.angular_velocity = Vector3(randf_range(-12, 12), randf_range(-12, 12), randf_range(-12, 12))
		_gibs.append(rb)
		get_tree().create_timer(randf_range(3.5, 5.0)).timeout.connect(func():
			if is_instance_valid(rb): rb.queue_free())
	while _gibs.size() > MAX_GIBS:   # cap: remove os mais antigos
		var old = _gibs.pop_front()
		if is_instance_valid(old): old.queue_free()

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

# ── [GORE] SANGUE (sem economia) — partículas + decais por código ───────────────
# Posição do golpe pela zona (head/body/legs).
func _hit_pos(f: Dictionary, zone: String) -> Vector3:
	var base := (f["node"] as Node3D).global_position
	match zone:
		"head": return base + Vector3(0, 1.6, 0)
		"legs": return base + Vector3(0, 0.5, 0)
		_:      return base + Vector3(0, 1.1, 0)

# Material dos pingos (quad billboard, cor vem do color_ramp das partículas).
func _drop_material() -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.vertex_color_use_as_albedo = true
	m.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	return m

# Textura radial macia (blob) p/ a poça/decal.
func _pool_texture() -> Texture2D:
	var g := Gradient.new()
	g.set_color(0, Color(1, 1, 1, 1))
	g.set_color(1, Color(1, 1, 1, 0))
	var t := GradientTexture2D.new()
	t.gradient = g
	t.width = 128; t.height = 128
	t.fill = GradientTexture2D.FILL_RADIAL
	t.fill_from = Vector2(0.5, 0.5)
	t.fill_to = Vector2(0.5, 0.0)
	return t

# JATO: spray de sangue saindo na direção do golpe (mais forte e largo no crit).
func _blood_spray(pos: Vector3, dir: Vector3, amount: int, big: bool, elem := "") -> void:
	if _spray_live >= MAX_SPRAYS: return
	_spray_live += 1
	var p := GPUParticles3D.new()
	p.one_shot = true
	p.explosiveness = 1.0
	p.amount = clampi(amount + (22 if big else 7), 10, 70)
	p.lifetime = 0.9
	var m := ParticleProcessMaterial.new()
	var d := Vector3.UP
	if dir.length() > 0.01: d = (dir.normalized() + Vector3.UP * 0.6).normalized()
	m.direction = d
	m.spread = 38.0 if big else 24.0
	m.initial_velocity_min = 2.2 if big else 1.3
	m.initial_velocity_max = 7.5 if big else 4.0
	m.gravity = Vector3(0, -9.0, 0)
	m.damping_min = 0.4
	m.damping_max = 1.6
	m.scale_min = 0.5
	m.scale_max = 1.35
	var hi := BLOOD_HIGH
	if elem == "SUPER": hi = Color(0.78, 0.12, 0.0)
	elif elem == "RESIST": hi = Color(0.45, 0.06, 0.18)
	var g := Gradient.new()
	g.set_color(0, hi)
	g.add_point(0.55, BLOOD_LOW)
	g.set_color(2, Color(BLOOD_LOW, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g
	m.color_ramp = gt
	p.process_material = m
	var quad := QuadMesh.new()
	quad.size = Vector2(0.085, 0.085)
	quad.material = _drop_material()
	p.draw_pass_1 = quad
	add_child(p)
	p.global_position = pos
	p.emitting = true
	get_tree().create_timer(1.1).timeout.connect(func():
		_spray_live -= 1
		if is_instance_valid(p): p.queue_free())

# NÉVOA: nuvem fina avermelhada suspensa um instante (crit/morte).
func _blood_mist(pos: Vector3) -> void:
	if _spray_live >= MAX_SPRAYS: return
	_spray_live += 1
	var p := GPUParticles3D.new()
	p.one_shot = true
	p.explosiveness = 1.0
	p.amount = 6
	p.lifetime = 1.1
	var m := ParticleProcessMaterial.new()
	m.direction = Vector3.UP
	m.spread = 85.0
	m.initial_velocity_min = 0.2
	m.initial_velocity_max = 0.7
	m.gravity = Vector3(0, -0.35, 0)
	m.scale_min = 3.0
	m.scale_max = 6.0
	var g := Gradient.new()
	g.set_color(0, Color(BLOOD_HIGH, 0.16))
	g.set_color(1, Color(BLOOD_LOW, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g
	m.color_ramp = gt
	p.process_material = m
	var quad := QuadMesh.new()
	quad.size = Vector2(0.3, 0.3)
	quad.material = _drop_material()
	p.draw_pass_1 = quad
	add_child(p)
	p.global_position = pos
	p.emitting = true
	get_tree().create_timer(1.4).timeout.connect(func():
		_spray_live -= 1
		if is_instance_valid(p): p.queue_free())

# POÇA: decal que drapeja sobre o chão e CRESCE (sangue se espalhando).
func _blood_pool(pos: Vector3, big := false) -> void:
	var dcl := Decal.new()
	dcl.texture_albedo = _pool_texture()
	dcl.modulate = POOL_TINT
	dcl.albedo_mix = 1.0
	dcl.size = Vector3(0.12, 0.8, 0.12)        # Y alto cobre o relevo dos ladrilhos
	add_child(dcl)
	dcl.global_position = Vector3(pos.x, 0.3, pos.z)
	dcl.rotation.y = randf() * TAU
	var s := randf_range(1.5, 2.2) if big else randf_range(0.7, 1.1)
	create_tween().tween_property(dcl, "size", Vector3(s, 0.8, s), 1.6).set_trans(Tween.TRANS_QUART).set_ease(Tween.EASE_OUT)
	_pools.append(dcl)
	if _pools.size() > MAX_POOLS:
		var old = _pools.pop_front()
		if is_instance_valid(old):
			var tw := create_tween()
			tw.tween_property(old, "modulate:a", 0.0, 2.0)
			tw.tween_callback(old.queue_free)

# GOTEJAMENTO: preso ao corpo, pinga ~2.2s (acompanha o ragdoll caindo).
func _blood_drip(f: Dictionary) -> void:
	var p := GPUParticles3D.new()
	p.amount = 22
	p.lifetime = 0.55
	var m := ParticleProcessMaterial.new()
	m.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_SPHERE
	m.emission_sphere_radius = 0.22
	m.direction = Vector3.DOWN
	m.spread = 15.0
	m.initial_velocity_min = 0.1
	m.initial_velocity_max = 0.5
	m.gravity = Vector3(0, -14.0, 0)
	m.scale_min = 0.5
	m.scale_max = 0.9
	var g := Gradient.new()
	g.set_color(0, BLOOD_HIGH)
	g.set_color(1, Color(BLOOD_LOW, 0.4))
	var gt := GradientTexture1D.new(); gt.gradient = g
	m.color_ramp = gt
	p.process_material = m
	var quad := QuadMesh.new()
	quad.size = Vector2(0.06, 0.06)
	quad.material = _drop_material()
	p.draw_pass_1 = quad
	(f["node"] as Node3D).add_child(p)
	p.position = Vector3(0, 1.0, 0)
	p.emitting = true
	get_tree().create_timer(2.2).timeout.connect(func():
		if is_instance_valid(p):
			p.emitting = false
			get_tree().create_timer(0.7).timeout.connect(p.queue_free))

func _quad(w: float, h: float, col: Color, prio: int) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(w, h)
	mi.mesh = q
	var mat := StandardMaterial3D.new()
	mat.albedo_color = col
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	mat.billboard_keep_scale = true   # SEM isso o billboard ignora o scale → a barra nunca encolhe (não zera)
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

# "scene" do backend → mapa do BattleReplay (casa o cenário com o reino da luta).
const SCENE_TO_MAP := {
	"arena": "arena", "tower": "dungeon", "coast": "beach",
	"sea": "beach", "cave": "mining", "fortress": "castle",
}

# Câmera só (cedo, antes do fetch). O MAPA é montado depois (_setup_map), já sabendo a scene.
func _setup_camera() -> void:
	cam = Camera3D.new()
	cam.position = Vector3(0.0, 3.0, 5.5)   # default de 1v1; _frame_camera() reenquadra após o spawn
	cam.look_at_from_position(cam.position, Vector3(0, 1.0, 0), Vector3.UP)
	add_child(cam)

# Monta o MAPA. Prioridade: scenario fixo (@export) > scene do backend (reino da luta) > sorteio.
func _setup_map() -> void:
	var srng := RandomNumberGenerator.new()
	srng.seed = 20260611
	var scn := scenario
	if scn == "":
		if fight_scene != "" and SCENE_TO_MAP.has(fight_scene):
			scn = SCENE_TO_MAP[fight_scene]   # casa o mapa com o reino (coast→beach, cave→mining, …)
			print("=== mapa pelo reino: scene=%s → %s ===" % [fight_scene, scn])
		else:
			var pick := RandomNumberGenerator.new()
			pick.randomize()
			scn = SCENARIOS[pick.randi() % SCENARIOS.size()]
			print("=== mapa aleatório: %s ===" % scn)
	chosen_scenario = scn
	if scn == "coliseum":   # coliseu procedural antigo (opcional)
		_setup_environment()
		_setup_lights()
		_setup_arena()
	else:
		var sc := Scenery.new()
		sc.build(self, scn, srng, FIELD_EDGE + 1.5, grimdark)   # centro livre p/ os lutadores

# Céu procedural quente + névoa + tonemap/glow → mood de fim de tarde no coliseu.
func _setup_environment() -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color(0.30, 0.40, 0.58)
	sky_mat.sky_horizon_color = Color(0.78, 0.62, 0.48)
	sky_mat.ground_horizon_color = Color(0.55, 0.45, 0.38)
	sky_mat.ground_bottom_color = Color(0.28, 0.23, 0.20)
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_SKY
	env.ambient_light_energy = 0.5
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	env.fog_enabled = true
	env.fog_light_color = Color(0.72, 0.6, 0.5)
	env.fog_density = 0.010
	env.glow_enabled = true
	env.glow_intensity = 0.3
	env.glow_bloom = 0.05
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)

func _setup_lights() -> void:
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-48, -35, 0)
	sun.light_color = Color(1.0, 0.94, 0.82)   # sol quente
	sun.light_energy = 1.4
	sun.shadow_enabled = true
	add_child(sun)
	var fill := DirectionalLight3D.new()
	fill.rotation_degrees = Vector3(-28, 150, 0)
	fill.light_color = Color(0.55, 0.65, 0.85)  # fill frio do lado oposto
	fill.light_energy = 0.4
	add_child(fill)

func _setup_arena() -> void:
	# chão de AREIA (disco) + colisão p/ o ragdoll
	var floor_mi := MeshInstance3D.new()
	var fc := CylinderMesh.new()
	fc.top_radius = 24.0; fc.bottom_radius = 24.0; fc.height = 0.5
	floor_mi.mesh = fc
	var fmat := StandardMaterial3D.new()
	fmat.albedo_color = Color(0.52, 0.43, 0.30); fmat.roughness = 1.0
	floor_mi.material_override = fmat
	floor_mi.position = Vector3(0, -0.25, 0)
	add_child(floor_mi)
	# círculo de duelo (marca mais escura no centro)
	var ring := MeshInstance3D.new()
	var rc := CylinderMesh.new()
	rc.top_radius = 6.5; rc.bottom_radius = 6.5; rc.height = 0.04
	ring.mesh = rc
	var rmat := StandardMaterial3D.new()
	rmat.albedo_color = Color(0.42, 0.34, 0.22); rmat.roughness = 1.0
	ring.material_override = rmat
	ring.position = Vector3(0, 0.02, 0)
	add_child(ring)
	var floor_body := StaticBody3D.new()
	var floor_col := CollisionShape3D.new()
	floor_col.shape = WorldBoundaryShape3D.new()
	floor_body.add_child(floor_col)
	add_child(floor_body)
	# ARQUIBANCADA em tiers que SOBEM (preenche o fundo / esconde o céu) — a frente fica atrás da câmera
	for t in 7:
		var r := 11.5 + t * 1.7
		var yb := t * 1.5
		var shade := 0.33 - t * 0.012
		_arena_ring(r, 2.4, yb, Color(shade + 0.03, shade + 0.01, shade - 0.02), 36 + t * 3)
	# muralha alta no topo, fechando de vez o céu atrás
	_arena_ring(23.5, 9.0, 12.0, Color(0.22, 0.21, 0.20), 64)
	# tochas com luz quente num anel uniforme (o glow faz a chama brilhar)
	for i in 7:
		var a := TAU * i / 7.0
		_torch(Vector3(10.6 * cos(a), 0.0, 10.6 * sin(a)))

# Um segmento-de-anel de pedra apontando pro centro (parede do coliseu).
func _arena_ring(r: float, h: float, y_base: float, color: Color, segs: int) -> void:
	var seg_w := (TAU * r / segs) * 1.18   # leve sobreposição → parede contínua
	for i in segs:
		var a := TAU * i / segs
		var mi := MeshInstance3D.new()
		var bm := BoxMesh.new()
		bm.size = Vector3(seg_w, h, 0.9)
		mi.mesh = bm
		var mat := StandardMaterial3D.new()
		mat.albedo_color = color.lerp(Color(0.5, 0.47, 0.43), float(i % 2) * 0.25)  # blocos alternados
		mat.roughness = 0.95
		mi.material_override = mat
		add_child(mi)
		mi.position = Vector3(r * cos(a), y_base + h * 0.5, r * sin(a))
		mi.look_at(Vector3(0, mi.position.y, 0), Vector3.UP)

func _torch(pos: Vector3) -> void:
	var post := MeshInstance3D.new()
	var pb := BoxMesh.new(); pb.size = Vector3(0.16, 1.8, 0.16)
	post.mesh = pb
	var pmat := StandardMaterial3D.new(); pmat.albedo_color = Color(0.18, 0.12, 0.07); pmat.roughness = 1.0
	post.material_override = pmat
	add_child(post); post.position = pos + Vector3(0, 0.9, 0)
	var flame := MeshInstance3D.new()
	var sm := SphereMesh.new(); sm.radius = 0.2; sm.height = 0.46
	flame.mesh = sm
	var fmat := StandardMaterial3D.new()
	fmat.albedo_color = Color(1.0, 0.6, 0.2)
	fmat.emission_enabled = true
	fmat.emission = Color(1.0, 0.55, 0.12)
	fmat.emission_energy_multiplier = 5.0
	flame.material_override = fmat
	add_child(flame); flame.position = pos + Vector3(0, 1.9, 0)
	var light := OmniLight3D.new()
	light.light_color = Color(1.0, 0.6, 0.25)
	light.light_energy = 3.5
	light.omni_range = 9.0
	add_child(light); light.position = pos + Vector3(0, 2.0, 0)

# Enquadra a câmera pela quantidade de lutadores: 1v1 fica perto; formações grandes
# afastam e sobem. (Hoje só há 1v1; o termo por-lutador já deixa pronto p/ 3×5 etc.)
func _frame_camera() -> void:
	if cam == null: return
	# cam_preset>0 força o preset; 0 = AUTO pela qtde de lutadores (1v1→1, ≤3×3→2, senão→3)
	if cam_preset >= 1 and cam_preset <= 3:
		cam_view = cam_preset
	else:
		var n := order.size()
		cam_view = 1 if n <= 2 else (2 if n <= 8 else 3)
	_apply_camera()

# Posiciona a câmera segundo o preset ATIVO (cam_view). Chamado no spawn e ao trocar com 1/2/3.
func _apply_camera() -> void:
	if cam == null: return
	var p: Dictionary = CAM_PRESETS[clampi(cam_view, 1, 3) - 1]
	cam.position = Vector3(0.0, p["height"], p["dist"])
	cam.look_at(Vector3(0.0, p["look_y"], 0.0), Vector3.UP)
	if cam_hint:
		cam_hint.text = "📷 Cam %d  [1/2/3]" % cam_view

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
	countdown_label.add_theme_font_size_override("font_size", 64)
	countdown_label.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	countdown_label.offset_top = 60     # mais acima (acima dos personagens)
	countdown_label.offset_bottom = 150
	countdown_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	countdown_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	layer.add_child(countdown_label)
	cam_hint = Label.new()                    # dica de câmera no canto superior esquerdo
	cam_hint.add_theme_font_size_override("font_size", 16)
	cam_hint.position = Vector2(14, 10)
	cam_hint.text = "📷 Cam 1  [1/2/3]"
	layer.add_child(cam_hint)

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

# Luta MOCK local: herói (equip real) vs um MONSTRO (`foe` vira o spawn da direita → Monsters.pick_for
# o transforma no bicho). O monstro dá alguns golpes e o herói vence (mostra ataque + morte + sangue).
func _mock_monster_events(foe: String) -> Array:
	var EHP := 130   # monstro um pouco mais tankão (HP maior) p/ a luta durar
	return [
		{"type": "spawn",  "actor": "Você", "target": "", "damage": 0, "targetHp": 100, "targetMaxHp": 100, "element": "", "hitZone": ""},
		{"type": "spawn",  "actor": foe,    "target": "", "damage": 0, "targetHp": EHP, "targetMaxHp": EHP, "element": "", "hitZone": ""},
		{"type": "attack", "actor": foe,    "target": "Você", "damage": 14, "targetHp": 86, "targetMaxHp": 100, "element": "", "hitZone": "body"},
		{"type": "attack", "actor": "Você", "target": foe,    "damage": 18, "targetHp": EHP - 18, "targetMaxHp": EHP, "element": "", "hitZone": "body"},
		{"type": "crit",   "actor": foe,    "target": "Você", "damage": 22, "targetHp": 64, "targetMaxHp": 100, "element": "SUPER", "hitZone": "head"},
		{"type": "attack", "actor": "Você", "target": foe,    "damage": 20, "targetHp": EHP - 38, "targetMaxHp": EHP, "element": "", "hitZone": "legs"},
		{"type": "attack", "actor": foe,    "target": "Você", "damage": 12, "targetHp": 52, "targetMaxHp": 100, "element": "", "hitZone": "body"},
		{"type": "crit",   "actor": "Você", "target": foe,    "damage": 34, "targetHp": EHP - 72, "targetMaxHp": EHP, "element": "SUPER", "hitZone": "head"},
		{"type": "attack", "actor": "Você", "target": foe,    "damage": 24, "targetHp": EHP - 96, "targetMaxHp": EHP, "element": "", "hitZone": "body"},
		{"type": "attack", "actor": foe,    "target": "Você", "damage": 10, "targetHp": 42, "targetMaxHp": 100, "element": "", "hitZone": "legs"},
		{"type": "attack", "actor": "Você", "target": foe,    "damage": EHP - 96, "targetHp": 0, "targetMaxHp": EHP, "element": "", "hitZone": "body"},
		{"type": "victory","actor": "Você", "target": foe,    "damage": 0, "targetHp": 0, "targetMaxHp": EHP, "element": "", "hitZone": ""},
	]
