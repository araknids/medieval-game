extends Control
# ── Tela TAVERNA ──────────────────────────────────────────────────────────────────
# [TAVERNA] Beber (1 bronze + minigame de timing → manda success) p/ buff stackável;
# chat (feed + enviar). Espelha loadTavern/renderTavernFeed do app.js. Padrão visual:
# UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]
# Endpoints: GET /api/tavern/status · POST /api/tavern/drink {success} ·
#            GET /api/tavern/feed?since=N · POST /api/tavern/chat {text}
# P1: Timer de 4s re-puxa o feed enquanto a tela está aberta (espelha o polling do web).

signal go_back

const Icons := preload("res://ui/Icons.gd")

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
# [TAVERNA_BUFF_LIVE] countdown VIVO do buff: o status só vem no fetch (timer/stacks ficavam congelados);
# aqui o _process desconta o tempo e ZERA o contador quando o buff expira (era o bug do contador não resetar).
var _buff_lbl: Label
var _buff_secs := 0
var _buff_pct := 0.0
var _buff_stacks := 0
var _buff_acc := 0.0
var _buff_synced := false

# [TAVERNA_CHANCE] o SERVIDOR sorteia o acerto; o front só mostra a CHANCE (barra verde, encolhe com os
# stacks) + a caneca animada (treme no clique → copo VAZIO se acerta / DERRAMADO se erra).
var drink_btn: Button
var _mug: TextureRect                  # caneca animada (drink_tremble/drink_empty/drink_spill)
var _mug_tw: Tween                     # tween atual da caneca (mata antes de tocar outro)
var _chance_fill: ColorRect            # preenchimento verde da barra de chance
var _chance_lbl: Label

const CHANCE_W := 200.0                 # largura da barra de chance (px)
const CHANCE_H := 16.0

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

func _process(delta: float) -> void:
	_tick_buff(delta)   # [TAVERNA_BUFF_LIVE] countdown do buff ao vivo (zera ao expirar)

func _refresh() -> void:
	UiKit.show_loading(self)
	var rs = await Api.batch_get(["/api/tavern/status", "/api/warrior"])
	if not is_instance_valid(self): return   # [TAVERN_FREED] logout liberou a Taverna durante o request
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
	_mug = null; _chance_fill = null; _chance_lbl = null
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	# ── Buff atual (card leve, no espírito do banner do Templo) ──
	var bres := UiKit.card(UiKit.GOLD_SOFT)
	buff_box = bres[1]
	_fill_buff(buff_box)
	content.add_child(bres[0])
	# ── Beber (caneca + barra de chance + botão ao lado) ──
	content.add_child(UiKit.section("Beber"))
	content.add_child(UiKit.dim("Beba pra ganhar +1 stack de buff (cobra 1 🥉). Quanto mais stacks, mais difícil o gole."))
	_build_drink_row()
	msg_label = Label.new()
	msg_label.custom_minimum_size = Vector2(0, 20)
	msg_label.add_theme_font_size_override("font_size", 13)
	content.add_child(msg_label)
	# ── Chat ──
	content.add_child(UiKit.section("💬 Chat"))
	var cres := UiKit.card()
	var cbox: VBoxContainer = cres[1]
	cres[0].custom_minimum_size = Vector2(0, 200)   # [SEM_SCROLL] chat com rolagem própria → reserva menos altura
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
		# [TAVERNA_BUFF_LIVE] guarda o estado p/ o _tick_buff descontar ao vivo e zerar ao expirar
		_buff_lbl = l; _buff_secs = secs; _buff_pct = pct; _buff_stacks = stacks; _buff_acc = 0.0; _buff_synced = false
	else:
		l.text = "Sem buff de bebida ativo."
		l.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		_buff_lbl = null; _buff_secs = 0
	box.add_child(l)

# [TAVERNA_BUFF_LIVE] desconta 1s por vez; ao chegar a 0, ZERA o contador (mostra "sem buff") e
# re-sincroniza 1x (some o badge 🍺 do topbar). Antes o timer/stacks ficavam congelados no último fetch.
func _tick_buff(delta: float) -> void:
	if _buff_lbl == null or not is_instance_valid(_buff_lbl) or _buff_secs <= 0:
		return
	_buff_acc += delta
	if _buff_acc < 1.0:
		return
	_buff_acc -= 1.0
	_buff_secs -= 1
	if _buff_secs <= 0:
		_buff_secs = 0
		_buff_lbl.text = "Sem buff de bebida ativo."
		_buff_lbl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		if not _buff_synced:
			_buff_synced = true
			_refresh()   # re-sincroniza estado + topbar (o badge 🍺 some)
	else:
		_buff_lbl.text = Lang.t("🍺 +%.2f%% em todos os stats · %d stacks · %d:%02d") % [_buff_pct, _buff_stacks, _buff_secs / 60, _buff_secs % 60]

# ── Beber: caneca + barra de chance + botão (linha compacta, botão ao LADO) ───────
func _build_drink_row() -> void:
	var res := UiKit.card()
	var holder: VBoxContainer = res[1]
	res[0].size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 16); row.alignment = BoxContainer.ALIGNMENT_CENTER
	holder.add_child(row)
	# caneca animada (estática até clicar; treme no gole → vazio/derramado no resultado)
	_mug = TextureRect.new()
	_mug.custom_minimum_size = Vector2(72, 72)
	_mug.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_mug.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	_mug.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	_mug.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	_mug.texture = Icons.tex("drink_mug")
	row.add_child(_mug)
	# barra de CHANCE (verde; encolhe conforme os stacks sobem — vem do servidor)
	var cbox := VBoxContainer.new(); cbox.add_theme_constant_override("separation", 3); cbox.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	_chance_lbl = Label.new(); _chance_lbl.add_theme_font_size_override("font_size", 12); _chance_lbl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	cbox.add_child(_chance_lbl)
	var bar_bg := PanelContainer.new()
	var sb := StyleBoxFlat.new(); sb.bg_color = Color(0.07, 0.06, 0.09); sb.set_border_width_all(1); sb.border_color = Color(0.40, 0.32, 0.20, 0.6); sb.set_corner_radius_all(3)
	bar_bg.add_theme_stylebox_override("panel", sb)
	bar_bg.custom_minimum_size = Vector2(CHANCE_W, CHANCE_H)
	var track := Control.new(); track.custom_minimum_size = Vector2(CHANCE_W, CHANCE_H)
	bar_bg.add_child(track)
	_chance_fill = ColorRect.new(); _chance_fill.color = Color(0.30, 0.70, 0.40); _chance_fill.position = Vector2(0, 0); _chance_fill.size = Vector2(CHANCE_W, CHANCE_H)
	track.add_child(_chance_fill)
	cbox.add_child(bar_bg)
	row.add_child(cbox)
	# botão MENOR ao lado (não expande)
	drink_btn = UiKit.action("🍺 Beber (1 🥉)", func() -> void: await _drink_pressed())
	drink_btn.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	drink_btn.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(drink_btn)
	content.add_child(res[0])
	_update_chance_bar()

# Atualiza a barra verde (largura = chance do próximo gole, do status do servidor).
func _update_chance_bar() -> void:
	var ch := clampf(float(st.get("drinkChance", 0.9)), 0.0, 1.0)
	if _chance_fill != null and is_instance_valid(_chance_fill):
		_chance_fill.size = Vector2(CHANCE_W * ch, CHANCE_H)
	if _chance_lbl != null and is_instance_valid(_chance_lbl):
		_chance_lbl.text = Lang.t("Chance do gole: %d%%") % int(round(ch * 100.0))

# Toca uma animação da caneca (mata o tween anterior). loop=true cicla; false roda 1x e segura no fim.
func _mug_play(key: String, loop: bool) -> void:
	if _mug == null or not is_instance_valid(_mug):
		return
	if _mug_tw != null and _mug_tw.is_valid():
		_mug_tw.kill()
	if Icons.frames(key).is_empty():
		_mug.texture = Icons.tex("drink_mug")   # fallback estático (frames não importados)
		return
	_mug_tw = Icons.play_loop(_mug, key, 0.12) if loop else Icons.play_once(_mug, key, 0.13)

# Beber: clica → caneca TREME enquanto o servidor SORTEIA → copo VAZIO (acerto) / DERRAMADO (erro).
func _drink_pressed() -> void:
	if busy:
		return
	busy = true
	if is_instance_valid(drink_btn): drink_btn.disabled = true
	_mug_play("drink_tremble", true)        # treme (anticipação)
	var r = await Api.tavern_drink(true)    # success é IGNORADO pelo backend (ele sorteia)
	await get_tree().create_timer(0.45).timeout   # deixa o tremor aparecer antes do resultado
	if not is_instance_valid(self): return   # [TAVERN_FREED] logout liberou a Taverna durante o request
	busy = false
	if is_instance_valid(drink_btn): drink_btn.disabled = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_mug_play("drink_mug", false)
		_flash(UiKit.err_text(r), true)
		return
	st = r["json"]
	var success := bool(st.get("success", false))
	_mug_play("drink_empty" if success else "drink_spill", false)   # copo vazio / derramado
	_flash("🍺 Acertou! +1 stack" if success else "Entornou a cerveja… só o bronze foi.", not success)
	if buff_box != null:
		_fill_buff(buff_box)
	_update_chance_bar()
	# [TOPBAR_BUFFS] empurra o warrior FRESCO pro topbar → o badge do buff aparece/atualiza na hora
	var wr = await Api.get_warrior()
	if not is_instance_valid(self): return   # [TAVERN_FREED]
	if wr.get("ok") and wr.get("json") is Dictionary:
		warrior = wr["json"]
		UiKit.set_wallet(wallet, warrior)

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

# ── helper local (mensagem efêmera do hit/miss, abaixo do botão) ───────────────────
func _flash(text: String, is_error: bool) -> void:
	if msg_label == null:
		return
	msg_label.text = text
	msg_label.add_theme_color_override("font_color", UiKit.ERR if is_error else UiKit.OK)
