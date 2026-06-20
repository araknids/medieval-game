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
const CHAR_FEMALE := preload("res://addons/quaternius_ik_rigged/Models_with_rigging/Female_Rigged.tscn")  # [OUTFITS_FEMALE]
const OutfitsLib := preload("res://Outfits.gd")   # sets novos por tema/gênero/variante + recolor por raridade
const Scenery := preload("res://Scenery.gd")
const Monsters := preload("res://Monsters.gd")
const Weapons := preload("res://Weapons.gd")
const GameSettings := preload("res://GameSettings.gd")   # [CONFIG] toggle de gore (sangue/desmembramento)
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
const WINDUP := 0.27         # [FLUIDEZ A] s — armar o golpe; alinha o impacto (sangue/flinch) ~ pico do swing
const RECOVER := 0.20        # [FLUIDEZ C] s — respiro pós-impacto; deixa o swing "respirar" antes do próximo
const MAX_WAIT := 2.2        # s — timeout p/ disparar mesmo fora de posição (nunca trava)
const COUNTDOWN := 3.0       # s — contagem 3,2,1 antes da luta (warm-up)
const CD_STEP := 0.5         # [COUNTDOWN_ART] s por número (3,2,1)
const CD_WORD := 0.9         # [COUNTDOWN_ART] s que o LUTAR/FIGHT fica na tela
const INSTANT_TYPES := ["spawn", "victory", "heal", "berserk", "backpedal", "pinned", "pointblank"]

# game-feel (polish de fluidez — sugestões do Fable)
const BLEND := 0.12          # cross-fade entre animações (mata o "pop" de troca)
const TURN_SPEED := 13.0     # virada suave (rad/s aprox; frame-rate independente)
const ACCEL := 12.0          # aceleração do movimento → chega à vel. máx em ~0.23s (sem partir/parar seco)
const HP_LERP := 9.0         # drenagem suave da barra de vida
const CORNER_HP := true       # [HP_CANTO] vida no CANTO da tela (estilo jogo de luta) em vez de acima da cabeça

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
	# [CAM_LENTE] lente LONGA no 1v1 (FOV ~50 + mais longe) = menos distorção, lutadores mais "pesados".
	{"dist": 6.9,  "height": 2.4,  "look_y": 1.25, "fov": 50.0},  # 1 — 1v1
	{"dist": 10.0, "height": 4.4,  "look_y": 1.5,  "fov": 55.0},  # 2 — até 3×3
	{"dist": 14.0, "height": 7.2,  "look_y": 1.9,  "fov": 60.0},  # 3 — 5×5
]

# tipos de evento (idênticos ao battleArena.js 2D)
# [HABILIDADES] id da skill (vem no evento `ability`) → [ícone em assets/ui/icons/, nome exibido, cor].
# O replay mostra ícone+nome flutuando acima do lutador quando uma ATIVA dispara. [SKILL_POPUP]
const SKILL_INFO := {
	"second_wind":   ["skill_second_wind", "Second Wind", Color(0.55, 0.95, 0.6)],
	"berserk":       ["skill_berserk", "Berserk", Color(1.0, 0.45, 0.25)],
	"shield_bash":   ["skill_shield_bash", "Shield Bash", Color(1.0, 0.82, 0.4)],
	"crushing_blow": ["skill_crushing_blow", "Crushing Blow", Color(1.0, 0.7, 0.35)],
	"precise_shot":  ["skill_precise_shot", "Precise Shot", Color(0.6, 0.85, 1.0)],
	"volley":        ["skill_volley", "Volley", Color(0.7, 0.8, 1.0)],
	"evasive_roll":  ["skill_evasive_roll", "Evasive Roll", Color(0.7, 0.9, 1.0)],
}
const HIT_TYPES := ["attack", "crit", "volley", "extra"]       # carregam dano/HP
const SWING_TYPES := ["attack", "crit", "volley", "extra", "miss", "dodge"]  # atacante balança a arma
const RANGED_MARKERS := ["volley", "pinned", "pointblank", "backpedal"]  # delatam um lutador ranged
const SCENARIOS := ["mining", "beach", "garimpa", "dungeon", "arena", "city", "castle"]  # mapas p/ sorteio
# [INIMIGO] cara própria do inimigo HUMANO, derivada do NOME (determinístico): cor de roupa + arma + porte.
const ENEMY_TINTS := [Color(0.55, 0.18, 0.18), Color(0.20, 0.28, 0.55), Color(0.22, 0.42, 0.24),
	Color(0.36, 0.30, 0.20), Color(0.25, 0.25, 0.28), Color(0.48, 0.40, 0.16), Color(0.40, 0.22, 0.42)]
const ENEMY_MELEE := ["sword", "greatsword", "axe", "spear", "mace"]
const ENEMY_BOWS := ["shortbow", "longbow", "crossbow"]
# Nomes humanos p/ o force_mock SORTEAR (cada nome → cara própria diferente). Sem isso, "Bandido" fixo = sempre igual.
const MOCK_FOES := ["Renegade Knight", "Corrupt Mercenary", "Road Brigand", "Fallen Captain", "Orc Reaver",
	"Iron Marauder", "Grim Outlaw", "Bandit Lord", "Cutthroat", "Deserter of the King", "Pale Cutpurse", "War Ogre"]
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
# [RARIDADE] cores/brilho por raridade ficam em Weapons.gd (Weapons.RARITY_TINT/RARITY_GLOW)

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
@export var force_mock := true
## TESTE: no mock, faz o Bandido (espada) VENCER — p/ ver como fica quando o melee ganha o arqueiro.
@export var mock_enemy_wins := false
## [TEAM_MOCK] TESTE 3v3: o botão Lutar (mock) mostra uma batalha de TIME — 3 aliados vs 3 inimigos.
@export var force_mock_3v3 := true
## Troca o INIMIGO (direita) por um monstro ESPECÍFICO do bundle (override manual de teste).
## Vazio = decide pelo NOME do inimigo (Monsters.pick_for). Ex.: "Demon", "Dragon", "Ghost Skull".
@export var enemy_monster := ""
## TESTE: finge que o inimigo do backend tem ESTE nome (p/ ver o mapa nome→monstro sem PvE real).
## Ex.: "Young Dragon" → Dragon · "Stone Golem" → Goleling Evolved · "Orc Warrior" → humano.
@export var force_enemy_name := ""
## TESTE: força o tipo VISUAL da arma do herói (sem equipar no jogo). Vazio = arma real do inventário.
## Valores: sword | greatsword | axe | spear | mace | shortbow | longbow | crossbow
@export var force_weapon := ""
## Posição do CABO da arma melee ao longo da mão (RightHand). MENOR = cabo mais pra dentro/baixo (na mão).
@export var weapon_grip := 0.10
## Orientação da ARMA do herói na mão (graus). Inspector: ajuste se o machado/arma ficar pro lado errado.
## 999,999,999 (default) = usa a tabela HAND_XF. Ache o valor certo aqui e me passe que eu fixo na tabela. [ARMAS_3D]
@export var weapon_rot_override := Vector3(999, 999, 999)
## TESTE: força a RARIDADE visual da arma do herói (1 comum…5 lendário). 0 = raridade real do inventário.
@export_range(0, 5) var force_rarity := 0
## TESTE: força um ESCUDO na off-hand do herói (some com arco). Vazio/false = só se equipado de verdade.
@export var force_shield := false
## Escudo (off-hand): posição calibrável (orientação é automática, virada pra frente). [Fable] Roll-safe.
@export var shield_slide := 0.13   # desliza ao longo do antebraço (cotovelo→pulso). + = rumo ao pulso
@export var shield_push := 0.18    # empurra pra FORA (frente) — afasta do braço/torso (cobre a mão)
@export var shield_side := 0.0     # nudge lateral (no frame alinhado do escudo)
@export var shield_up := 0.02      # nudge vertical (no frame alinhado do escudo)
## Vira a face do escudo (use se o umbo ficar virado pro CORPO em vez do inimigo).
@export var shield_flip := false
## Orientação do escudo na mão (graus). Ajuste no Inspector se ficar de lado/cabeça-pra-baixo. [ARMAS_3D]
@export var shield_rot := Vector3(0, 0, 180)
## (Legado) escala manual do monstro — hoje o tamanho vem do roster (Monsters.size_for) + auto-fit.
@export var monster_scale := 1.0
## Giro extra do monstro em Y (graus) se ele nascer de lado/de costas. Tente 0, 90, 180, -90.
@export var monster_face_offset_deg := 0.0
## Câmera: 0 = AUTO (escolhe pela qtde de lutadores) · 1 = 1v1 · 2 = até 3×3 · 3 = 5×5. Teclas 1/2/3 trocam ao vivo.
@export var cam_preset := 0
## Pós-processo grimdark (vinheta + grade + bloom/SSAO) nos mapas do Scenery. [GODOT_GRIMDARK]
@export var grimdark := true

signal finished             # [MIGRACAO_GODOT] embutido no app (overlay): o App fecha o replay no fim
var external_battle := {}    # {events, scene, won} vindo da TELA (pula o fetch); vazio = busca sozinho (F6)
var events: Array = []
var fighters := {}          # name -> dict do lutador
var order: Array = []       # [left, right] na ordem de spawn
var player_equip: Array = []  # tipos de armadura EQUIPADOS pelo jogador (p/ vestir o lutador da esquerda)
var player_weapon := ""       # tipo visual fino: sword|greatsword|axe|spear|mace|shortbow|longbow|crossbow
var player_weapon_rarity := 1 # raridade (1-5) da arma equipada → cor/brilho do metal [RARIDADE]
var player_shield_rarity := 1 # raridade (1-5) do escudo equipado → cor/brilho da borda/umbo [RARIDADE]
var player_items: Array = []  # [OUTFITS] itens EQUIPADOS reais (dicts) p/ vestir o herói com o tema/variante/raridade de cada peça
var player_gender := "male"   # gênero do jogador (UiKit.current_gender) → base + peças do herói [OUTFITS_FEMALE]
var cam: Camera3D
var mons := Monsters.new()  # helper de monstros (instancia + auto-fit + roster/mapa)
var wp := Weapons.new()     # helper de armas/escudo procedurais (+ raridade)
var cam_view := 1            # preset de câmera ATIVO (1/2/3) — ver CAM_PRESETS
var cam_hint: Label          # dica no canto: "📷 Cam N  [1/2/3]"
# [JUICE] game-feel: camera shake (trauma²) + hit-stop + kill-cam slow-mo [Fable]
var cam_shake := 0.0          # trauma 0..1 (decai); aplica offset de tela
var _shake_noise := FastNoiseLite.new()
var _shake_time := 0.0
var _hs_gen := 0              # geração do hit-stop/slow-mo (só o último restaura o time_scale)
var _cam_base_fov := 75.0
const SHAKE_DECAY := 3.2
# [CAM_TRACK] tracking 1v1: alvos SUAVIZADOS (lag pesado = sensação de operador). _cam_locked = kill-cam
# assume a câmera (o tracking pausa pra não brigar). Reset no _apply_camera.
var _cam_look := Vector3(0, 1.2, 0)
var _cam_dist := 6.9
var _cam_lean_x := 0.0
var _cam_locked := false
var _env: Environment         # [JUICE] reactivity: glow pulsa no kill; luz-chave pisca no crit
var _key_light: Light3D
var _env_base_glow := 0.0

# kiting: ativo quando EXATAMENTE um lado é ranged (arco) e o outro melee
var kiting := false
var team_mode := false        # [TEAM_MOCK] batalha 3v3 (movimento por-evento, sem clash/kite 1x1)
# [GUERRA_GAUNTLET] 15v15 em ondas de 3v3 (Modelo B): vencedor mantém sobreviventes, perdedor repõe 3.
const TEAM_ROWS := [-2.2, 0.0, 2.2]   # 3 lanes (eixo Z) do campo 3v3
const TEAM_X := 1.7                    # x de cada lado (ally −1.7 / foe +1.7)
var _resv_ally: Array = []            # reservas (specs ainda não spawnadas) do time aliado
var _resv_foe: Array = []             # reservas do time inimigo
var _team_wave := 1                   # onda atual
var war_mode := false                 # [GUERRA_GAUNTLET] replay da guerra REAL (eventos do backend, não sim local)
var war_events: Array = []
var war_i := 0
var war_t := 0.0
const WAR_STEP := 0.16                # seg entre golpes no replay da guerra
var ranged_f := {}            # lutador que recua/atira
var melee_f := {}             # lutador que avança
var victory_label: Label
var status_label: Label
# [HP_CANTO] HUD de vida no canto (estilo jogo de luta): 2 colunas (aliados esq / inimigos dir).
var hud_left: VBoxContainer
var hud_right: VBoxContainer
var hud_sig := ""             # assinatura dos lutadores no HUD → reconstrói só quando muda (spawn/morte)
var chosen_scenario := ""   # mapa sorteado/usado nesta luta
var fight_scene := ""        # "scene" que o backend mandou (coast/sea/cave/fortress/tower/arena) → casa o mapa
var _spray_live := 0        # [GORE] emissores de sangue vivos (cap)
var _pools: Array = []      # [GORE] poças/decais ativos (cap)
var _gibs: Array = []       # [GORE] pedaços/membros (RigidBody) ativos (cap)
var _trails: Array = []     # [JUICE] rastros de arma ativos (ribbon)
var _vignette: ColorRect = null   # [JUICE] vinheta vermelha de dano (lazy)

# director dirigido por simulação: movimento contínuo + cursor de evento com gatilho por posição
var phase := "loading"      # loading → fight → done
var _left := false          # guard p/ não sair duas vezes (botão Continuar + timer 5s)
var idx := 0                # cursor do evento atual
var act_state := "approach" # approach (espera posição) → windup (arma) → recover (respira)
var act_timer := 0.0
var wait_timer := 0.0
var cur_windup := WINDUP     # windup/recover do golpe atual (variados p/ matar o "metrônomo")
var cur_recover := RECOVER
var countdown_t := 0.0       # cronômetro da contagem 3,2,1
var countdown_tex: TextureRect   # [COUNTDOWN_ART] 3/2/1 + LUTAR/FIGHT em pixel art
var _cd_beat := -1               # beat atual da contagem (0=3,1=2,2=1,3=palavra)
var _cd_tex: Array = []          # texturas pré-carregadas (palavra pelo idioma)

func _ready() -> void:
	_setup_camera()
	_make_ui()
	await _load_events()        # define fight_scene (scene do backend)
	if events.is_empty():
		return
	_setup_map()                # monta o mapa JÁ sabendo o reino da luta (scene → mapa)
	if war_mode:                # [GUERRA_GAUNTLET] replay da guerra real (orientado a eventos)
		_start_war()
		return
	if team_mode:
		_build_team()           # [TEAM_MOCK] 3 contra 3
	else:
		var nspawn := events.filter(func(e): return str(e.get("type", "")) == "spawn").size()
		if nspawn > 2:          # [TORRE_GRUPO] 1 jogador + N inimigos ao MESMO TEMPO
			_build_group()      # já posiciona, enquadra a câmera e inicia o stepping (fase "war")
			print("=== BATTLE REPLAY (grupo) === %d eventos · %d spawns" % [events.size(), nspawn])
			return
		_build_fighters()
	_frame_camera()
	phase = "countdown"   # 3,2,1 antes de soltar a luta
	print("=== BATTLE REPLAY (sim-driven) === %d eventos · kiting=%s" % [events.size(), kiting])

# ── carga dos eventos: backend real, com fallback mock ──────────────────────────
# Sempre tenta logar p/ ler o EQUIP+ARMA reais do herói (o herói é sempre dinâmico).
# force_mock só troca os EVENTOS por um duelo fixo e força o Bandido a ser espadachim.
func _load_events() -> void:
	# [MIGRACAO_GODOT] reusa o Api (autoload): se já logado (veio do app), NÃO re-loga.
	# Acessa por /root/Api (robusto a parse de autoload); sem autoload → instância própria.
	# Standalone (F6) → loga via login.cfg. Plano: docs/PLANO_MIGRACAO_GODOT.md
	var client = get_node_or_null("/root/Api")
	if client == null:
		client = BackendClient.new()
		add_child(client)
	if base_url_override != "":
		client.base_url = base_url_override
	if client.token == "":
		var cf := ConfigFile.new()
		if cf.load("res://login.cfg") == OK:
			username = str(cf.get_value("login", "user", username))
			password = str(cf.get_value("login", "pass", password))
		_status(Lang.t("Conectando %s…") % client.base_url)
		var lr = await client.login(username, password)
		if not lr.get("ok"):
			_status(Lang.t("Login falhou (%s) — usando luta MOCK.") % lr.get("status"))
			print(">>> LOGIN FALHOU: %s | %s — caindo no mock." % [lr.get("status"), lr.get("error", "")])
			events = _mock_events()
			return

	# inventário → armadura equipada (paper-doll) + ARMA equipada (visual dinâmico do herói)
	var inv = await client.get_inventory()
	if inv.get("ok") and inv.get("json") is Array:
		_read_player_gear(inv["json"])
		print(">>> herói: equip=%s arma=%s" % [str(player_equip), player_weapon])

	# Overlay (veio do app): usa os eventos JÁ resolvidos pela tela — NÃO refaz a luta.
	if external_battle.get("events") is Array and (external_battle["events"] as Array).size() >= 2:
		events = external_battle["events"]
		fight_scene = str(external_battle.get("scene", ""))
		war_mode = bool(external_battle.get("war", false))   # [GUERRA_GAUNTLET] replay da guerra real
		var foe := str(external_battle.get("enemy", ""))
		_status(Lang.t("Duelo…") if foe == "" else (Lang.t("⚔ vs %s") % foe))   # SEM spoiler — o vencedor só no fim
		return

	if force_mock and force_mock_3v3:   # [TEAM_MOCK] batalha de time 3 contra 3
		team_mode = true
		events = _mock_team_events()
		_status(Lang.t("Modo TESTE — batalha 3 contra 3"))
		print("=== force_mock_3v3: batalha de time 3v3 ===")
		return

	if force_mock:
		var foe: String = MOCK_FOES[randi() % MOCK_FOES.size()]   # sorteia → cara própria varia a cada run
		events = _mock_events()
		for e in events:                                          # renomeia "Bandido" → o sorteado
			if e.get("actor") == "Bandido": e["actor"] = foe
			if e.get("target") == "Bandido": e["target"] = foe
		_status(Lang.t("Modo TESTE — sua arma real vs %s") % foe)
		print("=== force_mock: herói real vs %s (cara própria) ===" % foe)
		return

	if fight_source == "monster":   # luta MOCK local contra um MONSTRO (herói real vs bicho)
		var foe: String = enemy_monster if enemy_monster != "" else SHOWCASE_FOES[randi() % SHOWCASE_FOES.size()]
		events = _mock_monster_events(foe)
		_status(Lang.t("Monstro: %s (mock local)") % foe)
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
		_status(Lang.t("Arena indisponível — luta MOCK."))
	else:
		var foe: String = SHOWCASE_FOES[randi() % SHOWCASE_FOES.size()]
		events = _mock_monster_events(foe)
		_status(Lang.t("PvE indisponível — mock de monstro (%s).") % foe)

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
	var who := Lang.t("venceu") if j.get(win_key, false) else Lang.t("perdeu")
	_status(Lang.t("%s vs %s — você %s!") % [username, foe, who])
	print(">>> luta OK (%s): vs %s, %d eventos, scene=%s" % [fight_source, foe, be.size(), str(j.get("scene", ""))])
	return true

func _load_arena(client) -> bool:
	_status(Lang.t("Lutando na arena…"))
	var fr = await client.arena_fight()
	if fr.get("ok") and fr.get("json") is Dictionary:
		return _apply_fight_json(fr["json"], ["opponent"], "won")
	print(">>> ARENA FALHOU: %s | raw: %s" % [fr.get("status"), fr.get("raw", "")])
	return false

func _load_tower(client) -> bool:
	_status(Lang.t("Subindo a Torre…"))
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
		_status(Lang.t("Quest %s (%s)…") % [str(pick.get("displayName", qtype)), kingdom])
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
		_status(Lang.t("Eventos sem 2 spawns — nada a encenar."))
		return
	var lname := str(spawns[0].get("actor", "Hero"))
	var rname := str(spawns[1].get("actor", "Foe"))
	# HERÓI (esquerda = challenger): arma e equip REAIS. [UNARMED] Sem arma equipada → DESARMADO
	# (sem modelo de arma + golpe de soco/Punch_Cross), não mais uma espada padrão.
	# force_weapon (Inspector) sobrepõe p/ TESTE — ver qualquer tipo sem equipar no jogo.
	var lweapon := force_weapon if force_weapon != "" else player_weapon
	var lequip: Array = (player_equip if player_equip.size() > 0 else DEFAULT_OUTFIT).duplicate()
	if force_shield and not ("SHIELD" in lequip):
		lequip.append("SHIELD")   # TESTE: força o escudo
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
	# INIMIGO HUMANO (real OU mock): cara própria pelo NOME (cor de roupa + arma + porte).
	var rweapon := "sword"
	var rlook: Dictionary = {}
	if emeta.is_empty():
		rlook = _enemy_look(rname, _is_ranged(rname))
		rweapon = str(rlook["weapon"])
	var lrarity := force_rarity if force_rarity > 0 else player_weapon_rarity   # [RARIDADE] herói
	var requip: Array = rlook.get("equip", DEFAULT_OUTFIT)   # peças do inimigo variam (rlook); monstro ignora
	player_gender = UiKit.current_gender if UiKit.current_gender != "" else "male"   # [OUTFITS] gênero real do jogador
	# herói (esquerda) = ITENS REAIS equipados (player_items); inimigo segue o set por nome
	order = [
		_make_fighter(lname, -1, int(spawns[0].get("targetMaxHp", 100)), lweapon, lequip, {}, lrarity, {}, player_items),
		_make_fighter(rname,  1, int(spawns[1].get("targetMaxHp", 100)), rweapon, requip, emeta, 1, rlook),
	]
	# [HP_SPAWN] HP inicial = ATUAL (targetHp), não o máximo → a barra reflete entrar machucado.
	for i in 2:
		var sp: Dictionary = spawns[i]
		var cur := clampi(int(sp.get("targetHp", order[i]["maxhp"])), 1, int(order[i]["maxhp"]))
		order[i]["hp"] = cur
		order[i]["shown_hp"] = float(cur)
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

# [TORRE_GRUPO] 1 herói (esquerda, gear REAL) vs N inimigos (direita, em lanes) — TODOS ao mesmo tempo.
# Reusa o desenho de time (billboard de HP, _war_hit, _finish) + o stepping por-evento da guerra (fase "war").
# Detecção: >2 spawns nos eventos (1 jogador + N inimigos). Eventos vêm do simulateGroup do backend.
func _build_group() -> void:
	team_mode = true        # reusa enquadramento/HP de time
	kiting = false
	order = []
	var spawns: Array = events.filter(func(e): return str(e.get("type", "")) == "spawn")
	# HERÓI = 1º spawn (esquerda) com itens/arma REAIS, igual ao _build_fighters.
	var lname := str(spawns[0].get("actor", "Hero"))
	var lweapon := force_weapon if force_weapon != "" else player_weapon
	var lequip: Array = (player_equip if player_equip.size() > 0 else DEFAULT_OUTFIT).duplicate()
	if force_shield and not ("SHIELD" in lequip):
		lequip.append("SHIELD")
	player_gender = UiKit.current_gender if UiKit.current_gender != "" else "male"
	var lrar := force_rarity if force_rarity > 0 else player_weapon_rarity
	var hero := _make_fighter(lname, -1, int(spawns[0].get("targetMaxHp", 100)), lweapon, lequip, {}, lrar, {}, player_items)
	var hn := hero["node"] as Node3D
	hn.position = Vector3(-TEAM_X, hero["base_y"], 0.0)
	hero["home"] = hn.position
	hero["anchor"] = hn.position
	hero["team"] = -1
	hero["lane"] = 1
	hero["hp"] = clampi(int(spawns[0].get("targetHp", hero["maxhp"])), 1, int(hero["maxhp"]))
	hero["shown_hp"] = float(hero["hp"])
	order.append(hero)
	fighters[lname] = hero
	_update_hp(hero)
	# INIMIGOS = spawns restantes (direita, em lanes centralizadas pela contagem). Cara própria pelo NOME.
	var foes := spawns.slice(1)
	for i in foes.size():
		var sp: Dictionary = foes[i]
		var nm := str(sp.get("actor", "Foe %d" % (i + 1)))
		if nm == "" or fighters.has(nm):
			continue
		var emeta: Dictionary = {}
		var look: Dictionary = {}
		var ew := "sword"
		var pick := mons.pick_for(nm)
		if pick.get("kind") == "monster":
			emeta = pick
		else:
			look = _enemy_look(nm, false)
			ew = str(look["weapon"])
		var maxhp := maxi(1, int(sp.get("targetMaxHp", 100)))
		var f := _make_fighter(nm, 1, maxhp, ew, look.get("equip", DEFAULT_OUTFIT), emeta, 1, look)
		var fn := f["node"] as Node3D
		fn.position = Vector3(TEAM_X, f["base_y"], _group_lane_z(i, foes.size()))
		f["home"] = fn.position
		f["anchor"] = fn.position
		f["team"] = 1
		f["lane"] = i
		f["hp"] = clampi(int(sp.get("targetHp", maxhp)), 0, maxhp)
		f["shown_hp"] = float(f["hp"])
		order.append(f)
		fighters[nm] = f
		_update_hp(f)
	# câmera fixa enquadrando o campo + stepping por-evento (reusa a fase "war")
	cam.position = Vector3(0.0, 4.8, 8.4)
	cam.look_at(Vector3(0, 1.2, 0), Vector3.UP)
	war_events = events
	war_i = 0
	war_t = 0.0
	phase = "war"

# z da lane de cada inimigo, centralizado pela contagem (1→0; 2→±1.4; 3→TEAM_ROWS). [TORRE_GRUPO]
func _group_lane_z(idx: int, count: int) -> float:
	if count <= 1:
		return 0.0
	if count == 2:
		return -1.4 if idx == 0 else 1.4
	return TEAM_ROWS[clampi(idx, 0, TEAM_ROWS.size() - 1)]

# [GODOT_PAPERDOLL] Veste UM lutador: esconde a base nua, põe a cabeça sempre, roupa no slot
# equipado e a pele cortada no slot vazio (a roupa cobre o resto → 0 clipping). Se as peças
# cortadas não existirem, mantém a base visível (nu, mas não invisível).
# [TEAM_MOCK] Monta uma batalha 3 contra 3. Reusa _make_fighter; cada lado numa coluna (eixo Z).
# Aliados à esquerda (side=-1, o 1º com o equip/arma REAIS do herói), inimigos à direita (cara própria).
func _build_team() -> void:
	team_mode = true
	kiting = false
	order = []
	_team_wave = 1
	_resv_ally = []
	_resv_foe = []
	var foe_arch := ["Bandido", "Saqueador", "Capanga"]   # arquétipos conhecidos (_enemy_look dá o visual)
	for i in 15:   # [GUERRA_GAUNTLET] specs dos 15 de cada lado; spawnam 3 por vez (Modelo B)
		var aname := "Você" if i == 0 else "Aliado %d" % (i + 1)
		_resv_ally.append({
			"name": aname,
			"weapon": (player_weapon if i == 0 else "sword"),   # [UNARMED] o herói (i==0) pode ficar desarmado; aliados usam espada
			"equip": (player_equip if (i == 0 and player_equip.size() > 0) else DEFAULT_OUTFIT),
			"rar": player_weapon_rarity if i == 0 else 1,
			"hp": 100,
		})
		var fname := "%s %d" % [foe_arch[i % foe_arch.size()], i + 1]
		_resv_foe.append({"name": fname, "hp": 100, "look": _enemy_look(fname, false)})
	for lane in 3:
		_spawn_team(_resv_ally.pop_front(), -1, lane)
	for lane in 3:
		_spawn_team(_resv_foe.pop_front(), 1, lane)

# [GUERRA_GAUNTLET] Spawna um lutador (de um spec) no campo, na lane dada. Reusa _make_fighter.
func _spawn_team(spec: Dictionary, team: int, lane: int) -> Dictionary:
	var f: Dictionary
	if team == -1:
		f = _make_fighter(str(spec["name"]), -1, int(spec["hp"]), str(spec["weapon"]),
				(spec["equip"] as Array).duplicate(), {}, int(spec["rar"]))
	else:
		var look: Dictionary = spec["look"]
		f = _make_fighter(str(spec["name"]), 1, int(spec["hp"]), str(look["weapon"]),
				look.get("equip", DEFAULT_OUTFIT), {}, 1, look)
	var n := f["node"] as Node3D
	n.position = Vector3(float(team) * TEAM_X, f["base_y"], TEAM_ROWS[lane])
	f["home"] = n.position
	f["anchor"] = n.position   # ponto de onde ataca; vira o lugar do morto ao vencer
	f["team"] = team
	f["lane"] = lane
	f["ctarget"] = ""          # _tick_team acha o inimigo vivo mais próximo
	f["tstate"] = "approach"
	f["ttimer"] = 0.0
	order.append(f)
	fighters[str(f["name"])] = f
	return f

# [GUERRA_GAUNTLET] Repõe o campo do `team` com até 3 frescos da reserva (só o perdedor repõe — Modelo B).
func _refill_side(team: int) -> void:
	var resv: Array = _resv_ally if team == -1 else _resv_foe
	var n := mini(3, resv.size())
	for lane in n:
		_spawn_team(resv.pop_front(), team, lane)

# [GUERRA_GAUNTLET] Tira os corpos (mortos) entre ondas pra não acumular nós 3D na cena.
func _clear_corpses() -> void:
	var keep: Array = []
	for f in order:
		if f.get("dead", false):
			var n = f.get("node")
			if n != null and is_instance_valid(n): n.queue_free()
			var bar = f.get("bar")
			if bar != null and is_instance_valid(bar): bar.queue_free()
			fighters.erase(str(f["name"]))
		else:
			keep.append(f)
	order = keep

# ── [GUERRA_GAUNTLET] Replay da GUERRA real: orientado aos WarEvent do backend (não sim local) ──
func _start_war() -> void:
	team_mode = true   # reusa o desenho de time (lanes, billboard de HP)
	kiting = false
	war_events = events
	war_i = 0
	war_t = 0.0
	order = []
	cam.position = Vector3(0.0, 5.2, 8.6)   # câmera fixa enquadrando o campo (±2 x, 3 lanes)
	cam.look_at(Vector3(0, 1.2, 0), Vector3.UP)
	phase = "war"

func _war_step(dt: float) -> void:
	if war_i >= war_events.size():
		_finish()
		return
	war_t += dt
	if war_t < WAR_STEP:
		return
	war_t = 0.0
	# processa eventos até (incluindo) 1 golpe visível; spawn/wave/victory são instantâneos
	while war_i < war_events.size():
		var e: Dictionary = war_events[war_i]
		war_i += 1
		var ty := str(e.get("type", ""))
		_war_event(e)
		if ty == "attack" or ty == "crit" or ty == "miss" or ty == "volley" or ty == "dodge":
			break

func _war_event(e: Dictionary) -> void:
	# [SKILL_POPUP] ativa disparou na guerra → banner ícone+nome acima do ator (JSON manda null quando não há skill)
	var ability_v = e.get("ability")
	if ability_v != null and str(ability_v) != "":
		var u = fighters.get(str(e.get("actor", "")))
		if u != null and not u.get("dead", false): _skill_popup(u, str(ability_v))
	match str(e.get("type", "")):
		"spawn": _war_spawn(e)
		"attack": _war_hit(e, false)
		"crit": _war_hit(e, true)
		"volley": _war_hit(e, false)   # [HABILIDADES] golpe extra do Volley (tem dano/HP)
		"heal":
			var h = fighters.get(str(e.get("actor", "")))   # Second Wind: cura o próprio ator
			if h != null and not h.get("dead", false):
				h["hp"] = clampi(int(e.get("targetHp", h["hp"])), 0, int(h.get("maxhp", h["hp"])))
				_update_hp(h)
				_popup(_head(h), "+%d" % int(e.get("damage", 0)), Color(0.49, 0.99, 0.6), false)
		"dodge":
			var dd = fighters.get(str(e.get("actor", "")))    # Evasive Roll: o ator esquiva
			var rt = fighters.get(str(e.get("target", "")))   # o atacante leva o reflexo
			if dd != null: _popup(_head(dd), "DODGE", Color(0.62, 0.81, 1), false)
			if rt != null:
				rt["hp"] = clampi(int(e.get("targetHp", rt["hp"])), 0, int(rt.get("maxhp", rt["hp"])))
				_update_hp(rt)
				if int(rt["hp"]) <= 0: _kill(rt)
		"berserk":
			pass   # só o banner de skill (sem mudança de HP)
		"miss":
			var t = fighters.get(str(e.get("target", "")))
			if t != null: _popup(_head(t), "MISS", Color(0.62, 0.81, 1), false)
		"wave":
			_clear_corpses()
			_status("Onda %d" % int(e.get("wave", 0)))

# Lane livre (0–2) do lado (team -1 aliado / 1 inimigo); fallback 0.
func _war_free_lane(team: int) -> int:
	var occ := [false, false, false]
	for f in order:
		if not f.get("dead", false) and int(f.get("team", 0)) == team:
			var l := int(f.get("lane", -1))
			if l >= 0 and l < 3: occ[l] = true
	for i in 3:
		if not occ[i]: return i
	return 0

func _war_spawn(e: Dictionary) -> void:
	var nm := str(e.get("actor", ""))
	if nm == "" or fighters.has(nm): return
	var side := int(e.get("side", 0))            # 0 = atacante (aliado, esquerda) / 1 = defensor (direita)
	var team := -1 if side == 0 else 1
	var lane := _war_free_lane(team)
	var maxhp := maxi(1, int(e.get("targetMaxHp", 100)))
	var hp := clampi(int(e.get("targetHp", maxhp)), 0, maxhp)
	var f: Dictionary
	if team == -1:
		f = _make_fighter(nm, -1, maxhp, "sword", DEFAULT_OUTFIT.duplicate(), {}, 1)
	else:
		var look := _enemy_look(nm, false)
		f = _make_fighter(nm, 1, maxhp, str(look["weapon"]), look.get("equip", DEFAULT_OUTFIT), {}, 1, look)
	var n := f["node"] as Node3D
	n.position = Vector3(float(team) * TEAM_X, f["base_y"], TEAM_ROWS[lane])
	f["home"] = n.position
	f["anchor"] = n.position
	f["team"] = team
	f["lane"] = lane
	f["hp"] = hp
	f["shown_hp"] = float(hp)
	order.append(f)
	fighters[nm] = f
	_update_hp(f)

func _war_hit(e: Dictionary, crit: bool) -> void:
	var t = fighters.get(str(e.get("target", "")))
	if t == null or t.get("dead", false): return
	var a = fighters.get(str(e.get("actor", "")))
	if a != null and not a.get("dead", false):
		_face_node(a, t["node"])
		if a.get("anim"):
			a["anim"].play(_clip(a, "attack") if a.get("is_monster", false) else _melee_clip(a), BLEND)
	var dmg := int(e.get("damage", 0))
	var zone := str(e.get("hitZone", "body"))
	if zone == "": zone = "body"
	var head := zone == "head"
	if t.get("anim"): t["anim"].play(_clip(t, "hurt_head" if head else "hurt"), BLEND)
	t["busy"] = true
	_popup(_chest(t), "-%d" % dmg, Color(1, 0.32, 0.32) if crit else Color(1, 1, 1), crit)
	var bdir: Vector3 = (((t["node"] as Node3D).global_position - (a["node"] as Node3D).global_position) * Vector3(1, 0, 1)) if a != null else Vector3.RIGHT
	t["last_hit_dir"] = bdir.normalized() if bdir.length() > 0.01 else Vector3.RIGHT
	t["last_hit_crit"] = crit
	var bpos: Vector3 = _hit_pos(t, zone)
	_blood_spray(bpos, bdir, dmg, crit, "")
	var icol: Color = _impact_color("")
	_sparks(bpos, icol, crit)
	_flash_at(bpos, icol)
	if crit:
		_light_flicker()
		_blood_mist(bpos)
	t["hp"] = clampi(int(e.get("targetHp", t["hp"])), 0, int(t.get("maxhp", t["hp"])))
	_update_hp(t)
	if int(t["hp"]) <= 0:
		_kill(t)
	elif dmg > 0:
		_on_impact(crit)

# [OUTFITS_FEMALE][SKIN_RARIDADE] veste o lutador com um SET NOVO (tema/gênero/variante do `look`)
# + recolor por raridade. look = {theme, gender, rarity, seed}; ausente → ranger male cor base.
func _dress(node: Node3D, skel: Skeleton3D, equipped_types: Array, look := {}) -> void:
	if skel == null: return
	var theme := str(look.get("theme", "ranger"))
	var gender := "female" if str(look.get("gender", "male")) == "female" else "male"
	var rarity := int(look.get("rarity", 1))
	var seed_name := str(look.get("seed", ""))
	var g := "Female" if gender == "female" else "Male"
	var body_meshes: Array = []
	_collect_meshes(node, body_meshes)   # base do addon (corpo+cabeça) — esconder inteira
	for m: MeshInstance3D in body_meshes:
		m.visible = false
	var head: PackedScene = load("res://assets/base/Base_%s_Head.gltf" % g)   # rosto sempre
	if head: _attach_outfit_to(skel, head)
	for ty in OutfitsLib.ARMOR_SLOTS:
		if ty in equipped_types:
			var path := OutfitsLib.piece_path_theme(theme, ty, gender, seed_name)
			if path != "" and ResourceLoader.exists(path):
				var sc: PackedScene = load(path)
				if sc: _attach_outfit_to(skel, sc, theme, rarity)   # recolore pela raridade
	# pele nua (cortada no Blender) nos slots SEM roupa — gênero-aware
	var part_slot := {"Torso": "ARMOR", "Arms": "GLOVES", "Legs": "PANTS", "Feet": "BOOTS"}
	for part in part_slot:
		if not (str(part_slot[part]) in equipped_types):
			var p: PackedScene = load("res://assets/base/Base_%s_%s.gltf" % [g, part])
			if p: _attach_outfit_to(skel, p)

# [OUTFITS] Veste o HERÓI com os ITENS REAIS equipados (tema+variante+raridade de CADA peça), igual ao
# boneco/bust — não o set aleatório por nome. Espelha BustView/DollView/MenuDuel._dress_from_inv.
func _dress_from_inv(node: Node3D, skel: Skeleton3D, inv_arr: Array, gender: String) -> void:
	if skel == null: return
	var g := "Female" if gender == "female" else "Male"
	var body_meshes: Array = []
	_collect_meshes(node, body_meshes)
	for m: MeshInstance3D in body_meshes:
		m.visible = false
	var head: PackedScene = load("res://assets/base/Base_%s_Head.gltf" % g)   # rosto sempre
	if head: _attach_outfit_to(skel, head)
	var dressed := {}
	for it in inv_arr:
		if it is Dictionary and it.get("equipped") == true:
			var ty := str(it.get("type", ""))
			if OutfitsLib.is_armor_slot(ty):
				var path := OutfitsLib.piece_path_item(it, ty, gender)   # tema+variante do ITEM
				if path != "" and ResourceLoader.exists(path):
					var sc: PackedScene = load(path)
					if sc:
						_attach_outfit_to(skel, sc, OutfitsLib.theme_for_item(it), int(it.get("rarity", 1)))
						dressed[ty] = true
	# pele nua (cortada no Blender) nos slots de CORPO sem peça vestida
	var part_slot := {"Torso": "ARMOR", "Arms": "GLOVES", "Legs": "PANTS", "Feet": "BOOTS"}
	for part in part_slot:
		if not dressed.has(part_slot[part]):
			var p: PackedScene = load("res://assets/base/Base_%s_%s.gltf" % [g, part])
			if p: _attach_outfit_to(skel, p)

func _attach_outfit_to(skel: Skeleton3D, scene: PackedScene, theme := "", rarity := 0) -> void:
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
		mi.skeleton = NodePath("..")   # esqueleto compartilhado anima a peça junto
		if theme != "" and rarity > 0:
			OutfitsLib.recolor_mesh(mi, theme, rarity)
	inst.queue_free()

func _collect_meshes(node: Node, out: Array) -> void:
	if node is MeshInstance3D:
		out.append(node)
	for c in node.get_children():
		_collect_meshes(c, out)

# [OUTFITS] Aparência do SET (tema/gênero/cor/variante) determinística pelo NOME → cada lutador veste
# um set diferente (pra ver os sets novos na batalha). A raridade aqui controla só a COR do set.
# [TORRE_VESTE] Sets temáticos dos MVPs da Torre (chefes de história): tema + recolor (raridade alta
# = cor nobre/dourada). O nome casa por SUBSTRING (o backend manda "<Nome> (Floor N)"). Coroa vem do
# tema noble (HELMET=Crown); elmo/ombreira do knight; robe do wizard — tudo via SLOT_PIECE do tema.
const TOWER_MVP_LOOK := {
	"Fallen Captain": {"theme": "knight", "rarity": 4},   # capitão da guarda caída — armadura completa
	"Coin-Eaten":     {"theme": "noble",  "rarity": 5},   # nobre apodrecido em ouro — coroa dourada
	"Crowned Echo":   {"theme": "noble",  "rarity": 4},   # eco oco do Rei — coroa
	"Xam":            {"theme": "wizard", "rarity": 4},   # o Xamã — manto (casa "Xamã" por prefixo)
	"Rei Arka":       {"theme": "noble",  "rarity": 5},   # o Rei — coroa + dourado
}
const TOWER_FULL_SET := ["ARMOR", "GLOVES", "BOOTS", "PANTS", "HELMET", "SHOULDER"]

# [INCURSAO_VESTE] Cenas da Incursão/Zona (não-Torre): inimigos SEMPRE bem vestidos, tema pelo BIOMA
# (hash escolhe entre 2 → variedade). Resolve "inimigos sem set" + skin relacionada à zona. [INCURSAO_PVP]
const DELVE_SCENES := ["fortress", "coast", "sea", "cave"]
const DELVE_SCENE_THEMES := {
	"fortress": ["knight", "noble"],   # fortaleza maldita — guarda/corte morta
	"cave":     ["knight", "ranger"],  # grutas/mina — brutos e batedores
	"coast":    ["ranger", "noble"],   # costa/pesca — batedores e saqueadores
	"sea":      ["ranger", "knight"],  # mar abençoado — marujos amaldiçoados
}

func _is_delve_scene() -> bool:
	return fight_scene in DELVE_SCENES

# tema do inimigo da Incursão pelo bioma da cena (hash escolhe entre os 2 do bioma → variedade).
func _delve_theme_for(nm: String) -> String:
	var opts: Array = DELVE_SCENE_THEMES.get(fight_scene, ["knight", "ranger"])
	return str(opts[absi(hash(nm)) % opts.size()])

# [TORRE_VESTE] tema do inimigo comum da Torre pela "cara" do nome: culto→wizard, corte→noble, guarda→knight.
func _tower_theme_for(nm: String) -> String:
	var n := nm.to_lower()
	for kw in ["acolyte", "priest", "chant", "rite", "wraith", "censer", "crystal", "vintner", "unborn",
			"whisper", "becoming", "echo", "shaman", "xam", "scholar", "altar", "sleeper", "dream", "seal"]:
		if kw in n: return "wizard"
	for kw in ["gilded", "courtier", "jewel", "coin", "duelist", "crowned", "pretender", "kneel",
			"tally", "powder", "mirror", "faceless", "feast", "ash", "eager", "weeping"]:
		if kw in n: return "noble"
	return "knight"   # guarda/sentinela/husk/gate/etc + default = armadura

# [TORRE_VESTE] é um MVP (chefe de história)? casa o nome-base por substring.
func _tower_is_mvp(nm: String) -> bool:
	for key in TOWER_MVP_LOOK:
		if key in nm: return true
	return false

func _appearance(nm: String) -> Dictionary:
	var h := absi(hash(nm))
	if fight_scene == "tower":   # [TORRE_VESTE] MVP = set temático + recolor alto; comum = tema pela cara do nome
		for key in TOWER_MVP_LOOK:
			if key in nm:
				var mv: Dictionary = TOWER_MVP_LOOK[key]
				return {"theme": str(mv["theme"]), "gender": "male", "rarity": int(mv["rarity"]), "seed": nm}
		return {"theme": _tower_theme_for(nm), "gender": ("female" if (h / 3) % 2 == 1 else "male"),
				"rarity": 1 + (h / 7) % 3, "seed": nm}   # comum: raridade 1-3 (cor leve)
	if _is_delve_scene():   # [INCURSAO_VESTE] Incursão/Zona = tema por bioma + recolor por raridade
		return {"theme": _delve_theme_for(nm), "gender": ("female" if (h / 3) % 2 == 1 else "male"),
				"rarity": 1 + (h / 7) % 4, "seed": nm}
	var themes: Array = OutfitsLib.THEME_ORDER
	return {
		"theme":  str(themes[h % themes.size()]),
		"gender": "female" if (h / 3) % 2 == 1 else "male",
		"rarity": (h / 7) % 5 + 1,   # 1..5 → banda de cor do tema [SKIN_RARIDADE]
		"seed":   nm,                # → escolhe a variante da peça (elmo/ombreira/peitoral) [OUTFITS_VARIANTES]
	}

# [INIMIGO] "cara própria" determinística pelo NOME (mesmo oponente = mesmo visual): {weapon, tint, scale, equip}.
func _enemy_look(nm: String, ranged: bool) -> Dictionary:
	var h := absi(hash(nm))
	var weapon: String = ENEMY_BOWS[(h / 7) % ENEMY_BOWS.size()] if ranged else ENEMY_MELEE[(h / 7) % ENEMY_MELEE.size()]
	var equip: Array
	var scale := 0.86 + float((h / 13) % 16) * 0.01   # 0.86 .. 1.01
	if fight_scene == "tower":
		# [TORRE_VESTE] Torre = sempre BEM vestido (nunca pelado/sem peito). MVP = set completo + maior;
		# comum = quase completo (raras sem elmo). Wizard (culto) empunha "cajado" (mace) em vez de espada.
		var is_mvp := _tower_is_mvp(nm)
		equip = TOWER_FULL_SET.duplicate()
		if is_mvp:
			scale = 1.10
		elif (h / 101) % 4 == 0:
			equip.erase("HELMET")   # ~25% dos comuns sem elmo (variedade), mas nunca pelado
		if not ranged and _tower_theme_for(nm) == "wizard":
			weapon = "mace"
	elif _is_delve_scene():
		# [INCURSAO_VESTE] Incursão/Zona = SEMPRE bem vestido (set completo, nunca pelado); ~20% sem elmo.
		equip = TOWER_FULL_SET.duplicate()
		if (h / 101) % 5 == 0:
			equip.erase("HELMET")
	else:
		# peças variam: às vezes sem capacete / sem parte de cima / pelado (resto = completo)
		var style := (h / 101) % 10
		if style == 0:                              # ~10% PELADO
			equip = []
		elif style <= 2:                            # ~20% sem capacete
			equip = ["ARMOR", "PANTS", "BOOTS", "GLOVES", "SHOULDER"]
		elif style == 3:                            # ~10% sem parte de cima (peito nu)
			equip = ["PANTS", "BOOTS", "GLOVES"]
		elif style == 4:                            # ~10% só calça/botas
			equip = ["PANTS", "BOOTS"]
		else:                                       # ~50% completo
			equip = DEFAULT_OUTFIT.duplicate()
	return {
		"weapon": weapon,
		"tint": ENEMY_TINTS[h % ENEMY_TINTS.size()],
		"scale": scale,
		"equip": equip,
	}

# Lavagem de cor (faction) por cima da roupa — overlay translúcido, preserva a textura.
func _tint_body(node: Node3D, color: Color) -> void:
	var meshes: Array = []
	_collect_meshes(node, meshes)
	var ov := StandardMaterial3D.new()
	ov.albedo_color = Color(color.r, color.g, color.b, 0.32)   # wash sutil
	ov.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	for mi: MeshInstance3D in meshes:
		if mi.visible:                       # só as peças vestidas (a base nua está oculta)
			mi.material_overlay = ov

# Lê o inventário: armadura equipada → player_equip; arma equipada → player_weapon (tipo visual).
func _read_player_gear(items: Array) -> void:
	# [UNARMED] reset → sem arma/escudo equipado = desarmado (sem espada residual)
	player_items = []
	player_equip = []
	player_weapon = ""
	player_shield_rarity = 1
	player_weapon_rarity = 1
	for it in items:
		if not (it is Dictionary) or it.get("equipped") != true:
			continue
		player_items.append(it)   # [OUTFITS] guarda o item real p/ vestir o herói com o tema/variante/raridade dele
		var ty := str(it.get("type", ""))
		if PIECES.has(ty) and not (ty in player_equip):
			player_equip.append(ty)
		elif ty == "SHIELD":
			if not ("SHIELD" in player_equip):
				player_equip.append("SHIELD")   # marca p/ desenhar o escudo na off-hand (_make_fighter)
			player_shield_rarity = int(it.get("rarity", 1))
		elif ty == "WEAPON":
			player_weapon = wp.weapon_kind(str(it.get("name", "")), str(it.get("weaponCategory", "")))
			player_weapon_rarity = int(it.get("rarity", 1))

# (tipo visual da arma + is_bow_kind foram p/ Weapons.gd — use wp.weapon_kind / wp.is_bow_kind)

func _is_ranged(who: String) -> bool:
	for e in events:
		if str(e.get("actor", "")) == who and str(e.get("type", "")) in RANGED_MARKERS:
			return true
	return false

func _make_fighter(fname: String, side: int, maxhp: int, weapon_kind: String, equipped_types: Array, monster_meta := {}, rarity := 1, look := {}, inv_items := []) -> Dictionary:
	var is_monster := not monster_meta.is_empty()
	var is_player := not inv_items.is_empty()   # [OUTFITS] herói → veste os ITENS REAIS (não o set por nome)
	var aplook := _appearance(fname)   # [OUTFITS] set novo (tema/gênero/cor/variante) pelo nome (inimigos)
	var ap_gender := player_gender if is_player else str(aplook["gender"])
	var char_scene: PackedScene = CHAR_FEMALE if ap_gender == "female" else CHAR
	var node: Node3D = mons.instance(str(monster_meta.get("file", ""))) if is_monster else char_scene.instantiate()
	if node == null:   # monstro não carregou → cai no humano p/ não travar a cena
		node = char_scene.instantiate(); is_monster = false
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
		var hs := float(look.get("scale", 0.92))   # [INIMIGO] porte (humano = 0.92; inimigo varia pelo nome)
		node.scale = Vector3(hs, hs, hs)
	# monstro é sempre melee (sem arco/kite); herói/humano segue a arma equipada
	var ranged := wp.is_bow_kind(weapon_kind) and not is_monster
	var ap: AnimationPlayer = node.find_child("AnimationPlayer", true, false)
	# liga a lib UAL2 (variações de espada) — só no humano; monstro usa as próprias anims
	if ap and not is_monster and not ap.has_animation_library("UAL2_Standard"):
		var lib2 = load(UAL2_PATH)
		if lib2 is AnimationLibrary:
			ap.add_animation_library("UAL2_Standard", lib2)
	var skel: Skeleton3D = node.find_child("GeneralSkeleton", true, false)
	var yaw_off := deg_to_rad(monster_face_offset_deg) if is_monster else 0.0
	var unarmed := weapon_kind == "" and not is_monster   # [UNARMED] herói sem arma → soco
	var f := {"name": fname, "node": node, "anim": ap, "side": side, "ranged": ranged, "unarmed": unarmed,
			  "dead": false, "maxhp": max(1, maxhp), "hp": max(1, maxhp), "busy": false, "hopping": false,
			  "vel": 0.0, "shown_hp": float(max(1, maxhp)), "is_monster": is_monster, "yaw_offset": yaw_off,
			  "base_y": base_y, "bar_off": bar_off,
			  "amap": (_monster_anim_map(ap) if is_monster else {}),
			  "face_target": deg_to_rad(90.0 if -side > 0 else -90.0) + yaw_off}
	node.rotation.y = f["face_target"]   # nasce já virado pro centro (sem lerp do zero)
	_face(f, -side)   # seta o alvo de rotação (encara o oponente)
	# [JUICE] rim light fria atrás (separa do fundo escuro grimdark); segue o lutador no _process
	var rim := OmniLight3D.new()
	rim.light_color = Color(0.55, 0.7, 1.0)
	rim.light_energy = 1.6
	rim.omni_range = 3.4
	rim.shadow_enabled = false
	add_child(rim)
	f["rim"] = rim
	if not is_monster:
		if is_player:
			_dress_from_inv(node, skel, inv_items, player_gender)   # [OUTFITS] herói = itens REAIS equipados
		else:
			_dress(node, skel, equipped_types, aplook)   # inimigo = set por nome (variedade)
		var armor_n := 0                       # [SKIN_RARIDADE] quão completo é o set → aura mais visível
		for ty in equipped_types:
			if OutfitsLib.is_armor_slot(str(ty)): armor_n += 1
		var aura := OutfitsLib.rarity_aura(int(aplook["rarity"]), float(armor_n) / 6.0)   # brasas (raro+)
		if aura:
			node.add_child(aura)
			f["aura"] = aura
		if weapon_kind != "":   # [UNARMED] sem arma → não anexa modelo (soco)
			# [ARMAS_3D] override de rotação só no herói (p/ calibrar a arma no Inspector)
			var wrot := weapon_rot_override if is_player else Vector3(999, 999, 999)
			f["weapon_node"] = wp.attach_weapon(node, weapon_kind, rarity, weapon_grip, "", wrot)   # [JUICE] p/ o rastro
		# escudo na off-hand — só sem arma DE DUAS MÃOS (arco usa as duas mãos); desarmado pode escudar
		if ("SHIELD" in equipped_types) and not wp.is_bow_kind(weapon_kind):
			var sh_r := force_rarity if force_rarity > 0 else player_shield_rarity   # [RARIDADE] escudo
			wp.attach_shield(node, {"slide": shield_slide, "push": shield_push, "side": shield_side, "up": shield_up, "flip": shield_flip, "rot": shield_rot, "rarity": sh_r})
	# barra de vida (3D) + nome
	var bar := Node3D.new()
	add_child(bar)
	var bg_quad := _quad(BARW, 0.09, Color(0, 0, 0, 0.55), 0)
	bar.add_child(bg_quad)
	var fill := _quad(BARW, 0.09, Color(0.25, 0.85, 0.35, 1.0), 1)
	bar.add_child(fill)
	# valor de vida (atual/máx) DENTRO da barra verde [BATALHA]
	var hp_lbl := Label3D.new()
	hp_lbl.text = "%d/%d" % [int(f["maxhp"]), int(f["maxhp"])]
	hp_lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	hp_lbl.no_depth_test = true
	hp_lbl.render_priority = 3          # acima do preenchimento (prio 1) → não some atrás da barra
	hp_lbl.outline_render_priority = 2
	hp_lbl.pixel_size = 0.0022
	hp_lbl.font_size = 40
	hp_lbl.outline_size = 14
	hp_lbl.outline_modulate = Color(0, 0, 0, 0.9)
	bar.add_child(hp_lbl)
	if CORNER_HP:   # [HP_CANTO] a vida vai pro HUD no canto → esconde a barra 3D (fica só o nome acima da cabeça)
		bg_quad.visible = false
		fill.visible = false
		hp_lbl.visible = false
	var name_lbl := Label3D.new()
	name_lbl.text = fname + ("  🏹" if ranged else "")
	name_lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	name_lbl.no_depth_test = true
	name_lbl.pixel_size = 0.0032   # [HP_CANTO] um pouco menor
	name_lbl.font_size = 40
	name_lbl.modulate = Color(0.46, 0.92, 0.5) if side < 0 else Color(0.97, 0.42, 0.40)  # aliado verde / inimigo vermelho
	name_lbl.outline_modulate = Color(0, 0, 0, 0.95)
	name_lbl.outline_size = 12
	# [HP_CANTO] nome no PÉ do personagem (não mais acima da cabeça): bar fica em +bar_off → desce de volta ao chão
	name_lbl.position = Vector3(0, -bar_off + 0.18, 0)
	bar.add_child(name_lbl)
	f["bar"] = bar
	f["fill"] = fill
	f["hp_lbl"] = hp_lbl
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
	# [JUICE] camera shake (trauma² = golpes grandes tremem MUITO mais)
	if cam:
		_shake_time += dt
		if cam_shake > 0.001:
			cam_shake = maxf(0.0, cam_shake - dt * SHAKE_DECAY)
			var a := cam_shake * cam_shake * 0.20   # [CAM_LENTE] lente longa amplifica o shake → reduzido
			cam.h_offset = _shake_noise.get_noise_1d(_shake_time * 55.0) * a
			cam.v_offset = _shake_noise.get_noise_1d(_shake_time * 55.0 + 99.0) * a
		elif cam.h_offset != 0.0 or cam.v_offset != 0.0:
			cam.h_offset = 0.0; cam.v_offset = 0.0
	if not _trails.is_empty():
		_update_trails(dt)   # [JUICE] rastros de arma
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
		if f.has("rim") and is_instance_valid(f["rim"]):   # [JUICE] rim light atrás (−Z, longe da câmera) + acima
			f["rim"].global_position = n.global_position + Vector3(0, 1.9, -1.1)
	if CORNER_HP:
		_update_hud()   # [HP_CANTO] barras de vida no canto (estilo jogo de luta)
	match phase:
		"countdown": _countdown(dt)
		"war": _war_step(dt)
		"fight":
			if team_mode:
				_tick_team(dt)        # [TEAM_MOCK] sim ao vivo (3 duelos simultâneos)
			else:
				_move(dt)
				_advance(dt)
				_track_camera(dt)     # [CAM_TRACK] câmera 1v1 segue o MEIO da dupla (lag suave)

# Contagem 3,2,1 → Lutar! Os lutadores dançam (warm-up) e encaram o oponente.
func _countdown(dt: float) -> void:
	countdown_t += dt
	for f in order:
		if not f["dead"] and f["anim"]: _play_loop(f, _clip(f, "dance"))
	var t := countdown_t
	var beat := 0
	if t < CD_STEP: beat = 0
	elif t < 2.0 * CD_STEP: beat = 1
	elif t < 3.0 * CD_STEP: beat = 2
	elif t < 3.0 * CD_STEP + CD_WORD: beat = 3
	else:
		countdown_tex.visible = false
		for f in order:
			if f["anim"]: f["anim"].play(_clip(f, "idle"), BLEND)
		phase = "fight"
		return
	if beat != _cd_beat:
		_cd_beat = beat
		_pop_cd(beat)

# [COUNTDOWN_ART] troca a textura do beat com um "pop" (escala a partir do centro + fade).
func _pop_cd(beat: int) -> void:
	if beat < 0 or beat >= _cd_tex.size() or _cd_tex[beat] == null:
		return
	countdown_tex.texture = _cd_tex[beat]
	countdown_tex.visible = true
	countdown_tex.pivot_offset = countdown_tex.size / 2.0   # escala a partir do centro
	var big := beat == 3                                     # o LUTAR/FIGHT entra com pop maior
	countdown_tex.scale = (Vector2(0.4, 0.4) if big else Vector2(0.55, 0.55))
	countdown_tex.modulate = Color(1, 1, 1, 0.0)
	var tw := create_tween().set_ignore_time_scale(true)    # a contagem não sofre slow-mo
	tw.tween_property(countdown_tex, "modulate:a", 1.0, 0.10)
	tw.parallel().tween_property(countdown_tex, "scale", Vector2.ONE, (0.30 if big else 0.20)).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)

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
# [TEAM_MOCK] Movimento no 3v3: o MELEE do evento atual avança até o alvo durante a aproximação;
# o resto fica parado. Arqueiro atira de onde está. Sem clash/kite 1x1 (são vários lutadores).
# [TEAM_MOCK] Sim AO VIVO do 3v3: cada lutador roda seu PRÓPRIO loop (approach→windup→strike→
# recover) em PARALELO → os 3 duelos acontecem ao MESMO tempo. Ao matar o alvo, re-mira no inimigo
# vivo mais próximo (ajuda a lane do lado). Não usa o stream de eventos (idx/act_state).
func _tick_team(dt: float) -> void:
	var na := 0
	var nf := 0
	for f in order:
		if not f["dead"]:
			if int(f["team"]) == -1: na += 1
			else: nf += 1
	if na == 0 or nf == 0:
		# [GUERRA_GAUNTLET] Modelo B: o lado ZERADO (perdedor) repõe 3 frescos; o vencedor mantém os sobreviventes.
		var refill_ally := na == 0 and not _resv_ally.is_empty()
		var refill_foe := nf == 0 and not _resv_foe.is_empty()
		if refill_ally or refill_foe:
			_clear_corpses()
			_team_wave += 1
			if refill_ally: _refill_side(-1)
			if refill_foe: _refill_side(1)
			_status("Onda %d" % _team_wave)
		else:
			_finish()
		return
	for f in order:
		if f["dead"]:
			continue
		var tgt = fighters.get(str(f.get("ctarget", "")))
		if tgt == null or tgt["dead"]:
			tgt = _nearest_enemy_fighter(f)            # alvo caiu → ajuda o duelo do lado
			f["ctarget"] = "" if tgt == null else str(tgt["name"])
		if tgt == null:
			continue
		var sn := f["node"] as Node3D
		var tn := tgt["node"] as Node3D
		match str(f.get("tstate", "approach")):
			"approach":
				# direção de ataque = do ANCHOR do lutador até o alvo (o vencedor herda o LUGAR do morto →
				# ataca o próximo vindo da posição do morto, perpendicular a quem ainda luta na lane).
				var anchor: Vector3 = f.get("anchor", sn.position)
				var atk_dir := Vector3(anchor.x - tn.position.x, 0.0, anchor.z - tn.position.z)
				if atk_dir.length() < 0.01: atk_dir = Vector3(float(f["team"]), 0, 0)
				atk_dir = atk_dir.normalized()
				# 2+ no mesmo alvo → desloca cada um LATERALMENTE (perpendicular) p/ garantir que não empilham
				var co: Array = []
				for o in order:
					if not o["dead"] and int(o["team"]) == int(f["team"]) and str(o.get("ctarget", "")) == str(tgt["name"]):
						co.append(str(o["name"]))
				co.sort()
				var slot := maxi(0, co.find(str(f["name"])))
				var nslot := maxi(1, co.size())
				var perp := Vector3(atk_dir.z, 0.0, -atk_dir.x)
				var lateral := (float(slot) - float(nslot - 1) * 0.5) * 1.5   # [FLUIDEZ D] espaça mais (anti-empilha)
				var desired := tn.position + atk_dir * ATTACK_RANGE + perp * lateral
				desired.y = f["base_y"]
				# [FLUIDEZ] só move quando NÃO está em golpe/flinch (busy) → planta o ataque (sem deslizar
				# enquanto a espada balança) e o flinch fica no lugar. Vira (face) continua suave abaixo.
				if not f["ranged"] and not f["busy"]:
					var prev := sn.position
					sn.position = sn.position.move_toward(desired, MELEE_SPEED * dt)
					if f["anim"]:
						if sn.position.distance_to(prev) > 0.004:
							if f["anim"].current_animation != A_WALK: f["anim"].play(A_WALK, BLEND)
						elif f["anim"].current_animation != _clip(f, "idle"):
							f["anim"].play(_clip(f, "idle"), BLEND)
				_face_node(f, tn)   # encara o alvo DE VERDADE (X+Z), não só esquerda/direita
				var in_range: bool = f["ranged"] or sn.position.distance_to(tn.position) <= ATTACK_RANGE + 0.3
				if in_range and not f["busy"]:
					f["tstate"] = "windup"
					f["ttimer"] = 0.0
					f["cur_w"] = WINDUP * randf_range(0.85, 1.3)
					f["busy"] = true
					if f["anim"]:
						var clip: String
						if f.get("is_monster", false): clip = _clip(f, "attack")
						elif f["ranged"]: clip = A_SHOOT
						else: clip = _melee_clip(f)
						f["anim"].play(clip, BLEND)
						_start_weapon_trail(f)   # [JUICE] rastro de arma
			"windup":
				f["ttimer"] += dt
				if f["ttimer"] >= float(f.get("cur_w", WINDUP)):
					_team_strike(f, tgt)
					f["tstate"] = "recover"
					f["ttimer"] = 0.0
					f["cur_r"] = RECOVER * randf_range(0.9, 1.7)
			"recover":
				f["ttimer"] += dt
				if f["ttimer"] >= float(f.get("cur_r", RECOVER)):
					f["tstate"] = "approach"

# [TEAM_MOCK] Aplica um golpe ao vivo (dano + efeitos), espelhando o ramo de HIT do _resolve.
func _team_strike(a: Dictionary, t: Dictionary) -> void:
	if t["dead"]:
		return
	var crit := randf() < 0.16
	var dmg := (randi() % 6) + (20 if int(a["team"]) == -1 else 13)   # aliados batem mais → vencem
	if crit: dmg *= 2
	if a["ranged"]: _shoot_arrow(a, t)
	var zones := ["body", "legs", "head"]
	var zone: String = zones[randi() % zones.size()]
	var head := zone == "head"
	if t["anim"]: t["anim"].play(_clip(t, "hurt_head" if head else "hurt"), BLEND)
	t["busy"] = true
	_popup(_chest(t), "-%d" % dmg, Color(1, 0.32, 0.32) if crit else Color(1, 1, 1), crit)
	var bdir: Vector3 = ((t["node"] as Node3D).global_position - (a["node"] as Node3D).global_position) * Vector3(1, 0, 1)
	t["last_hit_dir"] = bdir.normalized() if bdir.length() > 0.01 else Vector3.RIGHT
	t["last_hit_crit"] = crit
	var bpos: Vector3 = _hit_pos(t, zone)
	_blood_spray(bpos, bdir, dmg, crit, "")
	var icol: Color = _impact_color("")
	_sparks(bpos, icol, crit)
	_flash_at(bpos, icol)
	if crit:
		_light_flicker()
		_blood_mist(bpos)
	t["hp"] = max(0, int(t["hp"]) - dmg)
	_update_hp(t)
	if int(t["hp"]) <= 0:
		a["anchor"] = (t["node"] as Node3D).position   # [TEAM_MOCK] vencedor TOMA O LUGAR do morto
		_kill(t)
	elif dmg > 0:
		_on_impact(crit)

# [TEAM_MOCK] Inimigo vivo mais PRÓXIMO (menor diferença de lane). Retorna o dict do lutador ou null.
func _nearest_enemy_fighter(f: Dictionary):
	var best = null
	var best_d := 999
	for o in order:
		if o["dead"] or int(o["team"]) == int(f["team"]):
			continue
		var d := absi(int(o.get("lane", 0)) - int(f.get("lane", 0)))
		if d < best_d:
			best_d = d
			best = o
	return best

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
		_footstep_dust(f, now_x)   # [JUICE] poeira nos passos
	elif ap.current_animation != _clip(f, "idle"):
		ap.play(_clip(f, "idle"), BLEND)

# Poeira a cada ~0.26s de corrida, nos pés do lutador (tom do cenário).
func _footstep_dust(f: Dictionary, x: float) -> void:
	var t: float = f.get("dust_t", 0.0) - get_process_delta_time()
	if t > 0.0:
		f["dust_t"] = t
		return
	f["dust_t"] = 0.26
	var pos := Vector3(x, f.get("base_y", 0.0) + 0.05, (f["node"] as Node3D).position.z)
	var p := GPUParticles3D.new()
	p.one_shot = true; p.amount = 7; p.lifetime = 0.6; p.explosiveness = 0.8
	var m := ParticleProcessMaterial.new()
	m.direction = Vector3.UP; m.spread = 35.0
	m.initial_velocity_min = 0.3; m.initial_velocity_max = 0.9
	m.gravity = Vector3(0, 0.4, 0)
	m.scale_min = 0.5; m.scale_max = 1.4
	var g := Gradient.new()
	g.set_color(0, Color(0.62, 0.56, 0.46, 0.0)); g.add_point(0.25, Color(0.62, 0.56, 0.46, 0.4)); g.set_color(2, Color(0.62, 0.56, 0.46, 0.0))
	var gt := GradientTexture1D.new(); gt.gradient = g; m.color_ramp = gt
	p.process_material = m
	var q := QuadMesh.new(); q.size = Vector2(0.4, 0.4)
	var qm := StandardMaterial3D.new()
	qm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	qm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	qm.vertex_color_use_as_albedo = true
	qm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	q.material = qm
	p.draw_pass_1 = q
	add_child(p); p.global_position = pos; p.emitting = true
	get_tree().create_timer(0.9).timeout.connect(p.queue_free)

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
			clip = _melee_clip(sw, ty == "crit")
		sw["anim"].play(clip, BLEND)
		_start_weapon_trail(sw)   # [JUICE] rastro de arma no swing (guard ranged/monstro)

# Golpe de espada aleatório (A/B/C da UAL2; fallback Sword_Attack da UAL1).
func _rand_sword(ap: AnimationPlayer) -> String:
	var pool: Array = SWORD_ATTACKS.filter(func(a): return ap.has_animation(a))
	if pool.is_empty(): return A_ATTACK
	return pool[randi() % pool.size()]

# Combo do crit (UAL2); fallback p/ um golpe normal se não existir.
func _combo_clip(ap: AnimationPlayer) -> String:
	return SWORD_COMBO if ap.has_animation(SWORD_COMBO) else _rand_sword(ap)

# [UNARMED] Golpe MELEE do lutador: DESARMADO → soco (Punch_Cross no crit, Jab/Cross variando);
# armado → espada (combo no crit). Centraliza a escolha p/ todos os call sites.
func _melee_clip(f: Dictionary, crit := false) -> String:
	var ap: AnimationPlayer = f["anim"]
	if f.get("unarmed", false):
		var cross := LIB + "Punch_Cross"
		var jab := LIB + "Punch_Jab"
		if crit and ap.has_animation(cross): return cross   # cross = o soco "forte"
		var pool: Array = [cross, jab].filter(func(a): return ap.has_animation(a))
		return pool[randi() % pool.size()] if not pool.is_empty() else A_ATTACK
	return _combo_clip(ap) if crit else _rand_sword(ap)

# Resolve o evento: aplica dano/HP/flinch/flecha/popup/morte (espelha o antigo impact+step_end).
func _resolve(e: Dictionary) -> void:
	var ty := str(e.get("type", ""))
	var act = fighters.get(str(e.get("actor", "")))
	var tgt = fighters.get(str(e.get("target", "")))
	var dmg := int(e.get("damage", 0))
	var ability_v = e.get("ability")   # [SKILL_POPUP] ativa disparou → banner ícone+nome no ator (JSON manda null quando não há skill)
	if ability_v != null and str(ability_v) != "" and act:
		_skill_popup(act, str(ability_v))
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
			# [JUICE] faíscas + flash no impacto (cor pelo elemento); crit pisca a luz-chave
			var icol := _impact_color(elem)
			_sparks(bpos, icol, big)
			_flash_at(bpos, icol)
			if big: _light_flicker()
			if big: _blood_mist(bpos)
			if big or randf() < 0.45:
				_blood_pool((tgt["node"] as Node3D).global_position + bdir.normalized() * randf_range(0.2, 0.6), big)
			# [JUICE] knockback (empurra o alvo) + label CRÍTICO + vinheta vermelha se o HERÓI levou
			_knockback(tgt, tgt.get("last_hit_dir", bdir), big)
			if big:
				_popup(_head(tgt), Lang.t("CRÍTICO!"), Color(1, 0.8, 0.25), true)
			if int(tgt.get("side", 1)) < 0:
				_damage_vignette(0.6 if big else 0.34)
		tgt["hp"] = int(e.get("targetHp", tgt["hp"]))
		_update_hp(tgt)
		if tgt["hp"] <= 0: _kill(tgt)
		elif dmg > 0: _on_impact(ty == "crit")   # [JUICE] golpe não-fatal: shake + hit-stop + fov
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
		f["maxhp"] = max(1, int(e.get("targetMaxHp", f["maxhp"])))
		f["hp"] = clampi(int(e.get("targetHp", f["maxhp"])), 0, int(f["maxhp"]))   # [HP_SPAWN] atual, não máximo
		f["shown_hp"] = float(f["hp"])   # barra começa no atual (sem drenar do cheio)
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
		victory_label.text = Lang.t("%s venceu!") % winner["name"]
		winner["busy"] = false
		_victory_flourish(winner)            # [JUICE] luz dourada subindo + brilho do vencedor
		if not loser.is_empty() and not winner["ranged"]:
			_stand_over(winner, loser)          # MELEE vem pra frente do corpo
		else:
			# arqueiro (ou sem perdedor): fica ONDE ESTÁ, só encara o corpo
			if not loser.is_empty():
				_face(winner, signf((loser["node"] as Node3D).position.x - (winner["node"] as Node3D).position.x))
			if winner["anim"]: winner["anim"].play(_clip(winner, "idle"), BLEND)
	_show_continue()

# Sai do replay UMA vez só: embutido → emite finished (App fecha + volta pra tela); standalone (F6) → troca de cena.
func _leave() -> void:
	if _left:
		return
	_left = true
	Engine.time_scale = 1.0
	if not external_battle.is_empty():
		finished.emit()
	else:
		get_tree().change_scene_to_file("res://App.tscn")

# Fim da batalha: botão "Continuar" GRANDE no centro + AUTO-FECHA em 5s (ambos chamam _leave).
func _show_continue() -> void:
	if victory_label == null:
		return
	var layer := victory_label.get_parent()
	if layer == null:
		return
	var btn := Button.new()
	btn.text = "Continuar  ▶"
	DarkButtonStyle.apply(btn)
	btn.add_theme_font_size_override("font_size", 30)
	btn.set_anchors_and_offsets_preset(Control.PRESET_CENTER)
	btn.offset_left = -160; btn.offset_right = 160
	btn.offset_top = 30; btn.offset_bottom = 100   # um pouco abaixo do centro (não tampa os lutadores)
	btn.pressed.connect(_leave)
	layer.add_child(btn)
	btn.grab_focus()
	get_tree().create_timer(5.0).timeout.connect(_leave)   # auto-fecha em 5s

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

# [TEAM_MOCK] Encara um ALVO arbitrário (X+Z), não só esquerda/direita — p/ ir ajudar a lane do lado.
# atan2(dx,dz) casa com a convenção do _face (+X→90°, -X→-90°); o _process gira suave até aqui.
func _face_node(f: Dictionary, tn: Node3D) -> void:
	var sn := f["node"] as Node3D
	var dx := tn.position.x - sn.position.x
	var dz := tn.position.z - sn.position.z
	if absf(dx) < 0.001 and absf(dz) < 0.001:
		return
	f["face_target"] = atan2(dx, dz) + f.get("yaw_offset", 0.0)

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
	_kill_cam(node, dir)   # [JUICE][CAM_KILL] slow-mo + reframe baixo 3/4 sobre a vítima (no 1v1)
	_env_pulse()  # [JUICE] glow floresce no kill (sangue + emissivos)
	if f.has("rim") and is_instance_valid(f["rim"]): (f["rim"] as OmniLight3D).queue_free()   # apaga a rim do morto (já pode ter sido liberada → guarda)
	if f.has("aura") and is_instance_valid(f["aura"]): (f["aura"] as Node).queue_free()   # apaga a aura de raridade
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
	if not GameSettings.gore_enabled(): return   # [CONFIG] gore desligado nas Configurações
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
	if f.has("hp_lbl") and is_instance_valid(f["hp_lbl"]):
		(f["hp_lbl"] as Label3D).text = "%d/%d" % [int(round(f["shown_hp"])), int(f["maxhp"])]

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
	if not GameSettings.gore_enabled(): return   # [CONFIG] gore desligado nas Configurações
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
	if not GameSettings.gore_enabled(): return   # [CONFIG] gore desligado nas Configurações
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
	if not GameSettings.gore_enabled(): return   # [CONFIG] gore desligado nas Configurações
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
	if not GameSettings.gore_enabled(): return   # [CONFIG] gore desligado nas Configurações
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

# [DANO_CRIT] Número de dano flutuante. CRÍTICO = número AMARELO num BURST vermelho (estilo Ragnarok);
# normal = número claro com contorno (mesmo estilo/anim → consistente). burst = arte PixelLab.
const BURST_TEX := "res://assets/ui/dmg_burst.png"
func _popup(pos: Vector3, text: String, color: Color, big: bool) -> void:
	var is_crit := big and text.begins_with("-")   # dano crítico (heal/miss/dodge passam big=false)
	var root := Node3D.new()
	add_child(root)
	root.global_position = pos
	var burst: Sprite3D = null
	if is_crit and ResourceLoader.exists(BURST_TEX):
		burst = Sprite3D.new()
		burst.texture = load(BURST_TEX)
		burst.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		burst.no_depth_test = true
		burst.pixel_size = 0.0072
		burst.render_priority = 1
		burst.scale = Vector3.ONE * clampf(text.length() * 0.26, 1.0, 1.9)  # cresce com o nº de dígitos
		root.add_child(burst)
	var lbl := Label3D.new()
	lbl.text = text
	lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	lbl.no_depth_test = true
	lbl.render_priority = 2   # sempre na frente do burst
	if is_crit:
		lbl.modulate = Color(1.0, 0.90, 0.16)              # amarelo Ragnarok
		lbl.outline_modulate = Color(0.32, 0.02, 0.02, 1)  # contorno vermelho-escuro
		lbl.outline_size = 9
		lbl.font_size = 80
		lbl.pixel_size = 0.0050
	else:
		lbl.modulate = color
		lbl.outline_modulate = Color(0, 0, 0, 0.9)
		lbl.outline_size = 7 if big else 6
		lbl.font_size = 64 if big else 56
		lbl.pixel_size = 0.0048 if big else 0.0044
	root.add_child(lbl)
	# [JUICE] "slam": estoura a escala (overshoot) + arco pra cima + fade. Burst pulsa junto e some antes.
	var pop := 2.0 if is_crit else (1.5 if big else 1.35)
	root.scale = Vector3(pop, pop, pop)
	var tw := create_tween().set_parallel(true)
	tw.tween_property(root, "scale", Vector3.ONE, 0.24 if is_crit else 0.22).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	tw.tween_property(root, "global_position", pos + Vector3(0, 0.9, 0), 0.85).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)
	tw.tween_property(lbl, "modulate:a", 0.0, 0.8).set_delay(0.30)
	if burst != null:
		burst.create_tween().tween_property(burst, "modulate:a", 0.0, 0.5).set_delay(0.22)
	get_tree().create_timer(1.0).timeout.connect(root.queue_free)

# [SKILL_POPUP] Banner de SKILL: ícone (pixel) + nome flutuando acima da cabeça quando uma ATIVA dispara
# (evento com `ability`). Some sozinho. Vale no 1v1 e na guerra (mesma função). [HABILIDADES]
func _skill_popup(f: Dictionary, ability_id: String) -> void:
	if f == null or not is_instance_valid(f.get("node")):
		return
	var info = SKILL_INFO.get(ability_id, ["", ability_id.capitalize(), Color(1, 0.9, 0.6)])
	var col: Color = info[2]
	var root := Node3D.new()
	add_child(root)
	root.global_position = _head(f) + Vector3(0, 0.45, 0)
	var visuals: Array = []
	var icon_key := str(info[0])
	if icon_key != "":
		var p := "res://assets/ui/icons/%s.png" % icon_key
		if ResourceLoader.exists(p):
			var spr := Sprite3D.new()
			spr.texture = load(p)
			spr.billboard = BaseMaterial3D.BILLBOARD_ENABLED
			spr.no_depth_test = true
			spr.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
			spr.pixel_size = 0.012
			spr.position = Vector3(0, 0.36, 0)
			root.add_child(spr)
			visuals.append(spr)
	var lbl := Label3D.new()
	lbl.text = Lang.t(str(info[1]))
	lbl.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	lbl.no_depth_test = true
	lbl.modulate = col
	lbl.outline_modulate = Color(0, 0, 0, 0.95)
	lbl.outline_size = 8
	lbl.pixel_size = 0.0055
	lbl.font_size = 56
	root.add_child(lbl)
	visuals.append(lbl)
	root.scale = Vector3(0.5, 0.5, 0.5)
	var tw := create_tween().set_parallel(true)
	tw.tween_property(root, "scale", Vector3.ONE, 0.24).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	tw.tween_property(root, "global_position", root.global_position + Vector3(0, 0.7, 0), 1.4).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)
	for v in visuals:
		tw.tween_property(v, "modulate:a", 0.0, 0.5).set_delay(0.95)
	get_tree().create_timer(1.5).timeout.connect(root.queue_free)

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
	_cam_base_fov = cam.fov
	_shake_noise.frequency = 2.0   # [JUICE] ruído do tremor

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
	_cache_env_and_light()   # [JUICE] guarda Environment + luz-chave p/ reagir a crit/kill

# Acha o WorldEnvironment + a 1ª DirectionalLight (montados pelo Scenery) p/ o reactivity [JUICE].
func _cache_env_and_light() -> void:
	for c in get_children():
		if c is WorldEnvironment and _env == null:
			_env = (c as WorldEnvironment).environment
		elif c is DirectionalLight3D and _key_light == null:
			_key_light = c
	if _env:
		_env_base_glow = _env.glow_intensity if _env.glow_enabled else 0.0

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
	cam.fov = p["fov"]            # [CAM_LENTE] lente por preset; punches/kill-cam descansam neste FOV
	_cam_base_fov = p["fov"]
	cam.position = Vector3(0.0, p["height"], p["dist"])
	cam.look_at(Vector3(0.0, p["look_y"], 0.0), Vector3.UP)
	_cam_look = Vector3(0.0, p["look_y"], 0.0)   # [CAM_TRACK] reseta os alvos suavizados pro preset
	_cam_dist = p["dist"]
	_cam_lean_x = 0.0
	if cam_hint:
		cam_hint.text = "📷 Cam %d  [1/2/3]" % cam_view

# [CAM_TRACK] 1v1: a câmera segue (com LAG pesado = sensação de operador) o MEIO da dupla — aproxima ao
# colar, afasta no kite. Move só o CORPO (position) + o alvo do olhar; o shake (h/v_offset) é à parte →
# não brigam. O lag (k baixo) é a alma: NÃO deixar snappy (vira mira/enjoo). Pausa no kill-cam (_cam_locked).
func _track_camera(dt: float) -> void:
	if cam == null or _cam_locked or order.size() < 2: return
	var na = order[0].get("node")
	var nb = order[1].get("node")
	if not (na is Node3D and is_instance_valid(na) and nb is Node3D and is_instance_valid(nb)): return
	var pa: Vector3 = na.position
	var pb: Vector3 = nb.position
	var mid := (pa + pb) * 0.5
	var sep := pa.distance_to(pb)
	var p: Dictionary = CAM_PRESETS[clampi(cam_view, 1, 3) - 1]
	var want_look := Vector3(mid.x * 0.6, p["look_y"], mid.z * 0.4)        # enviesado pro centro (borda não puxa tudo)
	var want_lean: float = mid.x * 0.18
	var want_dist: float = float(p["dist"]) + clampf((sep - 3.0) * 0.35, -0.6, 1.4)
	_cam_look = _cam_look.lerp(want_look, 1.0 - exp(-3.5 * dt))
	_cam_lean_x = lerpf(_cam_lean_x, want_lean, 1.0 - exp(-2.0 * dt))
	_cam_dist = lerpf(_cam_dist, want_dist, 1.0 - exp(-1.5 * dt))
	cam.position = Vector3(_cam_lean_x, p["height"], _cam_dist)
	cam.look_at(_cam_look, Vector3.UP)

# ── [JUICE] game-feel: impacto (shake+hit-stop+fov), kill-cam slow-mo [Fable] ─────
func _exit_tree() -> void:
	Engine.time_scale = 1.0   # segurança: nunca deixar o jogo travado em câmera lenta

# Golpe NÃO-fatal: tremor + micro-freeze + punch de FOV (forte no crit, bem sutil no normal).
func _on_impact(big: bool) -> void:
	cam_shake = maxf(cam_shake, 0.55 if big else 0.34)
	_hit_stop(0.14 if big else 0.05)   # [JUICE] crit congela mais → mais "oomph"
	_fov_punch(_cam_base_fov - (8.0 if big else 1.5))

# [JUICE] Knockback: empurra o alvo pra trás na direção do golpe e volta. O sim não move busy (flinch),
# então não briga com o movimento. Forte no crit. Só horizontal (mantém y/z da base).
func _knockback(f: Dictionary, dir: Vector3, big: bool) -> void:
	var nv = f.get("node")
	if not (nv is Node3D) or not is_instance_valid(nv) or f.get("dead", false):
		return
	var node: Node3D = nv                                   # tipado → create_tween() infere Tween
	var d: Vector3 = dir.normalized() if dir.length() > 0.01 else Vector3.RIGHT
	d.y = 0.0
	var base: Vector3 = node.position
	var dist := 0.34 if big else 0.13
	var tw := node.create_tween()
	tw.tween_property(node, "position", base + d * dist, 0.05).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)
	tw.tween_property(node, "position", base, 0.20).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)

# [JUICE] Vinheta vermelha quando o HERÓI toma dano (feedback "tomei porrada"). Lazy + shader radial.
func _damage_vignette(strength: float) -> void:
	if _vignette == null or not is_instance_valid(_vignette):
		var cl := CanvasLayer.new()
		cl.layer = 50
		add_child(cl)
		_vignette = ColorRect.new()
		_vignette.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
		_vignette.mouse_filter = Control.MOUSE_FILTER_IGNORE
		var sh := Shader.new()
		sh.code = "shader_type canvas_item;\nuniform float strength = 0.0;\nvoid fragment() {\n\tvec2 uv = UV - 0.5;\n\tfloat d = length(uv) * 1.4;\n\tfloat v = smoothstep(0.35, 0.9, d);\n\tCOLOR = vec4(0.8, 0.0, 0.0, v * strength);\n}"
		var sm := ShaderMaterial.new()
		sm.shader = sh
		_vignette.material = sm
		cl.add_child(_vignette)
	var mat: ShaderMaterial = _vignette.material
	mat.set_shader_parameter("strength", strength)
	var tw := create_tween().set_ignore_time_scale(true)
	tw.tween_method(func(v): mat.set_shader_parameter("strength", v), strength, 0.0, 0.30).set_trans(Tween.TRANS_SINE)

# [JUICE] Rastro de arma (ribbon) — varre a lâmina (base→ponta) ao longo do swing e some. Só melee humano.
func _start_weapon_trail(f: Dictionary) -> void:
	if f.get("ranged", false) or f.get("is_monster", false):
		return
	var wn = f.get("weapon_node")
	if not (wn is Node3D) or not is_instance_valid(wn) or wn.get_child_count() == 0:
		return
	var holder: Node3D = wn.get_child(0)
	var mesh := MeshInstance3D.new()
	var im := ImmediateMesh.new()
	mesh.mesh = im
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.vertex_color_use_as_albedo = true
	mat.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	mat.no_depth_test = true
	mesh.material_override = mat
	add_child(mesh)
	_trails.append({"mesh": mesh, "im": im, "holder": holder, "base": [], "tip": [], "life": 0.34, "max": 0.34})

func _update_trails(dt: float) -> void:
	var i := _trails.size() - 1
	while i >= 0:
		var t: Dictionary = _trails[i]
		t["life"] -= dt
		var holder = t["holder"]
		if t["life"] <= 0.0 or not is_instance_valid(holder) or not is_instance_valid(t.get("mesh")):
			if is_instance_valid(t.get("mesh")): t["mesh"].queue_free()
			_trails.remove_at(i); i -= 1; continue
		if t["max"] - t["life"] < 0.20:                 # amostra durante o swing; depois congela e some
			t["base"].append(holder.global_position)
			t["tip"].append(holder.global_transform * Vector3(0, 0.8, 0))
			while t["base"].size() > 16:
				t["base"].pop_front(); t["tip"].pop_front()
		_rebuild_trail(t, t["life"] / t["max"])
		i -= 1

func _rebuild_trail(t: Dictionary, fade: float) -> void:
	var im: ImmediateMesh = t["im"]
	im.clear_surfaces()
	var n: int = t["base"].size()
	if n < 2: return
	im.surface_begin(Mesh.PRIMITIVE_TRIANGLE_STRIP)
	for k in n:
		var a := float(k) / float(n - 1)            # 0=cauda … 1=cabeça
		var col := Color(0.82, 0.9, 1.0, a * a * 0.55 * fade)
		im.surface_set_color(col); im.surface_add_vertex(t["tip"][k])
		im.surface_set_color(col); im.surface_add_vertex(t["base"][k])
	im.surface_end()

# Congela o tempo por `dur` REAIS (ignore_time_scale no timer); só o último restaura (geração).
func _hit_stop(dur: float, scale := 0.04) -> void:
	_hs_gen += 1
	var my := _hs_gen
	Engine.time_scale = scale
	await get_tree().create_timer(dur, true, false, true).timeout
	if my == _hs_gen:
		Engine.time_scale = 1.0

# Zoom rápido pra dentro e volta (tween em tempo REAL p/ não arrastar no slow-mo).
func _fov_punch(target: float) -> void:
	if cam == null: return
	var tw := create_tween().set_ignore_time_scale(true)
	tw.tween_property(cam, "fov", target, 0.07).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	tw.tween_property(cam, "fov", _cam_base_fov, 0.30).set_trans(Tween.TRANS_SINE)

# KILL-CAM: slow-mo + zoom forte + tremor; o ragdoll/gore voa em câmera lenta. Restaura depois.
func _kill_cam(victim: Node3D = null, dir := Vector3.RIGHT) -> void:
	if cam == null: return
	_hs_gen += 1
	var my := _hs_gen
	Engine.time_scale = 0.16
	cam_shake = 1.0
	# [CAM_KILL] reframe pra ângulo baixo 3/4 só no 1v1 (em guerra/time viraria estroboscópio de cortes).
	var reframe := victim != null and is_instance_valid(victim) and not team_mode and not war_mode
	var tw := create_tween().set_ignore_time_scale(true)
	tw.tween_property(cam, "fov", _cam_base_fov - (10.0 if reframe else 16.0), 0.22).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	if reframe:
		_cam_locked = true   # pausa o tracking enquanto o kill-cam dirige a câmera
		var vp: Vector3 = victim.position
		var d := dir; d.y = 0.0
		if d.length() < 0.01: d = Vector3.RIGHT
		d = d.normalized()
		var side := 1.0 if d.x >= 0.0 else -1.0
		var kpos := vp + d * 2.4 + Vector3(side * 1.2, 1.0, 0.0)   # baixo (y~1) + de lado, golpe atravessa o quadro
		if kpos.z < 2.2: kpos.z = 2.2                              # nunca dentro do chão/atrás do cenário
		var look := vp + Vector3(0, 1.0, 0)
		create_tween().set_ignore_time_scale(true).tween_method(
			_kill_cam_step.bind(cam.position, kpos, look), 0.0, 1.0, 0.18).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	await get_tree().create_timer(1.0, true, false, true).timeout
	if my == _hs_gen:
		Engine.time_scale = 1.0
		create_tween().set_ignore_time_scale(true).tween_property(cam, "fov", _cam_base_fov, 0.45).set_trans(Tween.TRANS_SINE)
		_cam_locked = false   # tracking reassume suave a partir da posição atual

# [CAM_KILL] passo do reframe do kill: interpola a posição e re-mira a vítima a cada passo. [t vem do tween]
func _kill_cam_step(t: float, from: Vector3, kpos: Vector3, look: Vector3) -> void:
	if not is_instance_valid(cam): return
	cam.position = from.lerp(kpos, t)
	if cam.position.distance_to(look) > 0.05:   # evita look_at degenerado
		cam.look_at(look, Vector3.UP)

# Cor do impacto pelo elemento: SUPER=dourado, RESIST=azul, normal=branco-quente.
func _impact_color(elem: String) -> Color:
	if elem == "SUPER": return Color(1.0, 0.82, 0.30)
	if elem == "RESIST": return Color(0.50, 0.70, 1.0)
	return Color(1.0, 0.86, 0.62)

# [JUICE] FAÍSCAS no ponto do golpe (one-shot, emissivo → pega o bloom; maior no crit).
func _sparks(pos: Vector3, color: Color, big: bool) -> void:
	var p := GPUParticles3D.new()
	p.one_shot = true
	p.lifetime = 0.5
	p.explosiveness = 1.0
	p.amount = 26 if big else 14
	var m := ParticleProcessMaterial.new()
	m.direction = Vector3.UP
	m.spread = 75.0
	m.initial_velocity_min = 3.0
	m.initial_velocity_max = 8.0 if big else 6.0
	m.gravity = Vector3(0, -9.0, 0)
	m.scale_min = 0.4; m.scale_max = 1.0
	p.process_material = m
	var q := QuadMesh.new(); q.size = Vector2(0.05, 0.05)
	var qm := StandardMaterial3D.new()
	qm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	qm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	qm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	qm.albedo_color = color
	qm.emission_enabled = true
	qm.emission = color
	qm.emission_energy_multiplier = 4.0
	q.material = qm
	p.draw_pass_1 = q
	add_child(p)
	p.global_position = pos
	p.emitting = true
	get_tree().create_timer(0.8).timeout.connect(p.queue_free)

# [JUICE] FLASH curto no ponto do golpe (quad emissivo que estoura e some).
func _flash_at(pos: Vector3, color: Color) -> void:
	var mi := MeshInstance3D.new()
	var q := QuadMesh.new(); q.size = Vector2(0.55, 0.55)
	mi.mesh = q
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.emission_enabled = true
	m.emission = color
	m.emission_energy_multiplier = 3.0
	m.albedo_color = Color(color.r, color.g, color.b, 0.9)
	mi.material_override = m
	add_child(mi)
	mi.global_position = pos
	var tw := create_tween().set_parallel(true)
	tw.tween_property(mi, "scale", Vector3(2.2, 2.2, 2.2), 0.13)
	tw.tween_property(m, "albedo_color:a", 0.0, 0.13)
	tw.chain().tween_callback(mi.queue_free)

# [JUICE] reactivity: glow pulsa (emissivos+sangue florescem) no KILL.
func _env_pulse() -> void:
	if _env == null or not _env.glow_enabled: return
	var tw := create_tween().set_ignore_time_scale(true)
	tw.tween_property(_env, "glow_intensity", _env_base_glow * 1.8 + 0.3, 0.12)
	tw.tween_property(_env, "glow_intensity", _env_base_glow, 0.6)

# [JUICE] reactivity: luz-chave pisca no CRIT (relâmpago de impacto).
func _light_flicker() -> void:
	if _key_light == null: return
	var base := _key_light.light_energy
	var tw := create_tween().set_ignore_time_scale(true)
	tw.tween_property(_key_light, "light_energy", base * 1.6, 0.05)
	tw.tween_property(_key_light, "light_energy", base, 0.18)

# [JUICE] flourish de vitória: luz dourada subindo + rim do vencedor vira quente + coluna de brasas.
func _victory_flourish(winner: Dictionary) -> void:
	if not is_instance_valid(winner.get("node")): return
	var wn: Node3D = winner["node"]
	var glow := OmniLight3D.new()
	glow.light_color = Color(1.0, 0.78, 0.40)
	glow.light_energy = 0.0
	glow.omni_range = 5.5
	glow.shadow_enabled = false
	add_child(glow)
	glow.global_position = wn.global_position + Vector3(0, 0.3, 0)
	var tw := create_tween().set_ignore_time_scale(true).set_parallel(true)
	tw.tween_property(glow, "light_energy", 3.0, 0.6)
	tw.tween_property(glow, "global_position", wn.global_position + Vector3(0, 2.3, 0), 1.4)
	if winner.has("rim") and is_instance_valid(winner["rim"]):
		var rim := winner["rim"] as OmniLight3D
		rim.light_color = Color(1.0, 0.85, 0.55)
		create_tween().set_ignore_time_scale(true).tween_property(rim, "light_energy", 3.2, 0.5)
	# [SKIN_RARIDADE] o "foguinho que sobe" saiu daqui — agora é a AURA DE RARIDADE (brasas) durante a luta.

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
	countdown_tex = TextureRect.new()   # [COUNTDOWN_ART] contagem em pixel art (grande, sem esticar)
	countdown_tex.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	countdown_tex.offset_top = 20
	countdown_tex.offset_bottom = 180      # faixa PEQUENA no topo (não cobre os lutadores)
	countdown_tex.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	countdown_tex.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED   # grande, preserva o aspecto
	countdown_tex.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST        # pixel art nítido ao escalar
	countdown_tex.mouse_filter = Control.MOUSE_FILTER_IGNORE
	countdown_tex.visible = false
	layer.add_child(countdown_tex)
	var cd_word := "LUTAR" if Lang.current() == "pt" else "FIGHT"   # palavra pelo idioma
	_cd_tex = [
		load("res://assets/ui/countdown/3.png"),
		load("res://assets/ui/countdown/2.png"),
		load("res://assets/ui/countdown/1.png"),
		load("res://assets/ui/countdown/%s.png" % cd_word),
	]
	cam_hint = Label.new()                    # dica de câmera no canto superior esquerdo
	cam_hint.add_theme_font_size_override("font_size", 16)
	cam_hint.position = Vector2(14, 10)
	cam_hint.text = "📷 Cam 1  [1/2/3]"
	layer.add_child(cam_hint)
	# [MIGRACAO_GODOT] voltar ao menu (Personagem) — só faz sentido quando veio do App
	var back := Button.new()
	back.text = "← Menu"
	back.set_anchors_and_offsets_preset(Control.PRESET_TOP_RIGHT)
	back.offset_left = -110; back.offset_right = -14; back.offset_top = 10; back.offset_bottom = 42
	back.pressed.connect(_leave)
	layer.add_child(back)
	# [HP_CANTO] HUD de vida no canto (estilo jogo de luta): coluna esquerda (aliados) + direita (inimigos).
	hud_left = VBoxContainer.new()
	hud_left.set_anchors_and_offsets_preset(Control.PRESET_TOP_LEFT)
	hud_left.position = Vector2(14, 44)
	hud_left.add_theme_constant_override("separation", 6)
	hud_left.mouse_filter = Control.MOUSE_FILTER_IGNORE
	layer.add_child(hud_left)
	hud_right = VBoxContainer.new()
	hud_right.set_anchors_and_offsets_preset(Control.PRESET_TOP_LEFT)
	hud_right.position = Vector2(400, 52)   # _update_hud recoloca no canto direito (depende da largura)
	hud_right.add_theme_constant_override("separation", 6)
	hud_right.mouse_filter = Control.MOUSE_FILTER_IGNORE
	layer.add_child(hud_right)

# ── [HP_CANTO] HUD de vida no canto (estilo jogo de luta) ──────────────────────────
# Lado do lutador: <0 = esquerda (aliado/herói) · >0 = direita (inimigo).
func _fighter_side(f: Dictionary) -> int:
	return int(f.get("team", f.get("side", -1)))

# Todo frame: reconstrói as barras se o elenco mudou (spawn/morte) + atualiza valores + recoloca a coluna direita.
func _update_hud() -> void:
	if hud_left == null or hud_right == null:
		return
	var sig := _hud_signature()
	if sig != hud_sig:
		hud_sig = sig
		_rebuild_hp_hud()
	for f in order:
		if f.get("hud_bar") != null and is_instance_valid(f["hud_bar"]):
			var bar: ProgressBar = f["hud_bar"]
			bar.max_value = maxi(1, int(f["maxhp"]))
			bar.value = clampf(float(f.get("shown_hp", f["hp"])), 0.0, bar.max_value)
			if f.get("hud_num") != null and is_instance_valid(f["hud_num"]):
				(f["hud_num"] as Label).text = "%d/%d" % [int(round(float(f.get("shown_hp", f["hp"])))), int(f["maxhp"])]
	# recoloca a coluna direita encostada na borda direita da tela
	var vpw := get_viewport().get_visible_rect().size.x
	hud_right.position.x = vpw - hud_right.size.x - 14.0

# Assinatura do elenco visível (nome + vivo/morto) → só reconstrói quando muda.
func _hud_signature() -> String:
	var team := team_mode or war_mode
	var s := ""
	for f in order:
		if team and f.get("dead", false): continue
		s += str(f["name"]) + ("D" if f.get("dead", false) else "L") + ";"
	return s

# (Re)constrói as barras a partir de `order`. 1v1 mostra os 2 (mesmo mortos); time/guerra só os vivos.
func _rebuild_hp_hud() -> void:
	for c in hud_left.get_children(): c.queue_free()
	for c in hud_right.get_children(): c.queue_free()
	for f in order:
		f["hud_bar"] = null
		f["hud_num"] = null
	var team := team_mode or war_mode
	var w := 200.0 if team else 300.0
	for f in order:
		if team and f.get("dead", false):
			continue
		var right := _fighter_side(f) > 0
		var widget := _make_hp_widget(f, right, w)
		if right: hud_right.add_child(widget)
		else: hud_left.add_child(widget)

# Widget de uma barra: moldura pixel (9-slice) + nome + barra vermelha + número. right=inimigo (espelha).
func _make_hp_widget(f: Dictionary, right: bool, w: float) -> Control:
	var pc := PanelContainer.new()
	pc.custom_minimum_size = Vector2(w, 0)
	pc.mouse_filter = Control.MOUSE_FILTER_IGNORE
	pc.add_theme_stylebox_override("panel", _hp_frame_box())
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 1)
	vb.mouse_filter = Control.MOUSE_FILTER_IGNORE
	pc.add_child(vb)
	var nm := Label.new()
	nm.text = str(f["name"]) + ("  🏹" if f.get("ranged", false) else "")
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", Color(0.97, 0.42, 0.40) if right else Color(0.46, 0.92, 0.5))  # inimigo vermelho / aliado verde
	nm.add_theme_color_override("font_outline_color", Color(0, 0, 0))
	nm.add_theme_constant_override("outline_size", 4)
	nm.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	nm.mouse_filter = Control.MOUSE_FILTER_IGNORE
	vb.add_child(nm)
	var bar := ProgressBar.new()
	bar.custom_minimum_size = Vector2(0, 18)
	bar.min_value = 0
	bar.max_value = maxi(1, int(f["maxhp"]))
	bar.value = clampf(float(f.get("shown_hp", f["hp"])), 0.0, bar.max_value)
	bar.show_percentage = false
	bar.fill_mode = ProgressBar.FILL_END_TO_BEGIN if right else ProgressBar.FILL_BEGIN_TO_END
	bar.add_theme_stylebox_override("background", _hp_bar_bg())
	bar.add_theme_stylebox_override("fill", _hp_bar_fill())
	bar.mouse_filter = Control.MOUSE_FILTER_IGNORE
	vb.add_child(bar)
	var num := Label.new()   # número de HP sobre a barra
	num.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	num.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	num.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	num.add_theme_font_size_override("font_size", 12)
	num.add_theme_color_override("font_color", Color(1, 1, 1))
	num.add_theme_color_override("font_outline_color", Color(0, 0, 0))
	num.add_theme_constant_override("outline_size", 4)
	num.mouse_filter = Control.MOUSE_FILTER_IGNORE
	bar.add_child(num)
	f["hud_bar"] = bar
	f["hud_num"] = num
	return pc

# Moldura pixel medieval (9-slice). Fallback: caixa madeira/ouro se o PNG não foi importado.
func _hp_frame_box() -> StyleBox:
	var p := "res://assets/ui/hp_frame.png"
	if ResourceLoader.exists(p):
		var sb := StyleBoxTexture.new()
		sb.texture = load(p)
		sb.set_texture_margin_all(28)   # 9-slice: a borda ornamentada do frame
		sb.set_content_margin_all(15)    # padding interno (a barra fica dentro do frame)
		return sb
	var fb := StyleBoxFlat.new()
	fb.bg_color = Color(0.09, 0.07, 0.05, 0.92)
	fb.set_border_width_all(2); fb.border_color = Color(0.78, 0.65, 0.36)
	fb.set_corner_radius_all(3); fb.set_content_margin_all(8)
	return fb

func _hp_bar_bg() -> StyleBox:
	var s := StyleBoxFlat.new()
	s.bg_color = Color(0.06, 0.03, 0.03, 0.95)
	s.set_border_width_all(1); s.border_color = Color(0.20, 0.10, 0.08)
	s.set_corner_radius_all(2)
	return s

func _hp_bar_fill() -> StyleBox:
	var s := StyleBoxFlat.new()
	s.bg_color = Color(0.80, 0.16, 0.16)   # vermelho-sangue (medieval)
	s.set_corner_radius_all(2)
	return s

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

# [TEAM_MOCK] Eventos de uma batalha 3v3 (aliados batem mais forte → vencem). 6 spawns + troca de
# golpes por NOME (o motor resolve por actor/target). Gera até um time cair. Números são de teste.
# [TEAM_MOCK] Só os 6 spawns (p/ _ready não abortar). O COMBATE 3v3 é simulado AO VIVO em
# _tick_team (cada lutador roda seu loop em paralelo → 3 duelos ao mesmo tempo), não por este stream.
func _mock_team_events() -> Array:
	var ev: Array = []
	for nm in ["Você", "Aliado", "Recruta", "Bandido", "Saqueador", "Capanga"]:
		ev.append({"type": "spawn", "actor": nm, "target": "", "damage": 0, "targetHp": 100, "targetMaxHp": 100, "element": "", "hitZone": ""})
	return ev

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
