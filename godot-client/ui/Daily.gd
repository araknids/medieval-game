extends Control
# ── Tela RECOMPENSA DIÁRIA ([DAILY]) ───────────────────────────────────────────────
# Lê GET /api/daily-reward/status (ciclo de 7 dias de peixe de stamina + streak) e mostra
# o calendário; reivindica com POST /api/daily-reward/claim. Espelha loadDailyReward /
# renderDailyCalendar / claimDailyReward do app.js. Volta pro Hub (sinal go_back). [MIGRACAO_GODOT]

signal go_back

var content: VBoxContainer
var status: Label
var data: Dictionary = {}
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
	var ttl := Label.new(); ttl.text = "🎁 Recompensa Diária"; ttl.add_theme_font_size_override("font_size", 26)
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
	# conteúdo rolável
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
	content.add_theme_constant_override("separation", 8)
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	inner.add_child(content)
	await _refresh()

func _refresh() -> void:
	status.text = "Carregando…"
	var r = await Api.daily_status()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	data = r["json"]
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	var streak := int(data.get("streak", 0))
	var claim_day := int(data.get("claimDay", 1))
	var can_claim := bool(data.get("canClaim", false))
	# streak
	var streak_lbl := Label.new()
	streak_lbl.text = "🔥 Sequência: %d" % streak
	streak_lbl.add_theme_font_size_override("font_size", 18)
	streak_lbl.modulate = Color(0.79, 0.66, 0.3)
	content.add_child(streak_lbl)
	content.add_child(_spacer(4))
	# calendário (grid de 7 dias)
	var grid := GridContainer.new()
	grid.columns = 4
	grid.add_theme_constant_override("h_separation", 8)
	grid.add_theme_constant_override("v_separation", 8)
	content.add_child(grid)
	var days: Array = data.get("days", []) if data.get("days") is Array else []
	for d in days:
		if d is Dictionary:
			grid.add_child(_day_card(d, claim_day, can_claim))
	content.add_child(_spacer(12))
	# botão de reivindicar / aviso
	if can_claim:
		var btn := Button.new()
		btn.text = "🎁 Reivindicar"
		btn.custom_minimum_size = Vector2(180, 44)
		btn.pressed.connect(func() -> void: await _claim())
		content.add_child(btn)
	else:
		content.add_child(_dim("Volte amanhã para a próxima recompensa."))

func _day_card(d: Dictionary, claim_day: int, can_claim: bool) -> PanelContainer:
	var day := int(d.get("day", 0))
	var is_today := day == claim_day
	var panel := PanelContainer.new()
	panel.custom_minimum_size = Vector2(96, 0)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.063, 0.14, 0.165) if is_today else Color(0.086, 0.086, 0.133)
	sb.set_border_width_all(1)
	sb.border_color = Color(0.3, 0.82, 0.88) if is_today else Color(0.23, 0.23, 0.29)
	sb.set_corner_radius_all(8)
	sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 2)
	panel.add_child(box)
	var dlbl := Label.new(); dlbl.text = "Dia %d" % day
	dlbl.add_theme_font_size_override("font_size", 12); dlbl.modulate = Color(0.53, 0.53, 0.53)
	dlbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(dlbl)
	var fish := Label.new(); fish.text = "🐟"
	fish.add_theme_font_size_override("font_size", 22)
	fish.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(fish)
	var nm := Label.new()
	nm.text = "%s ×%d" % [str(d.get("fishName", "?")), int(d.get("qty", 0))]
	nm.add_theme_font_size_override("font_size", 12); nm.modulate = Color(0.8, 0.93, 1.0)
	nm.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	nm.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	box.add_child(nm)
	var bronze := int(d.get("bronze", 0))
	if bronze > 0:
		var bl := Label.new(); bl.text = "+%d 🪙" % bronze
		bl.add_theme_font_size_override("font_size", 11); bl.modulate = Color(0.8, 0.5, 0.2)
		bl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		box.add_child(bl)
	if is_today:
		var tag := Label.new()
		tag.text = "◀ hoje" if can_claim else "✓"
		tag.add_theme_font_size_override("font_size", 11); tag.modulate = Color(0.3, 0.82, 0.88)
		tag.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		box.add_child(tag)
	return panel

func _claim() -> void:
	if busy: return
	busy = true
	var r = await Api.daily_claim()
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		# espelha o showCollectModal do app.js, em texto
		var parts: Array = ["🐟 %s ×%d" % [str(j.get("fishName", "?")), int(j.get("qty", 0))]]
		if int(j.get("bronze", 0)) > 0:
			parts.append("🪙 %d Bronze" % int(j.get("bronze", 0)))
		if int(j.get("mailed", 0)) > 0:
			parts.append("📬 %d por correio (mochila cheia)" % int(j.get("mailed", 0)))
		status.text = "🎁 Recebido! 🔥 %d   —   %s" % [int(j.get("streak", 0)), "   ".join(parts)]
		await _refresh()
	else:
		_show_error(r)

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4)
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
