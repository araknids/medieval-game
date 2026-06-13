extends Control
# ── Tela CORREIO / MAIL ────────────────────────────────────────────────────────────
# Lê GET /api/mail/inbox (cartas + não-lidas) + /api/warrior (carteira). Abre uma carta
# (POST /api/mail/{id}/read), reivindica ouro/item/recurso anexado e deleta. Espelha
# loadMail/renderMailPanel/mailOpen do app.js. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var letters: Array = []         # cache local da inbox
var warrior: Dictionary = {}    # /api/warrior (carteira do header)
var unread := 0
var opened_id := -1             # carta aberta no momento (-1 = nenhuma)
var opened: Dictionary = {}     # dados completos da carta aberta (resposta do /read)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "✉ Correio", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_SOCIAL)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/mail/inbox", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	var data: Dictionary = r["json"]
	letters = data.get("letters", []) if data.get("letters") is Array else []
	unread = int(data.get("unread", 0))
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	content.add_child(UiKit.section("📥 Caixa de entrada%s" % ("  (%d não-lidas)" % unread if unread > 0 else "")))
	if letters.is_empty():
		content.add_child(UiKit.empty("Caixa vazia", "Recompensas, itens e recados chegam aqui"))
		return
	# cartas em grid (2 col) p/ encurtar a lista; a carta aberta abre num painel
	# FULL-WIDTH abaixo da grade (preserva o comportamento de abrir inline).
	content.add_child(UiKit.grid(self, letters, func(letter): return _letter_row(letter) if letter is Dictionary else null))
	if opened_id != -1:
		for letter in letters:
			if letter is Dictionary and int(letter.get("id", -1)) == opened_id:
				content.add_child(_open_panel())
				break

# ── linha da carta na lista (clicável p/ abrir) ──
func _letter_row(m: Dictionary) -> PanelContainer:
	var is_read := bool(m.get("isRead", false))
	var res := UiKit.card(UiKit.GOLD_SOFT if not is_read else UiKit.BRONZE)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 12)
	box.add_child(hb)
	# esquerda: remetente + flags + prévia da mensagem
	var left := VBoxContainer.new(); left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var from := Label.new(); from.text = str(m.get("from", "?"))
	from.add_theme_font_size_override("font_size", 16)
	from.add_theme_color_override("font_color", UiKit.TEXT if not is_read else UiKit.TEXT_DIM)
	top.add_child(from)
	if not is_read:
		var nw := Label.new(); nw.text = "● NOVA"; nw.add_theme_font_size_override("font_size", 12)
		nw.add_theme_color_override("font_color", UiKit.GOLD)
		top.add_child(nw)
	for flag in _flags(m):
		top.add_child(flag)
	left.add_child(top)
	var msg := str(m.get("message", ""))
	if msg.length() > 60:
		msg = msg.substr(0, 60) + "…"
	left.add_child(UiKit.dim(msg))
	hb.add_child(left)
	# direita: data + botão abrir
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 6)
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	right.add_child(UiKit.dim(str(m.get("sentAt", "")).substr(0, 10)))
	var id := int(m.get("id", 0))
	right.add_child(UiKit.small_btn("Fechar" if id == opened_id else "Abrir", _toggle_open.bind(id)))
	hb.add_child(right)
	return pc

# selos de anexo na linha (💰 ouro / 📦 item / 🐟 recurso / ⏰ expirado)
func _flags(m: Dictionary) -> Array:
	var out: Array = []
	var expired := bool(m.get("isExpired", false))
	if int(m.get("goldAmount", 0)) > 0 and not bool(m.get("isCollected", false)):
		out.append(_tag("💰 %d" % int(m.get("goldAmount", 0)), UiKit.GOLD))
	if bool(m.get("hasItem", false)) and not bool(m.get("itemCollected", false)) and not expired:
		out.append(_tag("📦 ITEM", Color(0.65, 0.55, 0.98)))
	if bool(m.get("hasResource", false)) and not expired:
		out.append(_tag("🐟 %d×" % int(m.get("resourceQty", 0)), Color(0.3, 0.82, 0.88)))
	if (bool(m.get("hasItem", false)) or bool(m.get("hasResource", false))) and expired:
		out.append(_tag("⏰ EXPIRADO", UiKit.ERR))
	return out

# ── painel da carta aberta (resposta do /read) ──
func _open_panel() -> PanelContainer:
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	vb.add_theme_constant_override("separation", 6)
	var r := opened
	# topo: remetente + deletar
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var from := Label.new(); from.text = "De: %s" % str(r.get("from", "?"))
	from.add_theme_font_size_override("font_size", 16); from.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	from.add_theme_color_override("font_color", UiKit.TEXT)
	top.add_child(from)
	top.add_child(UiKit.small_btn("🗑 Deletar", _confirm_delete.bind(opened_id, str(r.get("from", "?"))), true))
	vb.add_child(top)
	vb.add_child(UiKit.dim(str(r.get("sentAt", "")).substr(0, 16).replace("T", " ")))
	# corpo da mensagem
	vb.add_child(UiKit.body(str(r.get("message", ""))))
	# anexo: OURO
	if bool(r.get("hasGold", false)):
		vb.add_child(UiKit.action("💰 Coletar %d ouro" % int(r.get("goldAmount", 0)), _collect_gold.bind(opened_id)))
	elif int(r.get("goldAmount", 0)) > 0:
		vb.add_child(UiKit.dim("💰 %d ouro (já coletado)" % int(r.get("goldAmount", 0))))
	# anexo: ITEM
	if bool(r.get("hasItem", false)):
		if bool(r.get("isExpired", false)):
			vb.add_child(UiKit.dim("⏰ Este item expirou e foi perdido."))
		elif bool(r.get("itemCollected", false)):
			vb.add_child(UiKit.dim("📦 %s (já reivindicado)" % str(r.get("itemName", ""))))
		else:
			var lbl := Label.new(); lbl.text = "📦 %s" % str(r.get("itemName", ""))
			lbl.add_theme_color_override("font_color", Color(0.65, 0.55, 0.98))
			vb.add_child(lbl)
			vb.add_child(UiKit.action("📦 Adicionar à mochila", _claim_item.bind(opened_id)))
	# anexo: RECURSO ([DAILY])
	if bool(r.get("hasResource", false)) and not bool(r.get("isExpired", false)):
		var rname := str(r.get("resourceName", "")) if str(r.get("resourceName", "")) != "" else str(r.get("resourceType", ""))
		var lbl := Label.new(); lbl.text = "🐟 %s ×%d" % [rname, int(r.get("resourceQty", 0))]
		lbl.add_theme_color_override("font_color", Color(0.5, 0.82, 0.88))
		vb.add_child(lbl)
		vb.add_child(UiKit.action("📦 Adicionar à mochila", _claim_resource.bind(opened_id)))
	return pc

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
		unread = maxi(0, unread - 1)   # estimativa; o próximo _refresh corrige
		_render()
	else:
		UiKit.show_error(status, r)

func _collect_gold(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_collect_gold(id)
	busy = false
	if r.get("ok"):
		var msg := str(r["json"].get("message", "Ouro coletado!")) if r.get("json") is Dictionary else "Ouro coletado!"
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

func _claim_item(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_claim_item(id)
	busy = false
	if r.get("ok"):
		var msg := str(r["json"].get("message", "Item adicionado!")) if r.get("json") is Dictionary else "Item adicionado!"
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

func _claim_resource(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_claim_resource(id)
	busy = false
	if r.get("ok"):
		var msg := str(r["json"].get("message", "Recurso adicionado!")) if r.get("json") is Dictionary else "Recurso adicionado!"
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# Deletar = irreversível → confirma antes.
func _confirm_delete(id: int, sender: String) -> void:
	UiKit.confirm(self, "Deletar a carta de \"%s\"? Anexos não coletados serão perdidos." % sender, "Deletar", func() -> void: await _delete(id), true)

func _delete(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_delete(id)
	busy = false
	if r.get("ok"):
		opened_id = -1; opened = {}
		letters = letters.filter(func(it): return not (it is Dictionary) or int(it.get("id", -1)) != id)
		var msg := str(r["json"].get("message", "Carta deletada.")) if r.get("json") is Dictionary else "Carta deletada."
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _tag(text: String, col: Color) -> Label:
	var l := Label.new(); l.text = text; l.add_theme_font_size_override("font_size", 12)
	l.add_theme_color_override("font_color", col)
	return l
