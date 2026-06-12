extends Control
# ── Tela TAVERNA ──────────────────────────────────────────────────────────────────
# [TAVERNA] Beber (1 bronze + minigame de timing → manda success) p/ buff stackável;
# chat (feed + enviar). Espelha loadTavern/renderTavernFeed do app.js. [MIGRACAO_GODOT]
# Endpoints: GET /api/tavern/status · POST /api/tavern/drink {success} ·
#            GET /api/tavern/feed?since=N · POST /api/tavern/chat {text}

signal go_back

var content: VBoxContainer
var status: Label
var busy := false

# estado do buff (último status conhecido)
var st: Dictionary = {}

# chat
var feed_box: VBoxContainer            # onde as linhas do chat aparecem
var chat_scroll: ScrollContainer
var chat_input: LineEdit
var last_id := 0                       # maior id já exibido (p/ ?since=)
var msg_label: Label                   # mensagem efêmera (hit/miss/erro)

# minigame de timing (mesma ideia do app.js: marker viaja, acerta a zona = success)
var mini_active := false
var mini_zone_start := 0.0             # % (0–100)
var mini_zone_width := 22.0
var mini_pos := 0.0
var mini_dir := 1.0
var drink_btn: Button
var mini_panel: PanelContainer
var mini_marker: ColorRect
var mini_zone_rect: ColorRect

const MINI_W := 320.0                  # largura visual da pista (px)
const MINI_H := 22.0

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var bg := ColorRect.new()
	bg.color = Color(0.09, 0.08, 0.11)
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	var root := VBoxContainer.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(root)
	# header: ← voltar + título + ↻
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	var back := Button.new(); back.text = "←"; back.custom_minimum_size = Vector2(44, 36)
	back.pressed.connect(func() -> void: go_back.emit())
	header.add_child(back)
	var ttl := Label.new(); ttl.text = "🍺 Taverna"; ttl.add_theme_font_size_override("font_size", 26)
	ttl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	header.add_child(ttl)
	var sync := Button.new(); sync.text = "↻"; sync.custom_minimum_size = Vector2(40, 36)
	sync.pressed.connect(func() -> void: await _refresh())
	header.add_child(sync)
	var hm := MarginContainer.new()
	for side in ["left", "right", "top"]:
		hm.add_theme_constant_override("margin_" + side, 16)
	hm.add_child(header)
	root.add_child(hm)
	status = Label.new(); status.add_theme_constant_override("margin_left", 16)
	root.add_child(status)
	# corpo rolável
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

func _process(delta: float) -> void:
	if not mini_active:
		return
	# marker vai e volta de 0 a 100 (igual ao setInterval do app.js, mas em px)
	mini_pos += mini_dir * 130.0 * delta
	if mini_pos >= 100.0:
		mini_pos = 100.0; mini_dir = -1.0
	elif mini_pos <= 0.0:
		mini_pos = 0.0; mini_dir = 1.0
	if mini_marker:
		mini_marker.position.x = (mini_pos / 100.0) * (MINI_W - 6.0)

func _refresh() -> void:
	status.text = "Carregando…"
	var r = await Api.tavern_status()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	st = r["json"]
	status.text = ""
	_render()
	await _load_feed(true)

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	mini_marker = null
	mini_zone_rect = null
	# ── Buff atual ──
	content.add_child(_buff_label())
	# ── Minigame (pista) + botão beber ──
	mini_panel = _mini_track()
	content.add_child(mini_panel)
	drink_btn = Button.new()
	drink_btn.text = "🍺 Beber (1 🥉)"
	drink_btn.custom_minimum_size = Vector2(220, 40)
	drink_btn.pressed.connect(func() -> void: await _drink_pressed())
	content.add_child(drink_btn)
	msg_label = Label.new()
	msg_label.custom_minimum_size = Vector2(0, 20)
	content.add_child(msg_label)
	content.add_child(_spacer(6))
	# ── Chat ──
	content.add_child(_section("💬 Chat"))
	var chat_panel := PanelContainer.new()
	var csb := StyleBoxFlat.new()
	csb.bg_color = Color(0.12, 0.11, 0.14)
	csb.set_corner_radius_all(5)
	csb.set_content_margin_all(8)
	chat_panel.add_theme_stylebox_override("panel", csb)
	chat_panel.custom_minimum_size = Vector2(0, 240)
	content.add_child(chat_panel)
	chat_scroll = ScrollContainer.new()
	chat_scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	chat_panel.add_child(chat_scroll)
	feed_box = VBoxContainer.new()
	feed_box.add_theme_constant_override("separation", 3)
	feed_box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	chat_scroll.add_child(feed_box)
	# barra de envio
	var bar := HBoxContainer.new()
	bar.add_theme_constant_override("separation", 6)
	chat_input = LineEdit.new()
	chat_input.placeholder_text = "Diga algo…"
	chat_input.max_length = 200
	chat_input.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	chat_input.text_submitted.connect(func(_t: String) -> void: await _send_pressed())
	bar.add_child(chat_input)
	var send := Button.new(); send.text = "Enviar"; send.custom_minimum_size = Vector2(90, 0)
	send.pressed.connect(func() -> void: await _send_pressed())
	bar.add_child(send)
	content.add_child(bar)

# ── Buff ─────────────────────────────────────────────────────────────────────────
func _buff_label() -> Label:
	var l := Label.new()
	var stacks := int(st.get("stacks", 0))
	if stacks > 0:
		var secs := int(st.get("buffSecondsLeft", 0))
		var pct := float(st.get("buffPct", 0.0))
		l.text = "🍺 +%.2f%% em todos os stats · %d stacks · %d:%02d" % [pct, stacks, secs / 60, secs % 60]
		l.modulate = Color(0.5, 0.82, 0.72)
	else:
		l.text = "Sem buff de bebida ativo."
		l.modulate = Color(1, 1, 1, 0.55)
	l.add_theme_font_size_override("font_size", 16)
	return l

# ── Minigame (pista visual; clicar Beber para no marker e decide success) ─────────
func _mini_track() -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.16, 0.15, 0.18)
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(4)
	panel.add_theme_stylebox_override("panel", sb)
	panel.custom_minimum_size = Vector2(MINI_W + 8.0, MINI_H + 8.0)
	var track := Control.new()
	track.custom_minimum_size = Vector2(MINI_W, MINI_H)
	panel.add_child(track)
	# zona-alvo (verde)
	mini_zone_rect = ColorRect.new()
	mini_zone_rect.color = Color(0.3, 0.7, 0.4, 0.55)
	mini_zone_rect.size = Vector2((mini_zone_width / 100.0) * MINI_W, MINI_H)
	mini_zone_rect.position = Vector2((mini_zone_start / 100.0) * MINI_W, 0)
	mini_zone_rect.visible = mini_active
	track.add_child(mini_zone_rect)
	# marker (laranja)
	mini_marker = ColorRect.new()
	mini_marker.color = Color(1.0, 0.7, 0.2)
	mini_marker.size = Vector2(6, MINI_H)
	mini_marker.position = Vector2((mini_pos / 100.0) * (MINI_W - 6.0), 0)
	mini_marker.visible = mini_active
	track.add_child(mini_marker)
	return panel

func _start_minigame() -> void:
	mini_zone_width = 22.0
	mini_zone_start = randf() * (100.0 - mini_zone_width)
	mini_pos = 0.0
	mini_dir = 1.0
	mini_active = true
	if mini_zone_rect:
		mini_zone_rect.size = Vector2((mini_zone_width / 100.0) * MINI_W, MINI_H)
		mini_zone_rect.position = Vector2((mini_zone_start / 100.0) * MINI_W, 0)
		mini_zone_rect.visible = true
	if mini_marker:
		mini_marker.visible = true
	drink_btn.text = "🍺 Beber AGORA!"

func _drink_pressed() -> void:
	if busy:
		return
	# 1º clique: começa o minigame; 2º clique: para o marker e bebe
	if not mini_active:
		_start_minigame()
		return
	mini_active = false
	var success := mini_pos >= mini_zone_start and mini_pos <= (mini_zone_start + mini_zone_width)
	if mini_zone_rect: mini_zone_rect.visible = false
	if mini_marker: mini_marker.visible = false
	drink_btn.text = "🍺 Beber (1 🥉)"
	busy = true
	var r = await Api.tavern_drink(success)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_flash(_err_text(r), true)
		return
	st = r["json"]
	_flash("🍺 Acertou! +1 stack" if success else "Errou o gole… só o bronze foi.", not success)
	# atualiza só o label do buff (sem re-render do chat p/ não rolar/limpar)
	if content.get_child_count() > 0 and content.get_child(0) is Label:
		(content.get_child(0) as Label).queue_free()
		var nl := _buff_label()
		content.add_child(nl)
		content.move_child(nl, 0)

# ── Chat ─────────────────────────────────────────────────────────────────────────
func _load_feed(replace: bool) -> void:
	var since := 0 if replace else last_id
	var r = await Api.tavern_feed(since)
	if not (r.get("ok") and r.get("json") is Dictionary):
		return
	var msgs = r["json"].get("messages", [])
	if not (msgs is Array):
		return
	if replace and feed_box != null:
		for c in feed_box.get_children():
			c.queue_free()
		last_id = 0
	for m in msgs:
		if not (m is Dictionary):
			continue
		var mid := int(m.get("id", 0))
		if mid > last_id:
			last_id = mid
		if feed_box != null:
			feed_box.add_child(_feed_line(m))
	# rola pro fim
	if chat_scroll != null:
		await get_tree().process_frame
		chat_scroll.scroll_vertical = int(chat_scroll.get_v_scroll_bar().max_value)

func _feed_line(m: Dictionary) -> Label:
	var l := Label.new()
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	l.add_theme_font_size_override("font_size", 13)
	if bool(m.get("system", false)):
		l.text = "📢 %s" % str(m.get("text", ""))
		l.modulate = Color(1.0, 0.82, 0.4)
	else:
		l.text = "%s: %s" % [str(m.get("sender", "?")), str(m.get("text", ""))]
		l.modulate = Color(0.88, 0.88, 0.9)
	return l

func _send_pressed() -> void:
	if busy or chat_input == null:
		return
	var text := chat_input.text.strip_edges()
	if text == "":
		return
	chat_input.text = ""
	busy = true
	var r = await Api.tavern_chat(text)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_flash(_err_text(r), true)
		return
	await _load_feed(false)

# ── helpers ───────────────────────────────────────────────────────────────────────
func _flash(text: String, is_error: bool) -> void:
	if msg_label == null:
		return
	msg_label.text = text
	msg_label.modulate = Color(0.93, 0.32, 0.31) if is_error else Color(0.5, 0.82, 0.72)

func _err_text(r) -> String:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		return str(j.get("message", j.get("error", "Falhou")))
	return "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t
	l.add_theme_font_size_override("font_size", 19)
	l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
