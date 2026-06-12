extends Control
# ── Tela CORREIO / MAIL ────────────────────────────────────────────────────────────
# Lê GET /api/mail/inbox (cartas + não-lidas). Abre uma carta (POST /api/mail/{id}/read),
# reivindica ouro/item/recurso anexado e deleta. Espelha loadMail/renderMailPanel/mailOpen
# do app.js. Volta pro Hub (sinal go_back). [MIGRACAO_GODOT]

signal go_back

var content: VBoxContainer
var status: Label
var busy := false
var letters: Array = []         # cache local da inbox
var unread := 0
var opened_id := -1             # carta aberta no momento (-1 = nenhuma)
var opened: Dictionary = {}     # dados completos da carta aberta (resposta do /read)

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
	var ttl := Label.new(); ttl.text = "Correio"; ttl.add_theme_font_size_override("font_size", 26)
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
	var r = await Api.mail_inbox()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	var data: Dictionary = r["json"]
	letters = data.get("letters", []) if data.get("letters") is Array else []
	unread = int(data.get("unread", 0))
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	var head := _section("📥 Caixa de entrada%s" % ("  (%d não-lidas)" % unread if unread > 0 else ""))
	content.add_child(head)
	if letters.is_empty():
		content.add_child(_dim("— nenhuma carta —"))
		return
	for letter in letters:
		if letter is Dictionary:
			content.add_child(_letter_row(letter))
			if int(letter.get("id", -1)) == opened_id:
				content.add_child(_open_panel())

# ── linha da carta na lista (clicável p/ abrir) ──
func _letter_row(m: Dictionary) -> PanelContainer:
	var is_read := bool(m.get("isRead", false))
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.10, 0.10, 0.18)
	sb.set_border_width_all(1)
	sb.border_color = Color(0.36, 0.42, 0.75) if not is_read else Color(0.2, 0.2, 0.2)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	# esquerda: remetente + flags + prévia da mensagem
	var left := VBoxContainer.new(); left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var from := Label.new(); from.text = str(m.get("from", "?"))
	from.add_theme_font_size_override("font_size", 16)
	from.modulate = Color(1, 1, 1) if not is_read else Color(0.8, 0.8, 0.8)
	top.add_child(from)
	if not is_read:
		var nw := Label.new(); nw.text = "● NOVA"; nw.add_theme_font_size_override("font_size", 12); nw.modulate = Color(0.36, 0.42, 0.75)
		top.add_child(nw)
	for flag in _flags(m):
		top.add_child(flag)
	left.add_child(top)
	var msg := str(m.get("message", ""))
	if msg.length() > 60:
		msg = msg.substr(0, 60) + "…"
	var prev := Label.new(); prev.text = msg; prev.add_theme_font_size_override("font_size", 12); prev.modulate = Color(1, 1, 1, 0.5)
	left.add_child(prev)
	hb.add_child(left)
	# direita: data + botão abrir
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 4)
	var date := Label.new(); date.text = str(m.get("sentAt", "")).substr(0, 10)
	date.add_theme_font_size_override("font_size", 11); date.modulate = Color(1, 1, 1, 0.4)
	right.add_child(date)
	var id := int(m.get("id", 0))
	var open_btn := Button.new()
	open_btn.text = "Fechar" if id == opened_id else "Abrir"
	open_btn.custom_minimum_size = Vector2(90, 0)
	open_btn.pressed.connect(_toggle_open.bind(id))
	right.add_child(open_btn)
	hb.add_child(right)
	return panel

# selos de anexo na linha (💰 ouro / 📦 item / 🐟 recurso / ⏰ expirado)
func _flags(m: Dictionary) -> Array:
	var out: Array = []
	var expired := bool(m.get("isExpired", false))
	if int(m.get("goldAmount", 0)) > 0 and not bool(m.get("isCollected", false)):
		out.append(_tag("💰 %d" % int(m.get("goldAmount", 0)), Color(1.0, 0.84, 0.0)))
	if bool(m.get("hasItem", false)) and not bool(m.get("itemCollected", false)) and not expired:
		out.append(_tag("📦 ITEM", Color(0.65, 0.55, 0.98)))
	if bool(m.get("hasResource", false)) and not expired:
		out.append(_tag("🐟 %d×" % int(m.get("resourceQty", 0)), Color(0.3, 0.82, 0.88)))
	if (bool(m.get("hasItem", false)) or bool(m.get("hasResource", false))) and expired:
		out.append(_tag("⏰ EXPIRADO", Color(0.94, 0.33, 0.31)))
	return out

# ── painel da carta aberta (resposta do /read) ──
func _open_panel() -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.10, 0.10, 0.18)
	sb.set_border_width_all(1); sb.border_color = Color(0.27, 0.27, 0.27)
	sb.set_corner_radius_all(6); sb.set_content_margin_all(12)
	panel.add_theme_stylebox_override("panel", sb)
	var vb := VBoxContainer.new(); vb.add_theme_constant_override("separation", 6)
	panel.add_child(vb)
	var r := opened
	# topo: remetente + deletar
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var from := Label.new(); from.text = "De: %s" % str(r.get("from", "?"))
	from.add_theme_font_size_override("font_size", 16); from.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(from)
	var del := Button.new(); del.text = "🗑 Deletar"; del.custom_minimum_size = Vector2(110, 0)
	del.pressed.connect(_delete.bind(opened_id))
	top.add_child(del)
	vb.add_child(top)
	var date := Label.new(); date.text = str(r.get("sentAt", "")).substr(0, 16).replace("T", " ")
	date.add_theme_font_size_override("font_size", 11); date.modulate = Color(1, 1, 1, 0.5)
	vb.add_child(date)
	# corpo da mensagem
	var body := Label.new(); body.text = str(r.get("message", ""))
	body.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	body.add_theme_font_size_override("font_size", 13)
	vb.add_child(body)
	# anexo: OURO
	if bool(r.get("hasGold", false)):
		vb.add_child(_act("💰 Coletar %d ouro" % int(r.get("goldAmount", 0)), _collect_gold.bind(opened_id)))
	elif int(r.get("goldAmount", 0)) > 0:
		vb.add_child(_dim("💰 %d ouro (já coletado)" % int(r.get("goldAmount", 0))))
	# anexo: ITEM
	if bool(r.get("hasItem", false)):
		if bool(r.get("isExpired", false)):
			vb.add_child(_dim("⏰ Este item expirou e foi perdido."))
		elif bool(r.get("itemCollected", false)):
			vb.add_child(_dim("📦 %s (já reivindicado)" % str(r.get("itemName", ""))))
		else:
			var lbl := Label.new(); lbl.text = "📦 %s" % str(r.get("itemName", "")); lbl.modulate = Color(0.65, 0.55, 0.98)
			vb.add_child(lbl)
			vb.add_child(_act("📦 Adicionar à mochila", _claim_item.bind(opened_id)))
	# anexo: RECURSO ([DAILY])
	if bool(r.get("hasResource", false)) and not bool(r.get("isExpired", false)):
		var rname := str(r.get("resourceName", "")) if str(r.get("resourceName", "")) != "" else str(r.get("resourceType", ""))
		var lbl := Label.new(); lbl.text = "🐟 %s ×%d" % [rname, int(r.get("resourceQty", 0))]; lbl.modulate = Color(0.5, 0.82, 0.88)
		vb.add_child(lbl)
		vb.add_child(_act("📦 Adicionar à mochila", _claim_resource.bind(opened_id)))
	return panel

# ── ações (1 chamada; em sucesso re-sincroniza a inbox) ──
func _toggle_open(id: int) -> void:
	if busy: return
	if id == opened_id:
		opened_id = -1; opened = {}; _render(); return
	busy = true
	var r = await Api.mail_read(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		opened = r["json"]
		opened_id = id
		# marca a carta como lida no cache local (o /read marcou no servidor)
		for letter in letters:
			if letter is Dictionary and int(letter.get("id", -1)) == id:
				letter["isRead"] = true
		unread = max(0, unread - 1)   # estimativa; o próximo _refresh corrige
		_render()
	else:
		_show_error(r)

func _collect_gold(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_collect_gold(id)
	busy = false
	if r.get("ok"):
		status.text = str(r["json"].get("message", "Ouro coletado!")) if r.get("json") is Dictionary else "Ouro coletado!"
		await _refresh()
	else:
		_show_error(r)

func _claim_item(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_claim_item(id)
	busy = false
	if r.get("ok"):
		status.text = str(r["json"].get("message", "Item adicionado!")) if r.get("json") is Dictionary else "Item adicionado!"
		await _refresh()
	else:
		_show_error(r)

func _claim_resource(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_claim_resource(id)
	busy = false
	if r.get("ok"):
		status.text = str(r["json"].get("message", "Recurso adicionado!")) if r.get("json") is Dictionary else "Recurso adicionado!"
		await _refresh()
	else:
		_show_error(r)

func _delete(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_delete(id)
	busy = false
	if r.get("ok"):
		opened_id = -1; opened = {}
		letters = letters.filter(func(it): return not (it is Dictionary) or int(it.get("id", -1)) != id)
		status.text = str(r["json"].get("message", "Carta deletada.")) if r.get("json") is Dictionary else "Carta deletada."
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
func _act(text: String, cb: Callable) -> Button:
	var b := Button.new(); b.text = text; b.custom_minimum_size = Vector2(0, 34)
	b.pressed.connect(cb)
	return b

func _tag(text: String, col: Color) -> Label:
	var l := Label.new(); l.text = text; l.add_theme_font_size_override("font_size", 12); l.modulate = col
	return l

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4)
	return l
