extends Control
# ── Tela BAÚ (Stash) ──────────────────────────────────────────────────────────────
# Espelha o openStash() do app.js (~1754). Dois lados:
#   • Mochila (bag): itens não-equipados (GET /api/inventory) + recursos (GET /api/gathering/resources)
#   • Baú (stash):  itens + recursos guardados (GET /api/stash → items/resources/used/max/fee/bagUsed/bagMax)
# Ações: depositar/sacar item (POST /api/stash/{deposit|withdraw}/item/{id}) e recurso
#         (POST /api/stash/{deposit|withdraw}/resource/{type} {quantity}). Cada move cobra `fee` bronze.
# Recursos: sem prompt nativo no Godot → move a quantidade TODA da pilha. Volta pro Hub (go_back). [MIGRACAO_GODOT]

signal go_back

# raridade 1-5 → cor (igual ao Inventory.gd)
const RARITY_COL := [Color(0.72, 0.72, 0.75), Color(0.45, 0.85, 0.45), Color(0.4, 0.6, 1.0), Color(0.78, 0.45, 0.95), Color(1.0, 0.8, 0.35)]

var content: VBoxContainer
var status: Label
var busy := false
var stash: Dictionary = {}      # GET /api/stash
var bag_items: Array = []       # itens da mochila (não equipados)
var bag_res: Array = []         # recursos da mochila (quantity > 0)

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
	# header: ← voltar + título + ↻ sync
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	var back := Button.new(); back.text = "←"; back.custom_minimum_size = Vector2(44, 36)
	back.pressed.connect(func() -> void: go_back.emit())
	header.add_child(back)
	var ttl := Label.new(); ttl.text = "🏛 Baú"; ttl.add_theme_font_size_override("font_size", 26)
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
	var rs = await Api.get_stash()
	if not (rs.get("ok") and rs.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(rs.get("status", "?"))
		return
	stash = rs["json"]
	var ri = await Api.get_inventory()
	bag_items = []
	if ri.get("ok") and ri.get("json") is Array:
		for it in ri["json"]:
			if it is Dictionary and not bool(it.get("equipped", false)):
				bag_items.append(it)
	var rr = await Api.get_resources()
	bag_res = []
	if rr.get("ok") and rr.get("json") is Array:
		for r in rr["json"]:
			if r is Dictionary and int(r.get("quantity", 0)) > 0:
				bag_res.append(r)
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	var fee := int(stash.get("fee", 0))
	var fee_lbl := Label.new()
	fee_lbl.text = "Taxa: %d bronze por movimento (depositar/sacar)" % fee
	fee_lbl.modulate = Color(1.0, 0.76, 0.03)
	fee_lbl.add_theme_font_size_override("font_size", 12)
	content.add_child(fee_lbl)
	content.add_child(_spacer(4))
	# ── Mochila (bag) ──  used/max vem de bagUsed/bagMax
	var bag_used := int(stash.get("bagUsed", 0))
	var bag_max := int(stash.get("bagMax", 0))
	content.add_child(_section("Mochila (%d/%d)" % [bag_used, bag_max]))
	if bag_items.is_empty() and bag_res.is_empty():
		content.add_child(_dim("— vazia —"))
	for it in bag_items:
		content.add_child(_item_row(it, true))
	for r in bag_res:
		content.add_child(_res_row(r, true))
	content.add_child(_spacer(10))
	# ── Baú (stash) ──  used/max(-1=∞)
	var st_items: Array = stash.get("items", []) if stash.get("items") is Array else []
	var st_res: Array = stash.get("resources", []) if stash.get("resources") is Array else []
	var used := int(stash.get("used", 0))
	var smax := int(stash.get("max", -1))
	var cap := "∞" if smax < 0 else str(smax)
	content.add_child(_section("Baú (%d/%s)" % [used, cap]))
	if st_items.is_empty() and st_res.is_empty():
		content.add_child(_dim("— vazio —"))
	for it in st_items:
		content.add_child(_item_row(it, false))
	for r in st_res:
		content.add_child(_res_row(r, false))

# in_bag=true → botão "→ Baú" (depositar); false → "→ Mochila" (sacar)
func _item_row(it: Dictionary, in_bag: bool) -> PanelContainer:
	var rarity := int(it.get("rarity", 1))
	var col: Color = RARITY_COL[clampi(rarity - 1, 0, 4)]
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1); sb.border_color = Color(col, 0.6)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	var left := VBoxContainer.new(); left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new(); nm.text = str(it.get("name", "?")); nm.modulate = col
	nm.add_theme_font_size_override("font_size", 16)
	left.add_child(nm)
	var sub := Label.new()
	sub.text = "Nv %d · %s" % [int(it.get("itemLevel", 1)), _stats_line(it)]
	sub.modulate = Color(1, 1, 1, 0.55); sub.add_theme_font_size_override("font_size", 12)
	left.add_child(sub)
	hb.add_child(left)
	var id := int(it.get("id", 0))
	if in_bag:
		hb.add_child(_act("→ Baú", _deposit_item.bind(id)))
	else:
		hb.add_child(_act("→ Mochila", _withdraw_item.bind(id)))
	return panel

func _res_row(r: Dictionary, in_bag: bool) -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1); sb.border_color = Color(0.4, 0.4, 0.45, 0.6)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	var name_lbl := Label.new()
	var qty := int(r.get("quantity", 0))
	name_lbl.text = "📦 %s ×%d" % [str(r.get("displayName", r.get("type", "?"))), qty]
	name_lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	name_lbl.add_theme_font_size_override("font_size", 15)
	hb.add_child(name_lbl)
	var rtype := str(r.get("type", ""))
	if in_bag:
		hb.add_child(_act("→ Baú", _deposit_resource.bind(rtype, qty)))
	else:
		hb.add_child(_act("→ Mochila", _withdraw_resource.bind(rtype, qty)))
	return panel

# ── Ações async (cada move re-baixa o estado: a taxa muda moeda e os dois lados) ──
func _deposit_item(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_deposit_item(id)
	await _after(r)
	busy = false

func _withdraw_item(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_withdraw_item(id)
	await _after(r)
	busy = false

func _deposit_resource(rtype: String, qty: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_deposit_resource(rtype, qty)
	await _after(r)
	busy = false

func _withdraw_resource(rtype: String, qty: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_withdraw_resource(rtype, qty)
	await _after(r)
	busy = false

func _after(r) -> void:
	if r is Dictionary and r.get("ok"):
		await _refresh()
		if r.get("json") is Dictionary:
			status.text = str(r["json"].get("message", ""))
	else:
		_show_error(r)

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

func _stats_line(it: Dictionary) -> String:
	var parts: Array = []
	for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]:
		var v := int(it.get(pair[0], 0))
		if v != 0:
			parts.append("%s %+d" % [pair[1], v])
	return "  ".join(parts) if not parts.is_empty() else "–"

# ── helpers de UI ────────────────────────────────────────────────────────────────
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
