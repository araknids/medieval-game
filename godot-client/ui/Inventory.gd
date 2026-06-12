extends Control
# ── Tela INVENTÁRIO / EQUIPAR ─────────────────────────────────────────────────────
# Lista GET /api/inventory (equipados + mochila), equipa/desequipa/vende. Nome colorido
# pela raridade. Volta pro Personagem (sinal go_back). [MIGRACAO_GODOT]

signal go_back

# raridade 1-5 → cor (igual ao brilho da arma no combate)
const RARITY_COL := [Color(0.72, 0.72, 0.75), Color(0.45, 0.85, 0.45), Color(0.4, 0.6, 1.0), Color(0.78, 0.45, 0.95), Color(1.0, 0.8, 0.35)]

var content: VBoxContainer
var status: Label
var busy := false
var items: Array = []   # cache local → ações atualizam em memória (sem re-baixar a lista)

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
	# header: ← voltar + título
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	var back := Button.new(); back.text = "←"; back.custom_minimum_size = Vector2(44, 36)
	back.pressed.connect(func() -> void: go_back.emit())
	header.add_child(back)
	var ttl := Label.new(); ttl.text = "Inventário"; ttl.add_theme_font_size_override("font_size", 26)
	ttl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	header.add_child(ttl)
	var sync := Button.new(); sync.text = "↻"; sync.custom_minimum_size = Vector2(40, 36)   # re-sincroniza com o servidor
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
	var r = await Api.get_inventory()
	if not (r.get("ok") and r.get("json") is Array):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	items = r["json"]
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	var equipped: Array = []
	var bag: Array = []
	for it in items:
		if it is Dictionary:
			(equipped if it.get("equipped", false) else bag).append(it)
	content.add_child(_section("Equipado (%d)" % equipped.size()))
	if equipped.is_empty():
		content.add_child(_dim("— nada equipado —"))
	for it in equipped:
		content.add_child(_item_row(it))
	content.add_child(_spacer(8))
	content.add_child(_section("Mochila (%d)" % bag.size()))
	if bag.is_empty():
		content.add_child(_dim("— mochila vazia —"))
	for it in bag:
		content.add_child(_item_row(it))

func _item_row(it: Dictionary) -> PanelContainer:
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
	# esquerda: nome + sub + stats
	var left := VBoxContainer.new(); left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new(); nm.text = str(it.get("name", "?")); nm.modulate = col
	nm.add_theme_font_size_override("font_size", 16)
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
	# direita: ações (handlers async com .bind(id))
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 4)
	var id := int(it.get("id", 0))
	if it.get("equipped", false):
		right.add_child(_act("Desequipar", _unequip.bind(id)))
	else:
		right.add_child(_act("Equipar", _equip.bind(id)))
		right.add_child(_act("Vender (%d🥇)" % int(it.get("sellPrice", 0)), _sell.bind(id)))
	hb.add_child(right)
	return panel

# Ações: 1 chamada só (sem re-baixar a lista). Em sucesso atualizo `items` em memória e re-renderizo;
# em falha não mexo no estado local (nada mudou no servidor) e mostro o erro.
func _equip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.equip_item(id)
	if r.get("ok") and r.get("json") is Dictionary:
		var updated: Dictionary = r["json"]
		for it in items:   # auto-desequipa o item antigo do MESMO slot (tipo)
			if it is Dictionary and str(it.get("type")) == str(updated.get("type")) and int(it.get("id", -1)) != int(updated.get("id", -2)):
				it["equipped"] = false
		_replace_item(updated)
		status.text = ""
		_render()
	else:
		_show_error(r)
	busy = false

func _unequip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.unequip_item(id)
	if r.get("ok") and r.get("json") is Dictionary:
		_replace_item(r["json"])   # equipped=false agora
		status.text = ""
		_render()
	else:
		_show_error(r)
	busy = false

func _sell(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.sell_item(id)
	if r.get("ok") and r.get("json") is Dictionary:
		items = items.filter(func(it): return not (it is Dictionary) or int(it.get("id", -1)) != id)   # some da lista
		status.text = str(r["json"].get("message", "Vendido!"))
		_render()
	else:
		_show_error(r)
	busy = false

func _replace_item(updated: Dictionary) -> void:
	var uid := int(updated.get("id", -1))
	for i in items.size():
		if items[i] is Dictionary and int(items[i].get("id", -2)) == uid:
			items[i] = updated
			return

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
	return "   ".join(parts)

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
