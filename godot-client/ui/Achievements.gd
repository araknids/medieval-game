extends Control
# ── Tela CONQUISTAS / TÍTULOS ─────────────────────────────────────────────────────
# Lê GET /api/achievements (catálogo + título ativo), lista desbloqueadas/bloqueadas
# por categoria e deixa escolher o título ativo (POST /api/achievements/title). [TITULOS]
# Volta pro Hub (sinal go_back). [MIGRACAO_GODOT]

signal go_back

var data: Dictionary = {}        # {activeTitle, achievements:[...]} vindo do backend
var content: VBoxContainer
var status: Label
var busy := false

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
	var ttl := Label.new(); ttl.text = "Conquistas"; ttl.add_theme_font_size_override("font_size", 26)
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
	var r = await Api.get_achievements()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	data = r["json"]
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	var all: Array = data.get("achievements", []) if data.get("achievements") is Array else []
	var active := str(data.get("activeTitle", ""))
	var unlocked: Array = []
	for a in all:
		if a is Dictionary and bool(a.get("unlocked", false)):
			unlocked.append(a)

	# ── Cabeçalho: contagem + dica ──
	content.add_child(_section("🏆 Conquistas & Títulos   (%d/%d)" % [unlocked.size(), all.size()]))
	var hint := Label.new()
	hint.text = "👑 Título ativo — escolha um para exibir antes do seu nome (todos veem):"
	hint.modulate = Color(0.79, 0.66, 0.3)
	hint.add_theme_font_size_override("font_size", 13)
	hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	content.add_child(hint)

	# ── Picker de título (None + cada desbloqueado) ──
	var picker := HBoxContainer.new()
	picker.add_theme_constant_override("separation", 6)
	content.add_child(picker)
	picker.add_child(_title_btn("Nenhum", "", active == ""))
	for a in unlocked:
		var t := str(a.get("title", ""))
		picker.add_child(_title_btn(t, str(a.get("id", "")), t == active))
	if unlocked.is_empty():
		content.add_child(_dim("Desbloqueie conquistas abaixo para ganhar títulos."))

	# ── Catálogo por categoria ──
	var cats: Array = []                  # mantém ordem de aparição
	var by_cat: Dictionary = {}
	for a in all:
		if not (a is Dictionary): continue
		var cat := str(a.get("category", "—"))
		if not by_cat.has(cat):
			by_cat[cat] = []
			cats.append(cat)
		by_cat[cat].append(a)
	for cat in cats:
		content.add_child(_spacer(6))
		var ch := Label.new(); ch.text = cat.to_upper()
		ch.modulate = Color(0.6, 0.67, 0.53); ch.add_theme_font_size_override("font_size", 12)
		content.add_child(ch)
		for a in by_cat[cat]:
			content.add_child(_ach_row(a))

# ── linha de conquista ──────────────────────────────────────────────────────────
func _ach_row(a: Dictionary) -> PanelContainer:
	var unlocked := bool(a.get("unlocked", false))
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1)
	sb.border_color = Color(0.79, 0.66, 0.3, 0.6) if unlocked else Color(0.3, 0.3, 0.34, 0.6)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	panel.modulate = Color(1, 1, 1, 1.0 if unlocked else 0.55)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	var icon := Label.new(); icon.text = "🏆" if unlocked else "🔒"
	icon.custom_minimum_size = Vector2(28, 0)
	hb.add_child(icon)
	# meio: nome + título + descrição
	var mid := VBoxContainer.new(); mid.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	nm.text = "%s  “%s”" % [str(a.get("displayName", "?")), str(a.get("title", ""))]
	nm.add_theme_font_size_override("font_size", 15)
	nm.modulate = Color(0.95, 0.95, 0.95) if unlocked else Color(0.8, 0.8, 0.8)
	mid.add_child(nm)
	var desc := Label.new()
	desc.text = str(a.get("description", ""))
	desc.modulate = Color(1, 1, 1, 0.5); desc.add_theme_font_size_override("font_size", 12)
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	mid.add_child(desc)
	hb.add_child(mid)
	# direita: progresso / ✓
	var val := Label.new()
	if unlocked:
		val.text = "✓"; val.modulate = Color(1.0, 0.84, 0.0)
	else:
		val.text = "%d/%d" % [int(a.get("current", 0)), int(a.get("threshold", 0))]
		val.modulate = Color(1, 1, 1, 0.4)
	val.custom_minimum_size = Vector2(70, 0)
	val.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	hb.add_child(val)
	return panel

# ── ação: escolher título ──────────────────────────────────────────────────────
func _title_btn(label: String, id: String, on: bool) -> Button:
	var b := Button.new(); b.text = label
	b.toggle_mode = true; b.button_pressed = on
	b.add_theme_font_size_override("font_size", 12)
	if on:
		b.modulate = Color(1.0, 0.84, 0.0)
	b.pressed.connect(_select_title.bind(id))
	return b

func _select_title(id: String) -> void:
	if busy: return
	busy = true
	# body: {id} — string vazia limpa o título (backend trata blank/"none" como limpar)
	var r = await Api.select_title(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		data["activeTitle"] = str(r["json"].get("activeTitle", ""))
		status.text = "Título atualizado."
		_render()
	else:
		_show_error(r)

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4); l.add_theme_font_size_override("font_size", 12)
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
