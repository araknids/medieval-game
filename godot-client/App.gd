extends Control
# ── Roteador do cliente Godot ────────────────────────────────────────────────────
# Sem token (Api) → Login; com token → Hub (menu). O Hub abre as telas por NOME
# (load sob demanda → tela com erro de parse não derruba o app). [MIGRACAO_GODOT]
# Telas emitem: go_back (→ Hub), open_screen(name), go_battle, go_inventory, logout, logged_in.

const HUB := preload("res://ui/Hub.tscn")
const LOGIN := preload("res://ui/Login.tscn")

var current: Control

func _ready() -> void:
	_route()

# Esc / B do controle = voltar pro Hub (só de uma tela; tela tem go_back, Hub/Login não). [Fable]
func _unhandled_input(event: InputEvent) -> void:
	if event.is_action_pressed("ui_cancel") and current and current.has_signal("go_back"):
		_show(HUB)
		get_viewport().set_input_as_handled()

func _route() -> void:
	if Api.token == "":
		_show(LOGIN)
	else:
		_show(HUB)

func _show(scene: PackedScene) -> void:
	if current and is_instance_valid(current):
		current.queue_free()
	current = scene.instantiate()
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
		c.logout.connect(func() -> void: Api.token = ""; _route())
	if c.has_signal("go_battle"):
		c.go_battle.connect(func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	if c.has_signal("go_back"):
		c.go_back.connect(func() -> void: _show(HUB))
	if c.has_signal("go_inventory"):
		c.go_inventory.connect(func() -> void: _open("Inventory"))
	if c.has_signal("open_screen"):
		c.open_screen.connect(_open)
