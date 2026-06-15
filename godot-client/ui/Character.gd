extends Control
# ── Tela FICHA DO PERSONAGEM (fusão Personagem + Inventário + Habilidades) [FICHA_PERSONAGEM] ──
# Paper-doll: boneco 3D no centro com SLOTS de equipamento em volta (esquerda) + painel SUB-ABADO
# à direita (🎒 Mochila / ⚔ Atributos / ✨ Habilidades). Equipar atualiza o boneco AO VIVO.
# A topbar do Shell já mostra stats/atributos/HP/moedas → aqui o foco é EQUIPAR/gastar ponto/skill.
# Detalhe verboso (efeito de atributo, stats do item equipado) mora no TOOLTIP (hover).
# Auditoria + desenho: docs/PLANO_FICHA_PERSONAGEM.md. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

const Icons := preload("res://ui/Icons.gd")
const Doll := preload("res://ui/DollView.gd")

# colunas de slots em volta do boneco (ItemType do backend)
const LEFT_SLOTS := ["HELMET", "ARMOR", "GLOVES", "PANTS", "BOOTS"]
const RIGHT_SLOTS := ["WEAPON", "SHIELD", "SHOULDER", "RING", "NECKLACE"]
const SLOT_LABEL := {
	"WEAPON": "Arma", "SHIELD": "Escudo", "HELMET": "Elmo", "ARMOR": "Peito",
	"PANTS": "Pernas", "BOOTS": "Botas", "GLOVES": "Luvas", "SHOULDER": "Ombros",
	"RING": "Anel", "NECKLACE": "Colar",
}

# atributo: chave no JSON, sigla
const ATTRS := [
	["strength", "STR"], ["constitution", "CON"], ["dexterity", "DEX"],
	["agility", "AGI"], ["luck", "LUK"], ["intellect", "INT"],
]
# Ganho EXATO por ponto (números do backend committado = prod). CON tem soft-cap (8→4→2) por faixa,
# então é calculado em _attr_gain a partir da CON atual. [REBALANCE v2]

var w: Dictionary = {}
var items: Array = []
var abilities_data: Dictionary = {}
var sub_tab := "bag"          # "bag" | "attr" | "abil"
var rarity_filter := 0
var busy := false

var content: VBoxContainer
var status: Label
var wallet
var doll: DollView
var _id_name: Label
var _id_sub: Label
var _slots := {}                  # type -> {frame, icon, item_id}
var _subtab_bar_host: VBoxContainer
var _panel_host: VBoxContainer    # onde o painel da sub-aba é montado/limpo

func _ready() -> void:
	var ui := UiKit.scaffold(self, "👤 Personagem", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_DEFAULT)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	_build_layout()
	await _refresh()

# ── Estrutura estática (montada UMA vez — não recria o 3D a cada render) ─────────────
func _build_layout() -> void:
	var main := HBoxContainer.new()
	main.add_theme_constant_override("separation", 16)
	main.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_child(main)
	main.add_child(_build_left())
	main.add_child(_build_right())

func _build_left() -> Control:
	var outer := VBoxContainer.new()
	outer.add_theme_constant_override("separation", 8)
	outer.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	outer.add_child(row)
	row.add_child(_slot_column(LEFT_SLOTS))
	doll = Doll.new()
	doll.custom_minimum_size = Vector2(230, 350)
	doll.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	doll.tooltip_text = Lang.t("Arraste para girar o personagem")
	row.add_child(doll)
	row.add_child(_slot_column(RIGHT_SLOTS))
	_id_name = Label.new()
	_id_name.add_theme_font_size_override("font_size", 20)
	_id_name.add_theme_color_override("font_color", UiKit.GOLD)
	_id_name.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	outer.add_child(_id_name)
	_id_sub = Label.new()
	_id_sub.add_theme_font_size_override("font_size", 13)
	_id_sub.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	_id_sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	outer.add_child(_id_sub)
	return outer

func _slot_column(types: Array) -> VBoxContainer:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 6)
	col.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	for t in types:
		col.add_child(_slot_frame(str(t)))
	return col

func _slot_frame(type: String) -> PanelContainer:
	var pc := PanelContainer.new()
	pc.custom_minimum_size = Vector2(56, 56)
	pc.mouse_filter = Control.MOUSE_FILTER_STOP
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.06, 0.055, 0.07, 0.95)
	sb.set_border_width_all(1)
	sb.border_color = UiKit.BRONZE
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(4)
	pc.add_theme_stylebox_override("panel", sb)
	var icon := TextureRect.new()
	icon.texture = Icons.tex("slot_" + type.to_lower())
	icon.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	icon.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	icon.custom_minimum_size = Vector2(44, 44)
	icon.mouse_filter = Control.MOUSE_FILTER_IGNORE
	icon.modulate = Color(1, 1, 1, 0.30)   # vazio = apagado
	pc.add_child(icon)
	pc.tooltip_text = Lang.t(str(SLOT_LABEL.get(type, type)))
	pc.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
			_slot_clicked(type))
	_slots[type] = {"frame": pc, "icon": icon, "item_id": 0}
	return pc

func _build_right() -> Control:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 8)
	col.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_subtab_bar_host = VBoxContainer.new()
	col.add_child(_subtab_bar_host)
	var scroll := ScrollContainer.new()      # ÚNICO scroll da tela (painel da sub-aba)
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	scroll.custom_minimum_size = Vector2(0, 330)
	scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	col.add_child(scroll)
	_panel_host = VBoxContainer.new()
	_panel_host.add_theme_constant_override("separation", 8)
	_panel_host.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.add_child(_panel_host)
	return col

func _build_subtab_bar() -> void:
	for c in _subtab_bar_host.get_children():
		c.queue_free()
	var opts := [
		{"label": "🎒 " + Lang.t("Mochila"), "value": "bag", "color": UiKit.GOLD},
		{"label": "⚔ " + Lang.t("Atributos"), "value": "attr", "color": UiKit.GOLD},
		{"label": "✨ " + Lang.t("Habilidades"), "value": "abil", "color": UiKit.GOLD},
	]
	_subtab_bar_host.add_child(UiKit.filter_row(opts, sub_tab, _set_tab))

func _set_tab(t) -> void:
	sub_tab = str(t)
	_build_subtab_bar()
	_render_panel()

# ── Dados ────────────────────────────────────────────────────────────────────────────
func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/warrior", "/api/inventory", "/api/abilities"])
	var wr = rs[0]
	if not (wr.get("ok") and wr.get("json") is Dictionary):
		UiKit.show_error(status, wr)
		return
	w = wr["json"]
	var ir = rs[1]
	items = ir["json"] if (ir.get("ok") and ir.get("json") is Array) else []
	var ar = rs[2]
	abilities_data = ar["json"] if (ar.get("ok") and ar.get("json") is Dictionary) else {}
	_apply()

func _apply() -> void:
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, w)        # alimenta a topbar (HP/estamina/stats/moedas)
	UiKit.set_equipped(items)
	if doll != null and is_instance_valid(doll):
		doll.apply(items)
	var title := str(w.get("title", ""))
	_id_name.text = (title + "  " if title != "" else "") + str(w.get("name", "?"))
	_id_sub.text = Lang.t("%s · Nível %d") % [Lang.t(str(w.get("warriorClass", "Recruta"))), int(w.get("level", 1))]
	if bool(w.get("isKnockedOut", false)):
		_id_sub.text += "   💀"
	_update_slots()
	_build_subtab_bar()
	_render_panel()

# Preenche cada slot com o item equipado do seu tipo (ou apagado se vazio). Stats no tooltip.
func _update_slots() -> void:
	var eq := {}
	for it in items:
		if it is Dictionary and bool(it.get("equipped", false)):
			eq[str(it.get("type", ""))] = it
	for type in _slots:
		var s: Dictionary = _slots[type]
		var frame: PanelContainer = s["frame"]
		var icon: TextureRect = s["icon"]
		var sb: StyleBoxFlat = frame.get_theme_stylebox("panel")
		if eq.has(type):
			var it: Dictionary = eq[type]
			s["item_id"] = int(it.get("id", 0))
			icon.modulate = Color(1, 1, 1, 1)
			sb.border_color = UiKit.rarity_color(int(it.get("rarity", 1)))
			sb.set_border_width_all(2)
			frame.tooltip_text = _equipped_tooltip(it)
		else:
			s["item_id"] = 0
			icon.modulate = Color(1, 1, 1, 0.30)
			sb.border_color = UiKit.BRONZE
			sb.set_border_width_all(1)
			frame.tooltip_text = Lang.t(str(SLOT_LABEL.get(type, type)))

func _equipped_tooltip(it: Dictionary) -> String:
	var tip := str(it.get("name", "?"))
	tip += "\n" + Lang.t("%s · Nv %d · %s") % [Lang.t(str(it.get("typeDisplay", it.get("type", "")))), int(it.get("itemLevel", 1)), Lang.t(str(it.get("rarityName", "")))]
	var st := _stats_line(it)
	if st != "":
		tip += "\n" + st
	tip += "\n" + Lang.t("(clique para desequipar)")
	return tip

func _slot_clicked(type: String) -> void:
	var s: Dictionary = _slots.get(type, {})
	var id := int(s.get("item_id", 0))
	if id > 0:
		_unequip(id)

# ── Painel da sub-aba ──────────────────────────────────────────────────────────────────
func _render_panel() -> void:
	for c in _panel_host.get_children():
		c.queue_free()
	match sub_tab:
		"attr":
			_render_attr_panel()
		"abil":
			_render_abil_panel()
		_:
			_render_bag_panel()

# 🎒 Mochila ───────────────────────────────────────────────────────────────────────────
func _render_bag_panel() -> void:
	var bag: Array = []
	for it in items:
		if it is Dictionary and not bool(it.get("equipped", false)):
			bag.append(it)
	_panel_host.add_child(UiKit.section(Lang.t("Mochila (%d)") % bag.size()))
	_panel_host.add_child(UiKit.rarity_filter(rarity_filter, _set_rarity))
	if bag.is_empty():
		_panel_host.add_child(UiKit.empty("Mochila vazia", "Vença missões no 🌍 Mundo para conseguir itens"))
		return
	var shown: Array = bag
	if rarity_filter > 0:
		shown = []
		for it in bag:
			if it is Dictionary and int(it.get("rarity", 1)) == rarity_filter:
				shown.append(it)
	if shown.is_empty():
		_panel_host.add_child(UiKit.dim("— nada nessa raridade —"))
		return
	_panel_host.add_child(UiKit.grid(self, shown, _bag_card, true))

func _set_rarity(r) -> void:
	rarity_filter = int(r)
	_render_panel()

func _bag_card(it) -> Control:
	if not (it is Dictionary):
		return null
	var id := int(it.get("id", 0))
	var rar := int(it.get("rarity", 1))
	var res := UiKit.card(UiKit.rarity_color(rar))
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	box.add_child(row)
	var ic := UiKit.item_icon(str(it.get("type", "")), 40)
	if ic:
		row.add_child(ic)
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(left)
	var nm := Label.new()
	nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 14)
	nm.add_theme_color_override("font_color", UiKit.rarity_color(rar))
	left.add_child(nm)
	left.add_child(UiKit.dim(Lang.t("%s · Nv %d · %s") % [Lang.t(str(it.get("typeDisplay", it.get("type", "")))), int(it.get("itemLevel", 1)), Lang.t(str(it.get("rarityName", "")))]))
	var st := _stats_line(it)
	if st != "":
		var sl := Label.new()
		sl.text = st
		sl.add_theme_font_size_override("font_size", 12)
		sl.add_theme_color_override("font_color", Color(0.62, 0.75, 0.58))
		sl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		left.add_child(sl)
	var cmp := UiKit.compare_line(it)   # vs equipado (▲/▼ + deltas)
	if cmp:
		left.add_child(cmp)
	var rcol := VBoxContainer.new()
	rcol.add_theme_constant_override("separation", 6)
	rcol.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(rcol)
	rcol.add_child(UiKit.small_btn("Equipar", _equip.bind(id)))
	if bool(it.get("pvpLocked", false)):
		rcol.add_child(UiKit.small_btn("🔒 PvP", func() -> void: UiKit.flash(status, Lang.t("Item travado no PvP — não dá pra vender enquanto exposto."), 2)))
	else:
		rcol.add_child(UiKit.small_btn(Lang.t("Vender (%s)") % UiKit.coin_str(int(it.get("sellPrice", 0))), _ask_sell.bind(id, str(it.get("name", "?")), rar)))
	return pc

# ⚔ Atributos ──────────────────────────────────────────────────────────────────────────
func _render_attr_panel() -> void:
	var pts := int(w.get("availablePoints", 0))
	var ttl := Lang.t("Atributos")
	if pts > 0:
		ttl += "  (%d %s)" % [pts, Lang.t("livre") if pts == 1 else Lang.t("livres")]
	_panel_host.add_child(UiKit.section(ttl))
	for a in ATTRS:
		_panel_host.add_child(_attr_row(a, pts > 0))
	_panel_host.add_child(UiKit.spacer(6))
	_panel_host.add_child(UiKit.dim("Ataque, defesa e HP efetivos estão na barra de cima."))

# Linha: [ícone] [sigla] [valor] [o que aumenta] [+]. O "+" fica logo após o efeito (não no canto).
func _attr_row(a: Array, can_add: bool) -> Control:
	var key := str(a[0])
	var sig := str(a[1])
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	row.add_child(Icons.rect("attr_" + key, 24))
	var nm := Label.new()
	nm.text = sig
	nm.custom_minimum_size = Vector2(46, 0)
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	row.add_child(nm)
	var val := Label.new()
	val.text = str(int(w.get(key, 0)))
	val.custom_minimum_size = Vector2(34, 0)
	val.add_theme_font_size_override("font_size", 16)
	val.add_theme_color_override("font_color", UiKit.GOLD)
	row.add_child(val)
	var eff := Label.new()
	eff.text = _attr_gain(key, sig)                     # ganho EXATO por ponto (inline)
	eff.custom_minimum_size = Vector2(200, 0)           # largura fixa → o "+" alinha em coluna e fica perto
	eff.add_theme_font_size_override("font_size", 12)
	eff.add_theme_color_override("font_color", UiKit.OK)
	eff.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	row.add_child(eff)
	if can_add:
		var plus := UiKit.icon_btn("+", func() -> void: await _spend(key))
		plus.custom_minimum_size = Vector2(36, 36)
		plus.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(plus)
	return row

# Contribuição TOTAL do valor ATUAL do atributo (não por ponto). Fórmulas do backend committado = prod.
# Ex.: DEX 15 → "+15% acerto · +15 atq (arco)". CON tem soft-cap (8/4/2 por faixa). [REBALANCE v2]
func _attr_gain(key: String, sig: String) -> String:
	var v := int(w.get(key, 0))
	match sig:
		"STR":
			return Lang.t("+%d de ataque") % v
		"CON":
			var t1 := mini(v, 40) * 8
			var t2 := clampi(v - 40, 0, 40) * 4
			var t3 := maxi(v - 80, 0) * 2
			return Lang.t("+%d de vida") % (t1 + t2 + t3)
		"DEX":
			return Lang.t("+%d%% acerto · +%d atq (arco)") % [v, v]
		"AGI":
			return Lang.t("+%d%% golpe · +%d%% esquiva") % [v, (v * 3) / 5]
		"LUK":
			return Lang.t("+%d%% de crítico") % mini(v / 2, 30)
		"INT":
			return Lang.t("reservado (Mago)")
	return ""

# ✨ Habilidades ────────────────────────────────────────────────────────────────────────
func _render_abil_panel() -> void:
	var pts := int(abilities_data.get("abilityPoints", 0))
	var abilities: Array = abilities_data.get("abilities", []) if abilities_data.get("abilities") is Array else []
	if abilities.is_empty():
		_panel_host.add_child(UiKit.section("Habilidades"))
		var pts_txt := (Lang.t("%d ponto") if pts == 1 else Lang.t("%d pontos")) % pts
		_panel_host.add_child(UiKit.empty(
			Lang.t("Você tem %s de habilidade guardado.") % pts_txt,
			"Escolha uma classe (Path Trial no Nv.10) para destravar as habilidades dela."))
		return
	_panel_host.add_child(UiKit.section(Lang.t("Habilidades — %s") % Lang.t(str(abilities_data.get("class", "?")))))
	if pts > 0:
		var pl := Label.new()
		pl.text = Lang.t("⬆ %s para gastar") % ((Lang.t("%d ponto") if pts == 1 else Lang.t("%d pontos")) % pts)
		pl.add_theme_font_size_override("font_size", 14)
		pl.add_theme_color_override("font_color", UiKit.GOLD)
		_panel_host.add_child(pl)
	_panel_host.add_child(UiKit.grid(self, abilities, func(a): return _ability_card(a, pts) if a is Dictionary else null, true))
	_panel_host.add_child(UiKit.spacer(6))
	_panel_host.add_child(UiKit.action_danger(Lang.t("🔄 Resetar habilidades (%s)") % UiKit.coin_str(int(abilities_data.get("respecCost", 0))), _respec))

func _ability_card(a: Dictionary, pts: int) -> PanelContainer:
	var active := bool(a.get("active", false))
	var level := int(a.get("level", 0))
	var max_level := int(a.get("maxLevel", 0))
	var maxed := level >= max_level
	var col: Color = Color(0.48, 0.69, 1.0) if active else Color(0.6, 0.8, 0.6)
	var res := UiKit.card(col)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new()
	hb.add_theme_constant_override("separation", 10)
	box.add_child(hb)
	var icon := Label.new()
	icon.text = str(a.get("icon", "•"))
	icon.custom_minimum_size = Vector2(26, 0)
	icon.add_theme_font_size_override("font_size", 18)
	hb.add_child(icon)
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	nm.text = str(a.get("displayName", "?"))
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", col)
	left.add_child(nm)
	var kind_txt := ""
	if active:
		kind_txt = "⚡ Ativa"
		var cd := int(a.get("cooldown", 0))
		if cd > 0:
			kind_txt += Lang.t(" · CD %d rounds") % cd
	else:
		kind_txt = "🪨 Passiva"
	left.add_child(UiKit.dim(kind_txt))
	var desc := str(a.get("description", ""))
	if desc != "":
		left.add_child(UiKit.dim(desc))
	hb.add_child(left)
	var right := VBoxContainer.new()
	right.add_theme_constant_override("separation", 4)
	right.alignment = BoxContainer.ALIGNMENT_CENTER
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var lvl := Label.new()
	lvl.text = "%d/%d" % [level, max_level]
	lvl.add_theme_font_size_override("font_size", 14)
	lvl.add_theme_color_override("font_color", UiKit.GOLD if maxed else UiKit.TEXT)
	lvl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	right.add_child(lvl)
	var learn := UiKit.small_btn("✖ No máx" if maxed else "+ Aprender", _learn.bind(str(a.get("id", ""))))
	learn.disabled = pts <= 0 or maxed
	right.add_child(learn)
	hb.add_child(right)
	return pc

# ── Ações (cache local em memória; em falha re-sincroniza) ─────────────────────────────
func _equip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.equip_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var updated: Dictionary = r["json"]
		for it in items:   # auto-desequipa o item antigo do MESMO slot
			if it is Dictionary and str(it.get("type")) == str(updated.get("type")) and int(it.get("id", -1)) != int(updated.get("id", -2)):
				it["equipped"] = false
		_replace_item(updated)
		await _after_equip_change()
	else:
		UiKit.show_error(status, r)
		await _refresh()

func _unequip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.unequip_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		_replace_item(r["json"])
		await _after_equip_change()
	else:
		UiKit.show_error(status, r)
		await _refresh()

# Equip mudou: re-veste o boneco + slots + painel + avisa o Shell (busto da topbar + índice) e
# re-busca o warrior p/ os stats EFETIVOS da topbar (ATK/DEF/HP mudam com o gear).
func _after_equip_change() -> void:
	UiKit.set_equipped(items)
	if doll != null and is_instance_valid(doll):
		doll.apply(items)
	_update_slots()
	_render_panel()
	if UiKit.equip_changed_sink.is_valid():
		UiKit.equip_changed_sink.call(items)
	var wr = await Api.get_warrior()
	if wr.get("ok") and wr.get("json") is Dictionary:
		w = wr["json"]
		UiKit.set_wallet(wallet, w)

func _ask_sell(id: int, name_text: String, rarity: int) -> void:
	if rarity >= 3:
		UiKit.confirm(self, Lang.t("Vender %s?") % name_text, Lang.t("Vender"), func() -> void: await _sell(id))
	else:
		await _sell(id)

func _sell(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.sell_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		items = items.filter(func(it): return not (it is Dictionary) or int(it.get("id", -1)) != id)
		_render_panel()
		UiKit.flash(status, str(r["json"].get("message", Lang.t("Vendido!"))), 1)
	else:
		UiKit.show_error(status, r)
		await _refresh()

func _spend(key: String) -> void:
	if busy: return
	busy = true
	var r = await Api.spend_attribute(key.to_upper())
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		w = r["json"]
		UiKit.set_wallet(wallet, w)   # topbar reflete o novo atributo/stat
		_render_panel()
		UiKit.flash(status, "Ponto aplicado", 1)
	else:
		UiKit.show_error(status, r)

func _learn(id: String) -> void:
	if busy or id == "": return
	busy = true
	await _do(await Api.ability_learn(id), Lang.t("Aprimorado!"))
	busy = false

func _respec() -> void:
	UiKit.confirm(self,
		Lang.t("Resetar todas as habilidades por %s? Os pontos voltam para você.") % UiKit.coin_str(int(abilities_data.get("respecCost", 0))),
		"Resetar",
		func() -> void: await _do_respec())

func _do_respec() -> void:
	if busy: return
	busy = true
	await _do(await Api.ability_respec(), "Habilidades resetadas.")
	busy = false

# r = resultado JÁ resolvido; re-sincroniza habilidades + warrior (pontos/stat na topbar) e mostra feedback.
func _do(r, default_msg: String) -> void:
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary:
		var msg := str(r["json"].get("message", default_msg))
		var rs = await Api.batch_get(["/api/abilities", "/api/warrior"])
		var ar = rs[0]
		if ar.get("ok") and ar.get("json") is Dictionary:
			abilities_data = ar["json"]
		var wr = rs[1]
		if wr.get("ok") and wr.get("json") is Dictionary:
			w = wr["json"]
			UiKit.set_wallet(wallet, w)
		_render_panel()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# ── helpers ────────────────────────────────────────────────────────────────────────────
func _replace_item(updated: Dictionary) -> void:
	var uid := int(updated.get("id", -1))
	for i in items.size():
		if items[i] is Dictionary and int(items[i].get("id", -2)) == uid:
			items[i] = updated
			return
	items.append(updated)   # segurança: item que não estava no cache local

func _stats_line(it: Dictionary) -> String:
	var parts: Array = []
	for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]:
		var v := int(it.get(pair[0], 0))
		if v != 0:
			parts.append("%s %+d" % [pair[1], v])
	return "   ".join(parts)
