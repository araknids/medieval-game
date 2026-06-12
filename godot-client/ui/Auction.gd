extends Control
# ── Tela CASA DE LEILÃO (Auction House) — preço fixo / buyout. [LEILAO][MIGRACAO_GODOT] ─────
# 3 seções: 🛒 Browse (comprar), 📋 Minhas listagens (cancelar), ➕ Listar item (da mochila).
# Endpoints: GET /api/auction, GET /api/auction/mine, GET /api/inventory,
#            POST /api/auction/buy/{id}, POST /api/auction/cancel/{id}, POST /api/auction/list {itemId, price}.
# Taxa: 5% adiantada (queima) + 15% na venda → vendedor recebe ~80%. Listagens duram 2 dias, máx 10.

signal go_back

# raridade 1-5 → cor (igual ao Inventory.gd)
const RARITY_COL := [Color(0.72, 0.72, 0.75), Color(0.45, 0.85, 0.45), Color(0.4, 0.6, 1.0), Color(0.78, 0.45, 0.95), Color(1.0, 0.8, 0.35)]

var content: VBoxContainer
var status: Label
var busy := false
var listings: Array = []   # browse (de todos)
var mine: Array = []       # minhas listagens ativas
var bag: Array = []        # itens da mochila p/ listar (não-equipados)
var price_inputs := {}     # itemId(int) → SpinBox (preço digitado na seção de listagem)

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var bg := ColorRect.new()
	bg.color = Color(0.09, 0.08, 0.11)
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	var root := VBoxContainer.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side in ["left", "right", "top", "bottom"]:
		root.add_theme_constant_override("margin_" + side, 0)
	add_child(root)
	# header: ← voltar + título + ↻
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	var back := Button.new(); back.text = "←"; back.custom_minimum_size = Vector2(44, 36)
	back.pressed.connect(func() -> void: go_back.emit())
	header.add_child(back)
	var ttl := Label.new(); ttl.text = "Casa de Leilão"; ttl.add_theme_font_size_override("font_size", 26)
	ttl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	header.add_child(ttl)
	var sync := Button.new(); sync.text = "↻"; sync.custom_minimum_size = Vector2(40, 36)
	sync.pressed.connect(func() -> void: await _refresh())
	header.add_child(sync)
	var m := MarginContainer.new()
	for side in ["left", "right", "top"]:
		m.add_theme_constant_override("margin_" + side, 16)
	m.add_child(header)
	root.add_child(m)
	status = Label.new(); status.add_theme_constant_override("margin_left", 16)
	root.add_child(status)
	# lista rolável
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	root.add_child(scroll)
	var inner := MarginContainer.new()
	inner.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for side in ["left", "right", "bottom"]:
		inner.add_theme_constant_override("margin_" + side, 16)
	scroll.add_child(inner)
	content = VBoxContainer.new()
	content.add_theme_constant_override("separation", 6)
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	inner.add_child(content)
	await _refresh()

func _refresh() -> void:
	status.text = "Carregando…"
	var rl = await Api.auction_browse()
	var rm = await Api.auction_mine()
	var ri = await Api.get_inventory()
	if not (rl.get("ok") and rl.get("json") is Array):
		status.text = "Erro ao carregar (%s)" % str(rl.get("status", "?"))
		return
	listings = rl["json"]
	mine = rm["json"] if (rm.get("ok") and rm.get("json") is Array) else []
	bag = []
	if ri.get("ok") and ri.get("json") is Array:
		for it in ri["json"]:
			if it is Dictionary and not bool(it.get("equipped", false)):
				bag.append(it)
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	price_inputs.clear()
	# nota da taxa
	var note := Label.new()
	note.text = "Mercado de preço fixo. Taxa: 5% adiantada (queima) + 15% na venda → você recebe 80%. Duram 2 dias, máx 10."
	note.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	note.modulate = Color(1, 1, 1, 0.55); note.add_theme_font_size_override("font_size", 12)
	content.add_child(note)
	# ── 🛒 Browse ──
	content.add_child(_section("🛒 Comprar (%d)" % listings.size()))
	if listings.is_empty():
		content.add_child(_dim("— nenhum item à venda agora —"))
	for a in listings:
		if a is Dictionary:
			content.add_child(_listing_row(a, false))
	content.add_child(_spacer(8))
	# ── 📋 Minhas listagens ──
	content.add_child(_section("📋 Minhas listagens (%d/10)" % mine.size()))
	if mine.is_empty():
		content.add_child(_dim("— nenhuma listagem ativa —"))
	for a in mine:
		if a is Dictionary:
			content.add_child(_listing_row(a, true))
	content.add_child(_spacer(8))
	# ── ➕ Listar item ──
	content.add_child(_section("➕ Listar um item (%d)" % bag.size()))
	if bag.is_empty():
		content.add_child(_dim("— nada na mochila p/ listar —"))
	for it in bag:
		if it is Dictionary:
			content.add_child(_picker_row(it))

# Card de uma listagem (browse ou minha). is_mine_section → botão Cancelar; senão Comprar.
func _listing_row(a: Dictionary, is_mine_section: bool) -> PanelContainer:
	var rarity := int(a.get("rarity", 1))
	var col: Color = RARITY_COL[clampi(rarity - 1, 0, 4)]
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1); sb.border_color = Color(col, 0.6)
	sb.set_corner_radius_all(5); sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	# esquerda: nome + sub + stats + vendedor
	var left := VBoxContainer.new(); left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	var name_txt := str(a.get("name", "?"))
	if int(a.get("sockets", 0)) > 0:
		name_txt += "  ◇%d" % int(a.get("sockets", 0))
	nm.text = name_txt; nm.modulate = col; nm.add_theme_font_size_override("font_size", 16)
	left.add_child(nm)
	var sub := Label.new()
	sub.text = "%s · 🔧%d%% · ⏳ %s" % [str(a.get("typeDisplay", a.get("type", ""))), int(a.get("durability", 100)), _time_left(int(a.get("secondsLeft", 0)))]
	sub.modulate = Color(1, 1, 1, 0.55); sub.add_theme_font_size_override("font_size", 12)
	left.add_child(sub)
	var stats := _stats_line(a)
	if stats != "":
		var sl := Label.new(); sl.text = stats; sl.add_theme_font_size_override("font_size", 12); sl.modulate = Color(0.8, 0.9, 0.8)
		left.add_child(sl)
	var affs = a.get("affixes", [])
	if affs is Array and not affs.is_empty():
		var al := Label.new(); al.text = " · ".join(affs); al.add_theme_font_size_override("font_size", 11); al.modulate = Color(0.55, 0.76, 0.29)
		left.add_child(al)
	var seller := Label.new()
	var seller_txt := "Vendedor: %s" % str(a.get("sellerName", "?"))
	if is_mine_section:
		seller_txt += " · você recebe %d na venda" % int(a.get("sellerPayout", 0))
	seller.text = seller_txt; seller.modulate = Color(1, 1, 1, 0.4); seller.add_theme_font_size_override("font_size", 11)
	left.add_child(seller)
	hb.add_child(left)
	# direita: preço + ação
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 4)
	var price := Label.new(); price.text = "💰 %d" % int(a.get("price", 0)); price.modulate = Color(1, 0.85, 0.4)
	price.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	right.add_child(price)
	var lid := int(a.get("listingId", 0))
	if is_mine_section:
		var cancel := _act("Cancelar", _cancel.bind(lid))
		cancel.modulate = Color(1, 0.6, 0.6)
		right.add_child(cancel)
	else:
		var buy := _act("Comprar", _buy.bind(lid))
		if bool(a.get("isMine", false)):
			buy.disabled = true; buy.text = "(sua)"
		right.add_child(buy)
	hb.add_child(right)
	return panel

# Card da seção "Listar": item da mochila + campo de preço + botão Listar.
func _picker_row(it: Dictionary) -> PanelContainer:
	var rarity := int(it.get("rarity", 1))
	var col: Color = RARITY_COL[clampi(rarity - 1, 0, 4)]
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.11, 0.13, 0.13)
	sb.set_border_width_all(1); sb.border_color = Color(col, 0.5)
	sb.set_corner_radius_all(5); sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	var left := VBoxContainer.new(); left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new(); nm.text = str(it.get("name", "?")); nm.modulate = col; nm.add_theme_font_size_override("font_size", 16)
	left.add_child(nm)
	var sub := Label.new()
	sub.text = "%s · Nv %d · %s" % [str(it.get("typeDisplay", it.get("type", ""))), int(it.get("itemLevel", 1)), str(it.get("rarityName", ""))]
	sub.modulate = Color(1, 1, 1, 0.55); sub.add_theme_font_size_override("font_size", 12)
	left.add_child(sub)
	var stats := _stats_line(it)
	if stats != "":
		var sl := Label.new(); sl.text = stats; sl.add_theme_font_size_override("font_size", 12); sl.modulate = Color(0.8, 0.9, 0.8)
		left.add_child(sl)
	hb.add_child(left)
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 4)
	var itid := int(it.get("id", 0))
	var price := SpinBox.new()
	price.min_value = 1; price.max_value = 99999999; price.value = 1; price.step = 1
	price.custom_minimum_size = Vector2(120, 0)
	price.prefix = "💰 "
	price_inputs[itid] = price
	right.add_child(price)
	right.add_child(_act("Listar", _list.bind(itid)))
	hb.add_child(right)
	return panel

# ── Ações (1 chamada → re-sincroniza com _refresh) ─────────────────────────────────
func _buy(listing_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.auction_buy(listing_id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		status.text = "✅ %s" % str(r["json"].get("message", "Comprado!"))
		await _refresh()
	else:
		_show_error(r)

func _cancel(listing_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.auction_cancel(listing_id)
	busy = false
	if r.get("ok"):
		status.text = "Listagem cancelada — item de volta na mochila."
		await _refresh()
	else:
		_show_error(r)

func _list(item_id: int) -> void:
	if busy: return
	var sp: SpinBox = price_inputs.get(item_id)
	var price := int(sp.value) if sp != null else 0
	if price < 1:
		status.text = "Informe um preço válido."
		return
	busy = true
	var r = await Api.auction_list(item_id, price)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		status.text = "✅ Listado! (taxa de 5% cobrada)"
		await _refresh()
	else:
		_show_error(r)

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

# ── helpers ────────────────────────────────────────────────────────────────────────
func _stats_line(it: Dictionary) -> String:
	var parts: Array = []
	for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]:
		var v := int(it.get(pair[0], 0))
		if v != 0:
			parts.append("%s %+d" % [pair[1], v])
	return "   ".join(parts)

func _time_left(s: int) -> String:
	var d := s / 86400
	var h := (s % 86400) / 3600
	if d > 0:
		return "%dd %dh" % [d, h]
	var mn := (s % 3600) / 60
	return "%dh %dm" % [h, mn]

func _act(text: String, cb: Callable) -> Button:
	var b := Button.new(); b.text = text; b.custom_minimum_size = Vector2(120, 0)
	b.pressed.connect(cb)
	return b

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4)
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
