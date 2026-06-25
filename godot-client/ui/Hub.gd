extends Control
# ── HUB / Menu principal (estilo grimdark/Diablo) ────────────────────────────────
# Fundo 3D + linha de AÇÕES PRIMÁRIAS (Lutar/Personagem/Inventário) + SEÇÕES rotuladas
# (Aventura/Batalha/Comércio/Personagem/Social) — divide os 18 botões por categoria, como a web.
# Abre telas por nome (open_screen). [Fable][MIGRACAO_GODOT]

signal open_screen(screen)
signal go_battle
signal logout

const MenuFx := preload("res://ui/MenuFx.gd")
const Icons := preload("res://ui/Icons.gd")

# [seção, [[nome_da_tela, rótulo], ...]]
const SECTIONS := [
	["Aventura",   [["World", "🌍 Mundo"], ["Work", "💼 Trabalho"], ["Temple", "⛪ Templo"]]],
	["Batalha",    [["Tower", "🏰 Torre"], ["Arena", "⚔️ Arena"]]],
	["Comércio",   [["Shop", "🛒 Loja"], ["Forge", "🔨 Forja"], ["Auction", "💰 Leilão"],
					["Stash", "📦 Baú"], ["Tavern", "🍺 Taverna"], ["Vip", "💎 VIP"]]],
	["Personagem", [["Abilities", "✨ Habilidades"], ["Achievements", "🏆 Conquistas"]]],
	["Social",     [["Guild", "🛡 Guilda"], ["Territory", "🗺 Território"], ["Mail", "✉ Correio"], ["Daily", "🎁 Diário"]]],
]

var _fx: MenuFx

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	_fx = MenuFx.new()
	_fx.bg_3d(self, "castle")
	var scroll := ScrollContainer.new()
	scroll.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	scroll.follow_focus = true   # controle: o scroll acompanha o foco
	add_child(scroll)
	var margin := MarginContainer.new()
	margin.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for side in ["left", "right", "top", "bottom"]:
		margin.add_theme_constant_override("margin_" + side, 26)
	scroll.add_child(margin)
	# coluna máx 920px centrada (mesma regra do UiKit.scaffold → mesma "geração" das telas)
	var cap := func() -> void:
		var extra: int = maxi(0, int((size.x - 920) / 2.0))
		margin.add_theme_constant_override("margin_left", 26 + extra)
		margin.add_theme_constant_override("margin_right", 26 + extra)
	resized.connect(cap)
	cap.call()
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 10)
	box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	margin.add_child(box)
	box.add_child(_fx.title("CROWN OF ARAVOK", 40))
	# ── ações primárias: LUTAR grande + Personagem/Inventário ──
	var fight := _fx.button("⚔   LUTAR")
	if Icons.set_icon(fight, "arena"):   # espadas cruzadas no lugar do emoji
		fight.text = "LUTAR"
	fight.custom_minimum_size = Vector2(0, 56)
	fight.add_theme_font_size_override("font_size", 22)
	fight.pressed.connect(func() -> void: go_battle.emit())
	box.add_child(fight)
	var quick := HBoxContainer.new()
	quick.add_theme_constant_override("separation", 10)
	box.add_child(quick)
	quick.add_child(_screen_button(["Character", "👤 Personagem"], 50))
	quick.add_child(_screen_button(["Inventory", "🎒 Inventário"], 50))
	# ── seções ──
	for section in SECTIONS:
		box.add_child(_spacer(4))
		box.add_child(_section_header(str(section[0])))
		var grid := GridContainer.new()
		grid.columns = 3
		grid.add_theme_constant_override("h_separation", 10)
		grid.add_theme_constant_override("v_separation", 10)
		grid.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		box.add_child(grid)
		for entry in section[1]:
			grid.add_child(_screen_button(entry, 46))
	box.add_child(_spacer(12))
	var out := _fx.button("Sair")
	out.pressed.connect(func() -> void: logout.emit())
	box.add_child(out)
	fight.call_deferred("grab_focus")   # foco inicial = ação principal (menu vivo no controle)

func _screen_button(entry: Array, h: int) -> Button:
	var b := _fx.button("")
	Icons.label_button(b, str(entry[0]).to_lower(), str(entry[1]))  # ícone + texto (fallback no emoji)
	b.custom_minimum_size = Vector2(150, h)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var target: String = str(entry[0])
	b.pressed.connect(func() -> void: open_screen.emit(target))
	return b

# Header de seção: rótulo dourado em maiúsculas + régua horizontal.
func _section_header(text: String) -> Control:
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	var lbl := Label.new()
	lbl.text = text.to_upper()
	lbl.add_theme_font_size_override("font_size", 15)
	lbl.add_theme_color_override("font_color", Color(0.78, 0.65, 0.36))
	row.add_child(lbl)
	var rule := ColorRect.new()
	rule.color = Color(0.78, 0.65, 0.36, 0.35)
	rule.custom_minimum_size = Vector2(0, 1)
	rule.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	rule.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(rule)
	return row

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
