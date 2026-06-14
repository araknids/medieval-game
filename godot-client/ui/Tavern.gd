extends Control
# ── Tela TAVERNA ──────────────────────────────────────────────────────────────────
# [TAVERNA] Beber (1 bronze + minigame de timing → manda success) p/ buff stackável;
# chat (feed + enviar). Espelha loadTavern/renderTavernFeed do app.js. Padrão visual:
# UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]
# Endpoints: GET /api/tavern/status · POST /api/tavern/drink {success} ·
#            GET /api/tavern/feed?since=N · POST /api/tavern/chat {text}
# P1: Timer de 4s re-puxa o feed enquanto a tela está aberta (espelha o polling do web).

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false

# estado do buff (último status conhecido) + carteira do header
var st: Dictionary = {}
var warrior: Dictionary = {}

# chat
var feed_box: VBoxContainer            # onde as linhas do chat aparecem
var chat_scroll: ScrollContainer
var chat_input: LineEdit
var last_id := 0                       # maior id já exibido (p/ ?since=)
var msg_label: Label                   # mensagem efêmera (hit/miss/erro)
var buff_box: VBoxContainer            # card do buff (atualizado sem re-render do chat)
var poll_timer: Timer                  # polling do feed (~4s)

# minigame de timing (mesma ideia do app.js: marker viaja, acerta a zona = success)
var mini_active := false
var mini_zone_start := 0.0             # % (0–100)
var mini_zone_width := 22.0
var mini_pos := 0.0
var mini_dir := 1.0
var drink_btn: Button
var mini_marker: ColorRect
var mini_zone_rect: ColorRect

const MINI_W := 320.0                  # largura visual da pista (px)
const MINI_H := 22.0

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🍺 Taverna", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	# Timer de polling do feed (~4s) — morre junto com a tela.
	poll_timer = Timer.new()
	poll_timer.wait_time = 4.0
	poll_timer.autostart = true
	poll_timer.timeout.connect(func() -> void: await _poll_feed())
	add_child(poll_timer)
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
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/tavern/status", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	st = r["json"]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()
	await _load_feed(true)

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	mini_marker = null
	mini_zone_rect = null
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	# ── Buff atual (card leve, no espírito do banner do Templo) ──
	var bres := UiKit.card(UiKit.GOLD_SOFT)
	buff_box = bres[1]
	_fill_buff(buff_box)
	content.add_child(bres[0])
	# ── Beber (minigame + botão) ──
	content.add_child(UiKit.section("Beber"))
	content.add_child(UiKit.dim("Acerte o tempo no gole para ganhar +1 stack de buff. Cobra 1🥉 sempre."))
	mini_panel_holder()
	drink_btn = UiKit.action_big("🍺 Beber (1 🥉)", func() -> void: await _drink_pressed())
	content.add_child(drink_btn)
	msg_label = Label.new()
	msg_label.custom_minimum_size = Vector2(0, 20)
	msg_label.add_theme_font_size_override("font_size", 13)
	content.add_child(msg_label)
	# ── Chat ──
	content.add_child(UiKit.section("💬 Chat"))
	var cres := UiKit.card()
	var cbox: VBoxContainer = cres[1]
	cres[0].custom_minimum_size = Vector2(0, 240)
	chat_scroll = ScrollContainer.new()
	chat_scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	chat_scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	cbox.add_child(chat_scroll)
	feed_box = VBoxContainer.new()
	feed_box.add_theme_constant_override("separation", 3)
	feed_box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	chat_scroll.add_child(feed_box)
	content.add_child(cres[0])
	# barra de envio
	var bar := HBoxContainer.new()
	bar.add_theme_constant_override("separation", 6)
	chat_input = UiKit.input("Diga algo…")
	chat_input.max_length = 200
	chat_input.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	chat_input.text_submitted.connect(func(_t: String) -> void: await _send_pressed())
	bar.add_child(chat_input)
	bar.add_child(UiKit.action("Enviar", func() -> void: await _send_pressed()))
	content.add_child(bar)

# ── Buff ─────────────────────────────────────────────────────────────────────────
func _fill_buff(box: VBoxContainer) -> void:
	for c in box.get_children():
		c.queue_free()
	var stacks := int(st.get("stacks", 0))
	var l := Label.new()
	l.add_theme_font_size_override("font_size", 16)
	if stacks > 0:
		var secs := int(st.get("buffSecondsLeft", 0))
		var pct := float(st.get("buffPct", 0.0))
		l.text = Lang.t("🍺 +%.2f%% em todos os stats · %d stacks · %d:%02d") % [pct, stacks, secs / 60, secs % 60]
		l.add_theme_color_override("font_color", UiKit.GOLD)
	else:
		l.text = "Sem buff de bebida ativo."
		l.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	box.add_child(l)

# ── Minigame (pista visual; clicar Beber para no marker e decide success) ─────────
func mini_panel_holder() -> void:
	var res := UiKit.card()
	var holder: VBoxContainer = res[1]
	res[0].custom_minimum_size = Vector2(MINI_W + 24.0, MINI_H + 24.0)
	res[0].size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	var track := Control.new()
	track.custom_minimum_size = Vector2(MINI_W, MINI_H)
	holder.add_child(track)
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
	content.add_child(res[0])

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
		_flash(UiKit.err_text(r), true)
		return
	st = r["json"]
	_flash("🍺 Acertou! +1 stack" if success else "Errou o gole… só o bronze foi.", not success)
	# atualiza só o card do buff (sem re-render do chat p/ não rolar/limpar)
	if buff_box != null:
		_fill_buff(buff_box)

# ── Chat ─────────────────────────────────────────────────────────────────────────
# Polling: re-puxa o feed incremental enquanto a tela está aberta (não durante outra ação).
func _poll_feed() -> void:
	if busy or feed_box == null:
		return
	await _load_feed(false)

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
	var added := false
	for m in msgs:
		if not (m is Dictionary):
			continue
		var mid := int(m.get("id", 0))
		if mid > last_id:
			last_id = mid
		if feed_box != null:
			feed_box.add_child(_feed_line(m))
			added = true
	# rola pro fim só quando há novidade (não interrompe o scroll do usuário à toa)
	if chat_scroll != null and (replace or added):
		await get_tree().process_frame
		chat_scroll.scroll_vertical = int(chat_scroll.get_v_scroll_bar().max_value)

# Renderiza como Label de texto puro (sem BBCode/RichText) → sem injeção de markup.
func _feed_line(m: Dictionary) -> Label:
	var l := Label.new()
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	l.add_theme_font_size_override("font_size", 13)
	if bool(m.get("system", false)):
		l.text = "📢 %s" % str(m.get("text", ""))
		l.add_theme_color_override("font_color", UiKit.GOLD)
	else:
		l.text = "%s: %s" % [str(m.get("sender", "?")), str(m.get("text", ""))]
		l.add_theme_color_override("font_color", UiKit.TEXT)
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
		_flash(UiKit.err_text(r), true)
		return
	await _load_feed(false)

# ── helper local (mensagem efêmera do hit/miss, abaixo do botão) ───────────────────
func _flash(text: String, is_error: bool) -> void:
	if msg_label == null:
		return
	msg_label.text = text
	msg_label.add_theme_color_override("font_color", UiKit.ERR if is_error else UiKit.OK)
