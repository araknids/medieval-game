extends Control
class_name Shell
# ── SHELL persistente: TopBar (busto+identidade+HP/estamina+moedas) + Nav lateral + ContentHost ──
# Substitui a navegação "tela cheia que troca tudo". Hospeda as 21 telas no ContentHost; o warrior
# é buscado 1x aqui e o topbar se atualiza (telas embedded chamam UiKit.set_wallet→Shell.update_topbar).
# Desenho: docs/PLANO_UI_SHELL_GODOT.md [PLANO_UI_SHELL_GODOT]

signal request_battle(data)   # tela pediu replay 3D → App._play_battle (esconde o shell)
signal logout

const Icons := preload("res://ui/Icons.gd")   # BustView é global (class_name), não precisa preload

# Tooltips (hover) dos itens da topbar — explicam o que é cada coisa.
const COIN_TIPS := {
	"gold": "Ouro — moeda de maior valor (1 ouro = 100 prata = 10.000 bronze)",
	"silver": "Prata — 1 prata = 100 bronze",
	"bronze": "Bronze — moeda básica (recompensas, vendas)",
	"soulstone": "SoulStone — moeda premium (VIP, cura instantânea)",
}
const ELEM_ICONS := {"FIRE": "🔥", "WATER": "💧", "EARTH": "🪨", "AIR": "💨"}

# Nav em árvore: [seção, [[tela, rótulo], ...]] — o ícone vem de "<tela em minúsculo>.png".
const SECTIONS := [
	["Aventura",   [["World", "Mundo"], ["Work", "Trabalho"], ["Temple", "Templo"]]],
	["Batalha",    [["Tower", "Torre"], ["Arena", "Arena"], ["Territory", "Território"]]],
	["Comércio",   [["Shop", "Loja"], ["Forge", "Forja"], ["Auction", "Leilão"], ["Stash", "Baú"], ["Tavern", "Taverna"], ["Vip", "VIP"]]],
	["Personagem", [["Character", "Personagem"], ["Inventory", "Inventário"], ["Abilities", "Habilidades"], ["Achievements", "Conquistas"]]],
	["Social",     [["Guild", "Guilda"], ["Mail", "Correio"], ["Daily", "Diário"]]],
]

static var current = null   # ref do shell ativo (untyped p/ evitar edge-case de static var da própria classe)

var warrior: Dictionary = {}
var content_host: Control
var active_screen: Control = null
var active_name := ""
# topbar
var _bust: BustView
var _name_lbl: Label
var _title_lbl: Label
var _sub_lbl: Label
var _xp_bar: ProgressBar
var _hp_bar: ProgressBar
var _stam_bar: ProgressBar
var _stam_lbl: Label
var _coins: Dictionary = {}     # key -> Label
var _buffs_box: HBoxContainer    # badges dos buffs ativos (templo/vip/refeição/encanto/novato/taverna)
var _nav_buttons: Dictionary = {}   # nome da tela -> Button (destaque do ativo)

func _ready() -> void:
	current = self
	UiKit.topbar_sink = update_topbar   # telas embedded mandam o warrior pro topbar via set_wallet
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	UiKit.bg(self, UiKit.TINT_DEFAULT)
	var root := VBoxContainer.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	root.add_theme_constant_override("separation", 0)
	add_child(root)
	root.add_child(_build_topbar())
	var body := HBoxContainer.new()
	body.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body.add_theme_constant_override("separation", 0)
	root.add_child(body)
	body.add_child(_build_nav())
	content_host = Control.new()
	content_host.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content_host.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body.add_child(content_host)
	await refresh_warrior()
	_show_dashboard()

func _exit_tree() -> void:
	if current == self:
		current = null
	if UiKit.topbar_sink.is_valid() and UiKit.topbar_sink.get_object() == self:
		UiKit.topbar_sink = Callable()

# ── TopBar ─────────────────────────────────────────────────────────────────────────
func _build_topbar() -> Control:
	var pc := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.08, 0.075, 0.09, 0.96)
	sb.border_color = Color(0.40, 0.32, 0.20)
	sb.border_width_bottom = 2
	sb.set_content_margin_all(8)
	pc.add_theme_stylebox_override("panel", sb)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 14)
	pc.add_child(row)
	# busto num quadro de pedra
	var frame := PanelContainer.new()
	var fb := StyleBoxFlat.new()
	fb.bg_color = Color(0.05, 0.045, 0.06)
	fb.border_color = Color(0.45, 0.36, 0.22); fb.set_border_width_all(1)
	fb.set_corner_radius_all(3)
	frame.add_theme_stylebox_override("panel", fb)
	frame.custom_minimum_size = Vector2(60, 60)
	_bust = BustView.new()
	_bust.custom_minimum_size = Vector2(56, 56)
	frame.add_child(_bust)
	row.add_child(frame)
	# identidade: nome + título · classe·nível · XP
	var idv := VBoxContainer.new()
	idv.add_theme_constant_override("separation", 1)
	idv.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(idv)
	var nameline := HBoxContainer.new(); nameline.add_theme_constant_override("separation", 8)
	_name_lbl = Label.new()
	_name_lbl.add_theme_font_size_override("font_size", 18)
	_name_lbl.add_theme_color_override("font_color", UiKit.GOLD)
	nameline.add_child(_name_lbl)
	_title_lbl = Label.new()
	_title_lbl.add_theme_font_size_override("font_size", 12)
	_title_lbl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	_title_lbl.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	nameline.add_child(_title_lbl)
	idv.add_child(nameline)
	_sub_lbl = Label.new()
	_sub_lbl.add_theme_font_size_override("font_size", 12)
	_sub_lbl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	idv.add_child(_sub_lbl)
	_xp_bar = _mini_bar(Color(0.42, 0.50, 0.85), 150)
	_xp_bar.tooltip_text = "Experiência — enche e sobe de nível"
	idv.add_child(_xp_bar)
	# espaçador
	var spacer := Control.new(); spacer.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(spacer)
	# HP + estamina
	var vit := VBoxContainer.new(); vit.add_theme_constant_override("separation", 4)
	vit.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	_hp_bar = _mini_bar(Color(0.70, 0.22, 0.20), 170)
	_hp_bar.tooltip_text = "Vida (HP), em % — regenera com o tempo; cure na hora no Templo"
	vit.add_child(_labeled_bar("HP", _hp_bar))
	_stam_bar = _mini_bar(Color(0.36, 0.65, 0.38), 170)
	_stam_bar.tooltip_text = "Estamina — gasta nas ações; enche 100% em 1h (15min com buff de novato)"
	var sl := _labeled_bar("Estamina", _stam_bar)
	_stam_lbl = sl.get_meta("vlabel") as Label
	vit.add_child(sl)
	row.add_child(vit)
	# moedas
	var coinbox := VBoxContainer.new(); coinbox.add_theme_constant_override("separation", 2)
	coinbox.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var c1 := HBoxContainer.new(); c1.add_theme_constant_override("separation", 10)
	c1.add_child(_coin("gold")); c1.add_child(_coin("silver")); c1.add_child(_coin("bronze"))
	coinbox.add_child(c1)
	var c2 := HBoxContainer.new(); c2.add_theme_constant_override("separation", 10)
	c2.add_child(_coin("soulstone"))
	coinbox.add_child(c2)
	row.add_child(coinbox)
	# buffs ativos (badges com tooltip) — populados em update_topbar
	_buffs_box = HBoxContainer.new()
	_buffs_box.add_theme_constant_override("separation", 5)
	_buffs_box.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(_buffs_box)
	return pc

func _coin(key: String) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 5)
	h.tooltip_text = str(COIN_TIPS.get(key, ""))   # hover explica a moeda
	h.mouse_filter = Control.MOUSE_FILTER_STOP      # recebe o hover (o rect/label são IGNORE)
	h.add_child(Icons.rect(key, 20))
	var l := Label.new(); l.text = "0"; l.add_theme_font_size_override("font_size", 13)
	l.add_theme_color_override("font_color", UiKit.TEXT)
	l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(l)
	_coins[key] = l
	return h

func _mini_bar(fill: Color, w: int) -> ProgressBar:
	var pb := ProgressBar.new()
	pb.min_value = 0; pb.max_value = 100; pb.value = 0
	pb.show_percentage = false
	pb.custom_minimum_size = Vector2(w, 12)
	var bgs := StyleBoxFlat.new(); bgs.bg_color = Color(0.05, 0.045, 0.06)
	bgs.set_border_width_all(1); bgs.border_color = Color(0.40, 0.32, 0.20, 0.6); bgs.set_corner_radius_all(2)
	var fgs := StyleBoxFlat.new(); fgs.bg_color = fill; fgs.set_corner_radius_all(2)
	pb.add_theme_stylebox_override("background", bgs)
	pb.add_theme_stylebox_override("fill", fgs)
	return pb

# Linha "rótulo  [barra]  valor" — guarda o Label de valor em meta "vlabel".
func _labeled_bar(label: String, pb: ProgressBar) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 6)
	var k := Label.new(); k.text = label; k.add_theme_font_size_override("font_size", 11)
	k.add_theme_color_override("font_color", UiKit.TEXT_DIM); k.custom_minimum_size = Vector2(58, 0)
	h.add_child(k)
	h.add_child(pb)
	var v := Label.new(); v.text = ""; v.add_theme_font_size_override("font_size", 11)
	v.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	h.add_child(v)
	h.set_meta("vlabel", v)
	return h

# ── Nav lateral (árvore recolhível) ─────────────────────────────────────────────────
func _build_nav() -> Control:
	var pc := PanelContainer.new()
	pc.custom_minimum_size = Vector2(210, 0)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.07, 0.065, 0.08, 0.96)
	sb.border_color = Color(0.40, 0.32, 0.20); sb.border_width_right = 2
	sb.set_content_margin_all(8)
	pc.add_theme_stylebox_override("panel", sb)
	var scroll := ScrollContainer.new()
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	scroll.follow_focus = true
	pc.add_child(scroll)
	var nav := VBoxContainer.new()
	nav.add_theme_constant_override("separation", 4)
	nav.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.add_child(nav)
	# LUTAR (ação destacada)
	var fight := _stone_btn("LUTAR", 44)
	Icons.set_icon(fight, "arena")
	fight.add_theme_font_size_override("font_size", 18)
	fight.pressed.connect(func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	nav.add_child(fight)
	# Início (dashboard)
	var home := _stone_btn("🏠  Início", 38)
	home.pressed.connect(_show_dashboard)
	_nav_buttons["__home__"] = home
	nav.add_child(home)
	# seções recolhíveis
	for section in SECTIONS:
		var items := VBoxContainer.new(); items.add_theme_constant_override("separation", 3)
		var head := Button.new()
		head.flat = true
		head.alignment = HORIZONTAL_ALIGNMENT_LEFT
		head.text = "▾  " + str(section[0]).to_upper()
		head.add_theme_font_size_override("font_size", 13)
		head.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		head.pressed.connect(func() -> void:
			items.visible = not items.visible
			head.text = ("▾  " if items.visible else "▸  ") + str(section[0]).to_upper())
		nav.add_child(head)
		nav.add_child(items)
		for entry in section[1]:
			items.add_child(_nav_item(str(entry[0]), str(entry[1])))
	nav.add_child(_spacer(10))
	var out := _stone_btn("Sair", 36)
	out.pressed.connect(func() -> void: logout.emit())
	nav.add_child(out)
	return pc

func _nav_item(scr: String, label: String) -> Button:
	var b := Button.new()
	b.flat = true
	b.alignment = HORIZONTAL_ALIGNMENT_LEFT
	b.custom_minimum_size = Vector2(0, 34)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	Icons.label_button(b, scr.to_lower(), label)
	b.add_theme_font_size_override("font_size", 14)
	b.pressed.connect(func() -> void: _open(scr))
	_nav_buttons[scr] = b
	return b

func _set_active(nm: String) -> void:
	active_name = nm
	for k in _nav_buttons:
		var b: Button = _nav_buttons[k]
		if k == nm:
			b.add_theme_color_override("font_color", UiKit.GOLD)
			b.modulate = Color(1, 1, 1, 1)
		else:
			b.remove_theme_color_override("font_color")
			b.modulate = Color(1, 1, 1, 0.82)

# ── Navegação / hospedagem das telas ────────────────────────────────────────────────
func _open(scr: String) -> void:
	if scr == "":
		return
	var scene = load("res://ui/%s.tscn" % scr)
	if scene == null:
		push_warning("tela não encontrada: %s" % scr)
		return
	_clear_content()
	var node = scene.instantiate()
	node.set_meta("embedded", true)   # UiKit.scaffold roda em modo embutido (sem fundo/←/carteira)
	active_screen = node
	content_host.add_child(node)
	_wire_screen(node)
	_set_active(scr)
	await refresh_warrior()   # topbar fresco ao trocar de tela (+ re-veste o busto)

func _wire_screen(c: Control) -> void:
	if c.has_signal("go_back"):
		c.go_back.connect(_show_dashboard)
	if c.has_signal("open_screen"):
		c.open_screen.connect(_open)
	if c.has_signal("go_inventory"):
		c.go_inventory.connect(func() -> void: _open("Inventory"))
	if c.has_signal("go_battle"):
		c.go_battle.connect(func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	if c.has_signal("request_battle"):
		c.request_battle.connect(func(data) -> void: request_battle.emit(data))
	if c.has_signal("logout"):
		c.logout.connect(func() -> void: logout.emit())

func _clear_content() -> void:
	if active_screen != null and is_instance_valid(active_screen):
		active_screen.queue_free()
	active_screen = null
	for ch in content_host.get_children():
		ch.queue_free()

# Chamado pelo App quando o replay de batalha termina → repassa pra tela ativa.
func _on_battle_over() -> void:
	if active_screen != null and is_instance_valid(active_screen) and active_screen.has_method("_on_battle_over"):
		active_screen._on_battle_over()
	await refresh_warrior()

# ── Dashboard / home ────────────────────────────────────────────────────────────────
func _show_dashboard() -> void:
	_clear_content()
	_set_active("__home__")
	var scroll := ScrollContainer.new()
	scroll.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	content_host.add_child(scroll)
	var pad := MarginContainer.new()
	pad.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for s in ["left", "right", "top", "bottom"]:
		pad.add_theme_constant_override("margin_" + s, 20)
	scroll.add_child(pad)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 12)
	box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	pad.add_child(box)
	var hi := Label.new()
	hi.text = "Bem-vindo, %s" % str(warrior.get("name", "guerreiro"))
	hi.add_theme_font_size_override("font_size", 26)
	hi.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(hi)
	box.add_child(UiKit.dim("Escolha uma atividade no menu à esquerda, ou use os atalhos abaixo."))
	# LUTAR grande
	var fight := UiKit.action_big("⚔  Lutar", func() -> void: get_tree().change_scene_to_file("res://BattleReplay.tscn"))
	fight.custom_minimum_size = Vector2(0, 52)
	box.add_child(fight)
	# atalhos rápidos
	box.add_child(UiKit.section("Atalhos"))
	var grid := GridContainer.new(); grid.columns = 3
	grid.add_theme_constant_override("h_separation", 10); grid.add_theme_constant_override("v_separation", 10)
	grid.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for sc in [["World", "Mundo"], ["Inventory", "Inventário"], ["Shop", "Loja"], ["Daily", "Diário"], ["Forge", "Forja"], ["Character", "Personagem"]]:
		var b := _stone_btn(str(sc[1]), 44)
		Icons.set_icon(b, str(sc[0]).to_lower())
		var target: String = str(sc[0])
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		b.pressed.connect(func() -> void: _open(target))
		grid.add_child(b)
	box.add_child(grid)

# ── Atualização do warrior / topbar ─────────────────────────────────────────────────
func refresh_warrior() -> void:
	var api = get_node_or_null("/root/Api")
	if api == null:
		return
	var r = await api.get_warrior()
	if r.get("ok") and r.get("json") is Dictionary:
		warrior = r["json"]
		update_topbar(warrior)
	if _bust != null and is_instance_valid(_bust):
		await _bust.refresh()

# Atualiza só o topbar a partir de um WarriorResponse (chamado tb pelas telas via UiKit.set_wallet).
func update_topbar(w: Dictionary) -> void:
	if w.is_empty() or _name_lbl == null:
		return
	_name_lbl.text = str(w.get("name", "?"))
	var t := str(w.get("title", ""))
	_title_lbl.text = ("⟨%s⟩" % t) if t != "" else ""
	_sub_lbl.text = "%s · Nível %d" % [str(w.get("warriorClass", "Recruta")), int(w.get("level", 1))]
	var xp := int(w.get("experience", 0))
	var need := int(w.get("expNeeded", 0))
	_xp_bar.max_value = maxi(1, xp + need)
	_xp_bar.value = clampi(xp, 0, int(_xp_bar.max_value))
	var hp := int(w.get("hpPercent", w.get("currentHp", 100)))
	_hp_bar.value = clampi(hp, 0, 100)
	var stam := int(w.get("stamina", 0))
	_stam_bar.value = clampi(stam, 0, 100)
	if _stam_lbl != null:
		_stam_lbl.text = "%d%%" % stam
	for key in _coins:
		var field: String = "soulStones" if key == "soulstone" else str(key)
		_coins[key].text = str(int(w.get(field, 0)))
	_refresh_buffs(w)

# Badges dos buffs ATIVOS na topbar (com tooltip de nome + tempo). Reconstrói a cada update.
func _refresh_buffs(w: Dictionary) -> void:
	if _buffs_box == null:
		return
	for c in _buffs_box.get_children():
		c.queue_free()
	var ab := str(w.get("activeBuff", ""))
	if ab != "":
		_buffs_box.add_child(_buff_badge(ab, "Bênção do Templo: %s — %s" % [ab, _fmt_left(int(w.get("buffSecondsLeft", 0)))]))
	var ab2 := str(w.get("activeBuff2", ""))
	if ab2 != "":
		_buffs_box.add_child(_buff_badge(ab2, "Bênção VIP (2º slot): %s — %s" % [ab2, _fmt_left(int(w.get("buff2SecondsLeft", 0)))]))
	var meal := str(w.get("mealBuff", ""))
	if meal != "":
		_buffs_box.add_child(_buff_badge(meal, "Bem Alimentado: %s — %s" % [meal, _fmt_left(int(w.get("mealBuffSecondsLeft", 0)))]))
	var we := str(w.get("weaponElement", ""))
	if we != "":
		_buffs_box.add_child(_buff_badge("%s⚔" % _elem_icon(we), "Arma encantada (%s): ±25%% por elemento — %s" % [we, _fmt_left(int(w.get("weaponElementSecondsLeft", 0)))]))
	var ae := str(w.get("armorElement", ""))
	if ae != "":
		_buffs_box.add_child(_buff_badge("%s🛡" % _elem_icon(ae), "Armadura encantada (%s): ±25%% por elemento — %s" % [ae, _fmt_left(int(w.get("armorElementSecondsLeft", 0)))]))
	if bool(w.get("newbieBuffActive", false)):
		_buffs_box.add_child(_buff_badge("🐣", "Buff de Novato: estamina e HP regeneram 4× mais rápido — %dh restantes" % int(w.get("newbieBuffHoursLeft", 0))))
	var tav := float(w.get("tavernBuffPct", 0.0))
	if tav > 0.0:
		_buffs_box.add_child(_buff_badge("🍺+%.2f%%" % tav, "Buff da Taverna: +%.2f%% em TODOS os stats — %s" % [tav, _fmt_left(int(w.get("tavernBuffSecondsLeft", 0)))]))

func _buff_badge(text: String, tip: String) -> Control:
	var pc := PanelContainer.new()
	pc.tooltip_text = tip
	pc.mouse_filter = Control.MOUSE_FILTER_STOP
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.12, 0.11, 0.08, 0.95)
	sb.border_color = UiKit.GOLD_SOFT; sb.set_border_width_all(1)
	sb.set_corner_radius_all(3); sb.set_content_margin_all(4)
	pc.add_theme_stylebox_override("panel", sb)
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", 12)
	l.add_theme_color_override("font_color", UiKit.GOLD)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	pc.add_child(l)
	return pc

func _elem_icon(e: String) -> String:
	return str(ELEM_ICONS.get(e, "✨"))

func _fmt_left(secs: int) -> String:
	if secs <= 0:
		return "expirando"
	var h := secs / 3600
	var m := (secs % 3600) / 60
	if h > 0:
		return "%dh %dmin" % [h, m]
	if m > 0:
		return "%d min" % m
	return "%d s" % secs

# ── helpers ──────────────────────────────────────────────────────────────────────────
func _stone_btn(text: String, h: int) -> Button:
	var b := Button.new()
	b.text = text
	StoneStyle.apply(b)
	b.custom_minimum_size = Vector2(0, h)
	return b

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
