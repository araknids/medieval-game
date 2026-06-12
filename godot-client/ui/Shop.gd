extends Control
# ── Tela LOJA ─────────────────────────────────────────────────────────────────────
# Lista GET /api/shop (mercador + itens em rotação de 6h) e compra item único
# (POST /api/shop/buy/{id}). Item comprado fica marcado "✓ Comprado". Nome colorido
# pela raridade. Volta pro Personagem (sinal go_back). [MIGRACAO_GODOT]

signal go_back

# raridade 1-5 → cor (igual ao brilho da arma no combate)
const RARITY_COL := [Color(0.72, 0.72, 0.75), Color(0.45, 0.85, 0.45), Color(0.4, 0.6, 1.0), Color(0.78, 0.45, 0.95), Color(1.0, 0.8, 0.35)]

var content: VBoxContainer
var status: Label
var busy := false
var data: Dictionary = {}   # cache do GET /api/shop (items + mercador + timer)
var secs := 0               # segundos até a próxima rotação (decai por _process)

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
	# header: ← voltar + título + ↻ sincronizar
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	var back := Button.new(); back.text = "←"; back.custom_minimum_size = Vector2(44, 36)
	back.pressed.connect(func() -> void: go_back.emit())
	header.add_child(back)
	var ttl := Label.new(); ttl.text = "Loja"; ttl.add_theme_font_size_override("font_size", 26)
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
	var r = await Api.shop_get()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	data = r["json"]
	secs = int(data.get("secondsUntilNext", 0))
	status.text = ""
	_render()

# timer da rotação: decai em tempo real; ao zerar, recarrega a loja.
func _process(delta: float) -> void:
	if secs <= 0 or data.is_empty():
		return
	secs = max(0, secs - int(delta))
	_update_timer_label()
	if secs <= 0:
		call_deferred("_refresh")

var _timer_label: Label

func _update_timer_label() -> void:
	if _timer_label == null or not is_instance_valid(_timer_label):
		return
	var h := secs / 3600
	var mm := (secs % 3600) / 60
	var ss := secs % 60
	_timer_label.text = "🛒 Próxima rotação em %dh %02dm %02ds" % [h, mm, ss]

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	# ── Cabeçalho do mercador ──
	var name_lbl := Label.new()
	name_lbl.text = "🧙 %s" % str(data.get("merchantName", "Mercador"))
	name_lbl.add_theme_font_size_override("font_size", 22)
	name_lbl.modulate = Color(1, 0.9, 0.6)
	content.add_child(name_lbl)
	var quote := str(data.get("merchantQuote", ""))
	if quote != "":
		var q := Label.new(); q.text = "\"%s\"" % quote
		q.modulate = Color(1, 1, 1, 0.6); q.add_theme_font_size_override("font_size", 13)
		q.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		content.add_child(q)
	_timer_label = Label.new(); _timer_label.modulate = Color(0.7, 0.85, 1.0)
	content.add_child(_timer_label)
	_update_timer_label()
	content.add_child(_spacer(8))
	# ── Itens ──
	var items: Array = data.get("items", []) if data.get("items") is Array else []
	content.add_child(_section("Itens (%d)" % items.size()))
	if items.is_empty():
		content.add_child(_dim("— sem itens nesta rotação —"))
	for it in items:
		if it is Dictionary:
			content.add_child(_item_row(it))

func _item_row(it: Dictionary) -> PanelContainer:
	var rarity := int(it.get("rarity", 1))
	var col: Color = RARITY_COL[clampi(rarity - 1, 0, 4)]
	var purchased := bool(it.get("purchased", false))
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1); sb.border_color = Color(col, 0.6)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	if purchased:
		panel.modulate = Color(1, 1, 1, 0.5)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	# esquerda: nome + sub + stats + preço
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
	var price := Label.new(); price.text = "💰 %d" % int(it.get("price", 0))
	price.modulate = Color(1, 0.85, 0.4); price.add_theme_font_size_override("font_size", 13)
	left.add_child(price)
	hb.add_child(left)
	# direita: ação comprar / comprado
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 4)
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var id := int(it.get("id", 0))
	if purchased:
		var done := Label.new(); done.text = "✓ Comprado"; done.modulate = Color(0.5, 0.85, 0.5)
		right.add_child(done)
	else:
		right.add_child(_act("Comprar", _buy.bind(id)))
	hb.add_child(right)
	return panel

# Compra: 1 chamada. Em sucesso marco o item como comprado em memória + re-render;
# em falha não mexo no estado local e mostro o erro.
func _buy(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.shop_buy(id)
	if r.get("ok") and r.get("json") is Dictionary:
		var items: Array = data.get("items", []) if data.get("items") is Array else []
		for it in items:
			if it is Dictionary and int(it.get("id", -1)) == id:
				it["purchased"] = true
		status.text = str(r["json"].get("message", "Comprado!"))
		_render()
	else:
		_show_error(r)
	busy = false

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
