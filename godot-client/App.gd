extends Control
# ── Roteador do cliente Godot ────────────────────────────────────────────────────
# Sem token (Api) → Login; com token → Hub (menu). O Hub abre as telas por NOME
# (load sob demanda → tela com erro de parse não derruba o app). [MIGRACAO_GODOT]
# Telas emitem: go_back (→ Hub), open_screen(name), go_battle, go_inventory, logout, logged_in.

const LOGIN := preload("res://ui/Login.tscn")
const MenuFx := preload("res://ui/MenuFx.gd")   # fundo 3D do castelo (persistente, atrás de tudo)
# BattleReplay é carregado SOB DEMANDA (load) em _play_battle — NUNCA preload: um erro de parse no
# replay (arquivo grande) não pode derrubar o app/login. Mesmo princípio do _open() das telas.

var current: Control
var _battle: Node = null            # replay em andamento (overlay sobre a tela)
var _battle_screen: Control = null  # tela que pediu a batalha (volta pra ela no fim)
var _castle_bg: SubViewportContainer = null   # castelo + duelo 3D ÚNICO, atrás de toda tela

func _ready() -> void:
	get_window().min_size = Vector2i(1024, 576)   # trava o tamanho mínimo da janela (UI não quebra abaixo disso)
	_setup_emoji_font()
	_castle_bg = MenuFx.new().bg_3d(self, "castle")   # 1 fundo 3D p/ TODAS as telas (montado 1x; persiste)
	_route()

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
	if _battle != null and is_instance_valid(_battle):
		Engine.time_scale = 1.0
		_end_battle()
		get_viewport().set_input_as_handled()

func _route() -> void:
	if Api.token == "":
		_show(LOGIN)
	else:
		_show_node(Shell.new())   # logado → shell persistente (topbar + nav + conteúdo) [PLANO_UI_SHELL_GODOT]

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
	if _castle_bg:
		_castle_bg.visible = false   # esconde o castelo: o replay tem o 3D próprio dele
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
	if _castle_bg:
		_castle_bg.visible = true    # restaura o castelo atrás da tela
	var s := _battle_screen
	_battle_screen = null
	if is_instance_valid(s):
		s.visible = true
		if s.has_method("_on_battle_over"):
			s._on_battle_over()
