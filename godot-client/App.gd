extends Control
# ── Roteador do cliente Godot ────────────────────────────────────────────────────
# Troca a tela atual. Sem token (Api) → Login; com token → Personagem. [MIGRACAO_GODOT]
# Telas emitem sinais (logged_in / go_battle / logout) que o App escuta. Plano: docs/PLANO_MIGRACAO_GODOT.md

const LOGIN := preload("res://ui/Login.tscn")
const CHARACTER := preload("res://ui/Character.tscn")
const INVENTORY := preload("res://ui/Inventory.tscn")

var current: Control

func _ready() -> void:
	_route()

func _route() -> void:
	if Api.token == "":
		_show(LOGIN)
	else:
		_show(CHARACTER)

func _show(scene: PackedScene) -> void:
	if current and is_instance_valid(current):
		current.queue_free()
	current = scene.instantiate()
	add_child(current)
	if current.has_signal("logged_in"):
		current.logged_in.connect(_route)
	if current.has_signal("logout"):
		current.logout.connect(func() -> void: Api.token = ""; _route())
	if current.has_signal("go_battle"):
		current.go_battle.connect(func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	if current.has_signal("go_inventory"):
		current.go_inventory.connect(func() -> void: _show(INVENTORY))
	if current.has_signal("go_back"):
		current.go_back.connect(func() -> void: _show(CHARACTER))
