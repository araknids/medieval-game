extends Control
# ── Tela TAVERNA ──────────────────────────────────────────────────────────────────
# [TAVERNA] Chat entre players (feed + enviar) + avisos globais. Padrão visual:
# UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]
# Endpoints: GET /api/tavern/feed?since=N · POST /api/tavern/chat {text}
# P1: Timer de 4s re-puxa o feed enquanto a tela está aberta (espelha o polling do web).

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false

# carteira do header (do /api/warrior)
var warrior: Dictionary = {}

# chat
var feed_box: VBoxContainer            # onde as linhas do chat aparecem
var chat_scroll: ScrollContainer
var chat_input: LineEdit
var last_id := 0                       # maior id já exibido (p/ ?since=)
var msg_label: Label                   # mensagem efêmera (erro de envio)
var poll_timer: Timer                  # polling do feed (~4s)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🍺 Taverna", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	# Timer de polling do feed (~4s).
	poll_timer = Timer.new()
	poll_timer.wait_time = 4.0
	poll_timer.autostart = true
	poll_timer.timeout.connect(func() -> void: await _poll_feed())
	add_child(poll_timer)
	# [REDE] O Shell mantém a tela VIVA em cache (alterna visibilidade, não recria), então sem isto o
	# timer continuaria pingando /api/tavern/feed a cada 4s pra SEMPRE, mesmo fora da Taverna (~900
	# chamadas/h à toa → carga e custo no backend). Pausa o polling quando a Taverna não está visível.
	visibility_changed.connect(_on_tavern_visibility)
	await _refresh()

# [REDE] Pausa/retoma o polling do feed conforme a Taverna está visível (economiza chamadas + custo).
func _on_tavern_visibility() -> void:
	if not is_instance_valid(poll_timer):
		return
	if is_visible_in_tree():
		if poll_timer.is_stopped():
			poll_timer.start()
		await _poll_feed()              # atualiza na hora ao reabrir
	else:
		poll_timer.stop()

func _refresh() -> void:
	UiKit.show_loading(self)
	var wr = await Api.get_warrior()
	if not is_instance_valid(self): return   # [TAVERN_FREED] logout liberou a Taverna durante o request
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()
	await _load_feed(true)

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	# ── Chat ──
	content.add_child(UiKit.section("💬 Chat"))
	var cres := UiKit.card()
	var cbox: VBoxContainer = cres[1]
	cres[0].custom_minimum_size = Vector2(0, 280)   # [SEM_SCROLL] chat com rolagem própria
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
	msg_label = Label.new()
	msg_label.custom_minimum_size = Vector2(0, 20)
	msg_label.add_theme_font_size_override("font_size", 13)
	content.add_child(msg_label)

# ── Chat ─────────────────────────────────────────────────────────────────────────
# Polling: re-puxa o feed incremental enquanto a tela está aberta (não durante outra ação).
func _poll_feed() -> void:
	if busy or feed_box == null:
		return
	await _load_feed(false)

func _load_feed(replace: bool) -> void:
	var since := 0 if replace else last_id
	var r = await Api.tavern_feed(since)
	if not is_instance_valid(self): return   # [TAVERN_FREED] logout liberou a Taverna durante o poll de 4s
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
	if not is_instance_valid(self): return   # [TAVERN_FREED] logout liberou a Taverna durante o request
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_flash(UiKit.err_text(r), true)
		return
	await _load_feed(false)

# ── helper local (mensagem efêmera de erro de envio) ───────────────────────────────
func _flash(text: String, is_error: bool) -> void:
	if msg_label == null:
		return
	msg_label.text = text
	msg_label.add_theme_color_override("font_color", UiKit.ERR if is_error else UiKit.OK)
