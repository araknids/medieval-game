extends Control
# ── Roteador do cliente Godot ────────────────────────────────────────────────────
# Sem token (Api) → Login; com token → Hub (menu). O Hub abre as telas por NOME
# (load sob demanda → tela com erro de parse não derruba o app). [MIGRACAO_GODOT]
# Telas emitem: go_back (→ Hub), open_screen(name), go_battle, go_inventory, logout, logged_in.

const LOGIN := preload("res://ui/Login.tscn")
const MenuFx := preload("res://ui/MenuFx.gd")   # fundo 3D do menu (persistente, atrás de tudo)
# Mapas de fundo do menu: só os FECHADOS (câmera olha pra dentro → enquadra bem o duelo). [MENU_FUNDO]
const MENU_MAPS := ["castle", "arena", "city", "dungeon"]
# BattleReplay é carregado SOB DEMANDA (load) em _play_battle — NUNCA preload: um erro de parse no
# replay (arquivo grande) não pode derrubar o app/login. Mesmo princípio do _open() das telas.

var current: Control
var _battle: Node = null            # replay em andamento (overlay sobre a tela)
var _battle_screen: Control = null  # tela que pediu a batalha (volta pra ela no fim)
var _menu_bg: SubViewportContainer = null   # fundo 3D ÚNICO (cenário + duelo), atrás de toda tela
var _gear_layer: CanvasLayer = null         # engrenagem ⚙ flutuante (abre Settings de qualquer tela) [I18N]
var _settings_layer: CanvasLayer = null     # overlay de Settings aberto

func _ready() -> void:
	# Tooltip INSTANTÂNEO em TODO o jogo (padrão do Godot é 0.5s parado). Global, lido pelo
	# Viewport a cada hover → set em runtime vale pra todos os controles. [HOVER_INSTANT]
	ProjectSettings.set_setting("gui/timers/tooltip_delay_sec", 0.0)
	Lang.apply_saved()   # registra PT/EN + aplica o idioma salvo ANTES de montar qualquer tela [I18N]
	get_window().min_size = Vector2i(1024, 576)   # trava o tamanho mínimo da janela (UI não quebra abaixo disso)
	_setup_emoji_font()
	randomize()   # mapa de fundo + lutadores diferentes a cada abertura do jogo [MENU_FUNDO]
	var scenario: String = MENU_MAPS[randi() % MENU_MAPS.size()]
	_menu_bg = MenuFx.new().bg_3d(self, scenario)   # 1 fundo 3D p/ TODAS as telas (montado 1x; persiste)
	UiKit.duel_refresh_sink = _refresh_duel          # trocar de equip → o duelo do fundo re-veste com seu gear novo
	_route()
	_check_version()   # [DISTRIB_UPDATE] confere a versão contra o servidor (bloqueia cliente velho demais)
	# [UI] engrenagem flutuante REMOVIDA — Settings já vive na nav do Shell (Shell.gd _nav_item "Settings").

# [DISTRIB_UPDATE] Trava de versão: bate CLIENT_VERSION (project.godot) contra o /api/server-info.
# < minClientVersion → overlay BLOQUEANTE (a API mudou, o cliente velho quebraria). Entre min e latest →
# aviso suave dispensável. Falha de rede / 0.0.0 → não faz nada (não trava o jogo offline/dev).
func _check_version() -> void:
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var r = await api.server_info()
	if not (r.get("ok") and r.get("json") is Dictionary):
		return
	var j: Dictionary = r["json"]
	var mine := str(ProjectSettings.get_setting("application/config/version", "0.0.0"))
	var minv := str(j.get("minClientVersion", "0.0.0"))
	var latest := str(j.get("latestClientVersion", "0.0.0"))
	var url := str(j.get("clientDownloadUrl", ""))
	if _ver_cmp(mine, minv) < 0:
		_show_update_gate(true, mine, latest, url)          # obrigatório (bloqueia)
	elif _ver_cmp(mine, latest) < 0:
		_show_update_gate(false, mine, latest, url)         # opcional (avisa)

# Compara versões "x.y.z" → -1 / 0 / 1. Tolerante a campos a menos e a sufixos não-numéricos.
func _ver_cmp(a: String, b: String) -> int:
	var pa := a.split("."); var pb := b.split(".")
	for i in range(maxi(pa.size(), pb.size())):
		var na := int(pa[i]) if i < pa.size() else 0
		var nb := int(pb[i]) if i < pb.size() else 0
		if na != nb:
			return -1 if na < nb else 1
	return 0

func _show_update_gate(required: bool, mine: String, latest: String, url: String) -> void:
	var layer := CanvasLayer.new(); layer.layer = 200
	add_child(layer)
	if required:
		var block := ColorRect.new()
		block.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
		block.color = Color(0, 0, 0, 0.82)
		block.mouse_filter = Control.MOUSE_FILTER_STOP   # trava a tela embaixo
		layer.add_child(block)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	layer.add_child(center)
	var res := UiKit.card(UiKit.GOLD if required else UiKit.GOLD_SOFT)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	vb.add_theme_constant_override("separation", 10)
	center.add_child(panel)
	var title := UiKit.body(Lang.t("Atualização necessária") if required else Lang.t("Há uma nova versão"))
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(title)
	var msg := UiKit.dim(Lang.t("Sua versão: %s · disponível: %s") % [mine, latest])
	msg.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(msg)
	if url != "":
		vb.add_child(UiKit.action(Lang.t("Baixar atualização"), func() -> void: OS.shell_open(url)))
	if not required:
		vb.add_child(UiKit.action(Lang.t("Continuar mesmo assim"), func() -> void: layer.queue_free()))

# Engrenagem flutuante no canto: abre Settings (idioma PT/EN) de qualquer tela, sem depender da nav.
func _add_settings_gear() -> void:
	var tex = load("res://assets/ui/icons/settings.png")
	if tex == null:
		return
	_gear_layer = CanvasLayer.new()
	_gear_layer.layer = 100
	add_child(_gear_layer)
	var btn := TextureButton.new()
	btn.texture_normal = tex
	btn.ignore_texture_size = true
	btn.stretch_mode = TextureButton.STRETCH_KEEP_ASPECT_CENTERED
	btn.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_RIGHT)
	btn.offset_left = -54; btn.offset_top = -54; btn.offset_right = -12; btn.offset_bottom = -12
	btn.modulate = Color(1, 1, 1, 0.72)
	btn.tooltip_text = "Configurações / Settings"
	btn.mouse_entered.connect(func() -> void: btn.modulate = Color(1, 1, 1, 1))
	btn.mouse_exited.connect(func() -> void: btn.modulate = Color(1, 1, 1, 0.72))
	btn.pressed.connect(_open_settings)
	_gear_layer.add_child(btn)

func _open_settings() -> void:
	if _settings_layer != null and is_instance_valid(_settings_layer):
		return
	var scene = load("res://ui/Settings.tscn")
	if scene == null:
		return
	_settings_layer = CanvasLayer.new()
	_settings_layer.layer = 110
	add_child(_settings_layer)
	var scr = scene.instantiate()
	if scr.has_signal("go_back"):
		scr.go_back.connect(_close_settings)
	if scr.has_signal("logout"):                 # [LOGOUT] Settings em overlay também desconecta
		scr.logout.connect(func() -> void:
			_close_settings()
			Api.token = ""
			ConfigFile.new().save("user://session.cfg")
			_route())
	_settings_layer.add_child(scr)

func _close_settings() -> void:
	if _settings_layer != null and is_instance_valid(_settings_layer):
		_settings_layer.queue_free()
	_settings_layer = null

# Registra a Noto Emoji (mono, OFL) como fallback da fonte padrão → os ícones (emoji) passam a
# renderizar em TODO o app (Open Sans não tem emoji). Mono = herda a cor do label (combina com o
# tema). [PADRAO_UI_GODOT] Direção: Fable.
func _setup_emoji_font() -> void:
	var emoji = load("res://assets/fonts/NotoEmoji-VariableFont_wght.ttf")
	if emoji is Font:
		ThemeDB.fallback_font.fallbacks = [emoji]

# Esc / B do controle: durante a batalha encerra o replay; senão volta pro Hub (de uma tela). [Fable]
func _unhandled_input(event: InputEvent) -> void:
	if not event.is_action_pressed("ui_cancel"):
		return
	if _settings_layer != null and is_instance_valid(_settings_layer):   # Esc fecha o Settings primeiro
		_close_settings()
		get_viewport().set_input_as_handled()
		return
	if _battle != null and is_instance_valid(_battle):
		Engine.time_scale = 1.0
		_end_battle()
		get_viewport().set_input_as_handled()

func _route() -> void:
	_refresh_duel()   # atualiza os lutadores do fundo conforme o login (você vs aleatório) [MENU_FUNDO]
	if Api.token == "":
		_show(LOGIN)
	else:
		_show_node(Shell.new())   # logado → shell persistente (topbar + nav + conteúdo) [PLANO_UI_SHELL_GODOT]

# Pede ao duelo do fundo p/ remontar os lutadores (async, fire-and-forget). Logado → seu personagem
# de um lado; deslogado → dois aleatórios. Chamado no boot e a cada login/logout. [MENU_FUNDO]
func _refresh_duel() -> void:
	if _menu_bg == null or not _menu_bg.has_meta("menu_duel"):
		return
	var duel = _menu_bg.get_meta("menu_duel")
	if is_instance_valid(duel) and duel.has_method("setup"):
		duel.setup()

func _show(scene: PackedScene) -> void:
	_show_node(scene.instantiate())

func _show_node(node: Control) -> void:
	if current and is_instance_valid(current):
		current.queue_free()
	current = node
	add_child(current)
	_wire(current)

# Abre uma tela por NOME (res://ui/<Nome>.tscn). load() em runtime: tela quebrada erra só aqui.
func _open(name: String) -> void:
	var scene = load("res://ui/%s.tscn" % name)
	if scene == null:
		push_warning("tela não encontrada: %s" % name)
		return
	_show(scene)

func _wire(c: Control) -> void:
	if c.has_signal("logged_in"):
		c.logged_in.connect(_route)
	if c.has_signal("logout"):
		c.logout.connect(func() -> void:
			Api.token = ""
			ConfigFile.new().save("user://session.cfg")   # limpa o auto-login salvo (senão re-logaria)
			_route())
	if c.has_signal("request_battle"):                       # Shell repassa o pedido de replay 3D (arena/zona/torre)
		c.request_battle.connect(_play_battle.bind(c))

# Abre o replay 3D POR CIMA da tela (overlay): esconde a tela, mostra o 3D; no fim restaura. [MIGRACAO_GODOT]
# data = {events, scene, won, enemy} — a luta JÁ foi resolvida pela tela; o replay só anima.
func _play_battle(data: Dictionary, screen: Control) -> void:
	if _battle != null and is_instance_valid(_battle):
		return                                                # já tem uma rolando
	_battle_screen = screen
	var scene = load("res://BattleReplay.tscn")   # sob demanda: erro no replay não derruba o login
	if scene == null:
		push_warning("BattleReplay.tscn não carregou — pulando o replay")
		_battle_screen = null
		return
	if is_instance_valid(screen):
		screen.visible = false
	if _menu_bg:
		_menu_bg.visible = false   # esconde o fundo do menu: o replay tem o 3D próprio dele
	if _gear_layer:
		_gear_layer.visible = false
	var br = scene.instantiate()
	br.set("external_battle", data)
	br.set("force_mock", false)
	add_child(br)
	_battle = br
	if br.has_signal("finished"):
		br.connect("finished", _end_battle)

# Fecha o replay, restaura a tela e deixa ela tratar o resultado (recompensa + refresh).
func _end_battle() -> void:
	if _battle != null and is_instance_valid(_battle):
		_battle.queue_free()
	_battle = null
	if _menu_bg:
		_menu_bg.visible = true    # restaura o fundo do menu atrás da tela
	if _gear_layer:
		_gear_layer.visible = true
	var s := _battle_screen
	_battle_screen = null
	if is_instance_valid(s):
		s.visible = true
		if s.has_method("_on_battle_over"):
			s._on_battle_over()
