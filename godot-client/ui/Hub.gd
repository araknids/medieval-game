extends Control
# ── HUB / Menu principal ──────────────────────────────────────────────────────────
# Grade de botões → abre cada tela por nome (open_screen). + Lutar (go_battle) e Sair. [MIGRACAO_GODOT]

signal open_screen(screen)
signal go_battle
signal logout

# [nome_da_tela (= res://ui/<nome>.tscn), rótulo no botão]
const MENU := [
	["Character", "👤 Personagem"], ["Inventory", "🎒 Inventário"], ["Shop", "🛒 Loja"],
	["World", "🌍 Mundo"], ["Tower", "🏰 Torre"], ["Arena", "⚔️ Arena"],
	["Forge", "🔨 Forja"], ["Temple", "⛪ Templo"], ["Work", "💼 Trabalho"],
	["Tavern", "🍺 Taverna"], ["Guild", "🛡 Guilda"], ["Auction", "💰 Leilão"],
	["Stash", "📦 Baú"], ["Mail", "✉ Correio"], ["Achievements", "🏆 Conquistas"],
	["Abilities", "✨ Habilidades"], ["Daily", "🎁 Diário"], ["Vip", "💎 VIP"],
]

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var bg := ColorRect.new()
	bg.color = Color(0.08, 0.07, 0.10)
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	var scroll := ScrollContainer.new()
	scroll.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(scroll)
	var margin := MarginContainer.new()
	margin.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for side in ["left", "right", "top", "bottom"]:
		margin.add_theme_constant_override("margin_" + side, 22)
	scroll.add_child(margin)
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 12)
	box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	margin.add_child(box)
	var ttl := Label.new()
	ttl.text = "⚔ Medieval"
	ttl.add_theme_font_size_override("font_size", 34)
	box.add_child(ttl)
	# botão grande de Lutar
	var fight := Button.new()
	fight.text = "⚔  LUTAR"
	fight.custom_minimum_size = Vector2(0, 52)
	fight.pressed.connect(func() -> void: go_battle.emit())
	box.add_child(fight)
	# grade de telas
	var grid := GridContainer.new()
	grid.columns = 3
	grid.add_theme_constant_override("h_separation", 8)
	grid.add_theme_constant_override("v_separation", 8)
	grid.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	box.add_child(grid)
	for entry in MENU:
		var b := Button.new()
		b.text = str(entry[1])
		b.custom_minimum_size = Vector2(150, 44)
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		var target: String = str(entry[0])
		b.pressed.connect(func() -> void: open_screen.emit(target))
		grid.add_child(b)
	# sair
	box.add_child(_spacer(8))
	var out := Button.new()
	out.text = "Sair"
	out.pressed.connect(func() -> void: logout.emit())
	box.add_child(out)

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
