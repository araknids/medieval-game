extends Control
# ── Tela CORREIO / MAIL — [MAIL_ABAS] 4 abas estilo Shakes&Fidget ─────────────────────────────
# Abas: Recebidos (de players) · Sistema (recompensas/avisos) · Enviados · Replays (em breve).
# Recebidos vs Sistema = split por senderPlayerId (0 = sistema). Abrir uma carta abre um MODAL
# central (não mais inline no FIM da lista, que ficava desconexo). GET /api/mail/inbox + /sent +
# /api/warrior; POST /api/mail/{id}/read|collect|... Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

const Icons := preload("res://ui/Icons.gd")
# [MAIL_ABAS] value · icon_key (PixelLab) · rótulo de fallback (sem emoji) · tooltip do hover
const TABS := [
	["received", "mail_received", "Recebidos", "Cartas de outros jogadores"],
	["system",   "mail_system",   "Sistema",   "Recompensas, conquistas e avisos do jogo"],
	["sent",     "mail_sent",     "Enviados",  "Cartas que você enviou"],
	["replays",  "mail_replays",  "Replays",   "Reveja suas batalhas (em breve)"],
]

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var letters: Array = []         # inbox (sistema + players)
var sent: Array = []            # /api/mail/sent
var warrior: Dictionary = {}    # /api/warrior (carteira do header)
var unread := 0
var tab := "received"           # aba ativa
var opened_id := -1             # carta no modal (-1 = nenhuma)
var opened: Dictionary = {}     # resposta do /read (conteúdo do modal)
var _modal: Control = null      # overlay do modal aberto (1 por vez)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "Correio", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_SOCIAL)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	_close_modal()
	UiKit.show_loading(self)
	var rs = await Api.batch_get(["/api/mail/inbox", "/api/mail/sent", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	var data: Dictionary = r["json"]
	letters = data.get("letters", []) if data.get("letters") is Array else []
	unread = int(data.get("unread", 0))
	var rsent = rs[1]
	sent = rsent["json"] if (rsent.get("ok") and rsent.get("json") is Array) else []
	var wr = rs[2]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

# ── splits por aba (Recebidos = de player; Sistema = remetente 0) ──
func _received() -> Array:
	return letters.filter(func(m): return m is Dictionary and int(m.get("senderPlayerId", 0)) != 0)
func _system() -> Array:
	return letters.filter(func(m): return m is Dictionary and int(m.get("senderPlayerId", 0)) == 0)
func _unread_in(list: Array) -> int:
	var n := 0
	for m in list:
		if m is Dictionary and not bool(m.get("isRead", false)):
			n += 1
	return n

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	content.add_child(_tab_bar())
	match tab:
		"system":  _render_inbox(_system())
		"sent":    _render_sent()
		"replays": _render_replays()
		_:         _render_inbox(_received())

# Lista de cartas da inbox (Recebidos ou Sistema). Recolher/Apagar agem na inbox INTEIRA (backend
# global) — o confirm de apagar deixa isso claro ("TODAS as cartas").
func _render_inbox(list: Array) -> void:
	var un := _unread_in(list)
	var title := Lang.t("Recebidos") if tab == "received" else Lang.t("Sistema")
	content.add_child(UiKit.section(title + (Lang.t("  (%d não-lidas)") % un if un > 0 else "")))
	if list.is_empty():
		if tab == "received":
			content.add_child(UiKit.empty("Nenhuma carta de jogadores", "Mensagens de outros jogadores aparecem aqui"))
		else:
			content.add_child(UiKit.empty("Caixa do sistema vazia", "Recompensas, conquistas e avisos chegam aqui"))
		return
	var bar := HBoxContainer.new(); bar.add_theme_constant_override("separation", 10)
	if _has_collectible(letters):
		var ball := UiKit.action("📥 Recolher tudo", _claim_all)
		ball.custom_minimum_size = Vector2(190, 40)
		bar.add_child(ball)
	var dall := UiKit.action_danger("🗑 Apagar tudo", _confirm_delete_all)
	dall.custom_minimum_size = Vector2(160, 40)
	bar.add_child(dall)
	content.add_child(bar)
	content.add_child(UiKit.grid(self, list, func(m): return _letter_row(m) if m is Dictionary else null))

func _render_sent() -> void:
	content.add_child(UiKit.section(Lang.t("Enviados (%d)") % sent.size()))
	if sent.is_empty():
		content.add_child(UiKit.empty("Você não enviou cartas", "Cartas enviadas a outros jogadores aparecem aqui"))
		return
	content.add_child(UiKit.grid(self, sent, func(m): return _letter_row(m) if m is Dictionary else null))

func _render_replays() -> void:
	content.add_child(UiKit.section(Lang.t("Replays")))
	content.add_child(UiKit.empty("Replays — em breve", "Reveja suas batalhas aqui. Em construção."))

# ── barra de abas (ícone + nome + tooltip; badge de não-lidas) [MAIL_ABAS] ──
func _tab_bar() -> Control:
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
	for t in TABS:
		row.add_child(_tab_btn(str(t[0]), str(t[1]), str(t[2]), str(t[3])))
	return row

func _tab_btn(value: String, icon_key: String, label: String, tooltip: String) -> Button:
	var un := 0
	if value == "received": un = _unread_in(_received())
	elif value == "system": un = _unread_in(_system())
	var txt := Lang.t(label) + ("  (%d)" % un if un > 0 else "")
	var b := UiKit.small_btn(txt, func() -> void: _set_tab(value))
	b.tooltip_text = Lang.t(tooltip)
	if Icons.set_icon(b, icon_key):
		b.add_theme_constant_override("icon_max_width", 26)
		b.text = txt
	b.custom_minimum_size = Vector2(0, 44)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	b.add_theme_font_size_override("font_size", 13)
	if tab == value:                       # ativo: fundo dourado + borda
		var col := UiKit.GOLD
		var sb := StyleBoxFlat.new()
		sb.bg_color = Color(col.r, col.g, col.b, 0.22)
		sb.set_border_width_all(2); sb.border_color = col; sb.set_corner_radius_all(6)
		b.add_theme_stylebox_override("normal", sb)
		b.add_theme_stylebox_override("hover", sb)
		b.add_theme_stylebox_override("pressed", sb)
		b.add_theme_stylebox_override("focus", sb)
	elif value == "replays":
		b.modulate = Color(1, 1, 1, 0.45)  # "em breve" — apagado
	else:
		b.modulate = Color(1, 1, 1, 0.6)
	return b

func _set_tab(value: String) -> void:
	tab = value
	_render()

# ── linha da carta na lista (clicável p/ abrir o modal) ──
func _letter_row(m: Dictionary) -> PanelContainer:
	var is_read := bool(m.get("isRead", false))
	var res := UiKit.card(UiKit.GOLD_SOFT if not is_read else UiKit.BRONZE)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 12)
	box.add_child(hb)
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
	left.add_child(UiKit.dim(str(m.get("message", ""))))
	hb.add_child(left)
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 6)
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	right.add_child(UiKit.dim(str(m.get("sentAt", "")).substr(0, 10)))
	right.add_child(UiKit.small_btn("Abrir", _open_mail.bind(int(m.get("id", 0)))))
	hb.add_child(right)
	return pc

# selos de anexo na linha (🪙 moeda / 📦 item / 🐟 recurso / ⏰ expirado)
func _flags(m: Dictionary) -> Array:
	var out: Array = []
	var expired := bool(m.get("isExpired", false))
	if int(m.get("goldAmount", 0)) > 0 and not bool(m.get("isCollected", false)):
		out.append(_tag(UiKit.coin_str(int(m.get("goldAmount", 0))), UiKit.GOLD))
	if bool(m.get("hasItem", false)) and not bool(m.get("itemCollected", false)) and not expired:
		out.append(_tag("📦 ITEM", Color(0.65, 0.55, 0.98)))
	if bool(m.get("hasResource", false)) and not expired:
		out.append(_tag("🐟 %d×" % int(m.get("resourceQty", 0)), Color(0.3, 0.82, 0.88)))
	if (bool(m.get("hasItem", false)) or bool(m.get("hasResource", false))) and expired:
		out.append(_tag("⏰ EXPIRADO", UiKit.ERR))
	return out

# ── MODAL central da carta (substitui o painel inline no fim da lista) [MAIL_ABAS] ──
func _open_mail(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_read(id)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	opened = r["json"]; opened_id = id
	for letter in letters:                 # marca lida no cache (o /read já marcou no servidor)
		if letter is Dictionary and int(letter.get("id", -1)) == id:
			letter["isRead"] = true
	unread = maxi(0, unread - 1)
	_render()                              # lista atualiza (carta vira lida + badge)
	_show_modal()

func _show_modal() -> void:
	_close_modal()
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	overlay.gui_input.connect(func(ev: InputEvent) -> void:   # clicar FORA do card fecha
		if ev is InputEventMouseButton and ev.pressed:
			_close_modal())
	add_child(overlay)
	_modal = overlay
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE   # cliques fora do card passam p/ o overlay
	overlay.add_child(center)
	center.add_child(_open_panel())

func _close_modal() -> void:
	if _modal != null and is_instance_valid(_modal):
		_modal.queue_free()
	_modal = null

# painel (card) com o conteúdo da carta aberta — hospedado no modal central
func _open_panel() -> PanelContainer:
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	pc.custom_minimum_size = Vector2(460, 0)
	vb.add_theme_constant_override("separation", 6)
	var r := opened
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var from := Label.new(); from.text = Lang.t("De: %s") % str(r.get("from", "?"))
	from.add_theme_font_size_override("font_size", 16); from.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	from.add_theme_color_override("font_color", UiKit.TEXT)
	top.add_child(from)
	top.add_child(UiKit.small_btn("🗑 Deletar", _confirm_delete.bind(opened_id, str(r.get("from", "?"))), true))
	vb.add_child(top)
	vb.add_child(UiKit.dim(str(r.get("sentAt", "")).substr(0, 16).replace("T", " ")))
	vb.add_child(UiKit.body(str(r.get("message", ""))))
	if bool(r.get("hasGold", false)):
		vb.add_child(UiKit.action(Lang.t("🪙 Coletar %s") % UiKit.coin_str(int(r.get("goldAmount", 0))), _collect_gold.bind(opened_id)))
	elif int(r.get("goldAmount", 0)) > 0:
		vb.add_child(UiKit.dim(Lang.t("🪙 %s (já coletado)") % UiKit.coin_str(int(r.get("goldAmount", 0)))))
	if bool(r.get("hasItem", false)):
		if bool(r.get("isExpired", false)):
			vb.add_child(UiKit.dim("⏰ Este item expirou e foi perdido."))
		elif bool(r.get("itemCollected", false)):
			vb.add_child(UiKit.dim(Lang.t("📦 %s (já reivindicado)") % str(r.get("itemName", ""))))
		else:
			var irow := HBoxContainer.new(); irow.add_theme_constant_override("separation", 8)
			var ic := UiKit.item_icon_for({"type": str(r.get("itemType", "")), "name": str(r.get("itemName", "")), "outfitTheme": str(r.get("outfitTheme", ""))}, 36)
			if ic:
				irow.add_child(ic)
			var lbl := Label.new(); lbl.text = str(r.get("itemName", ""))
			lbl.add_theme_color_override("font_color", Color(0.65, 0.55, 0.98))
			lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			irow.add_child(lbl)
			vb.add_child(irow)
			vb.add_child(UiKit.action("📦 Adicionar à mochila", _claim_item.bind(opened_id)))
	if bool(r.get("hasResource", false)) and not bool(r.get("isExpired", false)):
		var rname := str(r.get("resourceName", "")) if str(r.get("resourceName", "")) != "" else str(r.get("resourceType", ""))
		var lbl := Label.new(); lbl.text = "🐟 %s ×%d" % [rname, int(r.get("resourceQty", 0))]
		lbl.add_theme_color_override("font_color", Color(0.5, 0.82, 0.88))
		vb.add_child(lbl)
		vb.add_child(UiKit.action("📦 Adicionar à mochila", _claim_resource.bind(opened_id)))
	vb.add_child(UiKit.spacer(4))
	var close_btn := UiKit.action(Lang.t("Fechar"), _close_modal)
	close_btn.custom_minimum_size = Vector2(0, 38)
	vb.add_child(close_btn)
	return pc

# ── ações (1 chamada; em sucesso re-sincroniza a inbox e fecha o modal via _refresh) ──
func _collect_gold(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_collect_gold(id)
	busy = false
	if r.get("ok"):
		var msg := str(r["json"].get("message", Lang.t("Ouro coletado!"))) if r.get("json") is Dictionary else Lang.t("Ouro coletado!")
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
		var msg := str(r["json"].get("message", Lang.t("Item adicionado!"))) if r.get("json") is Dictionary else Lang.t("Item adicionado!")
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
		var msg := str(r["json"].get("message", Lang.t("Recurso adicionado!"))) if r.get("json") is Dictionary else Lang.t("Recurso adicionado!")
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# Deletar = irreversível → confirma antes.
func _confirm_delete(id: int, sender: String) -> void:
	UiKit.confirm(self, Lang.t("Deletar a carta de \"%s\"? Anexos não coletados serão perdidos.") % sender, "Deletar", func() -> void: await _delete(id), true)

func _delete(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.mail_delete(id)
	busy = false
	if r.get("ok"):
		_close_modal()
		opened_id = -1; opened = {}
		letters = letters.filter(func(it): return not (it is Dictionary) or int(it.get("id", -1)) != id)
		var msg := str(r["json"].get("message", Lang.t("Carta deletada."))) if r.get("json") is Dictionary else Lang.t("Carta deletada.")
		_render()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# Há algo coletável (ouro/item/recurso não reivindicado e não expirado) na lista? [MAIL_CLAIM_ALL]
func _has_collectible(list: Array) -> bool:
	for m in list:
		if not (m is Dictionary):
			continue
		if int(m.get("goldAmount", 0)) > 0 and not bool(m.get("isCollected", false)):
			return true
		if bool(m.get("hasItem", false)) and not bool(m.get("itemCollected", false)) and not bool(m.get("isExpired", false)):
			return true
		if bool(m.get("hasResource", false)) and not bool(m.get("isExpired", false)):
			return true
	return false

# Recolhe tudo de uma vez (1 chamada, inbox inteira) → re-sincroniza + resumo no status. [MAIL_CLAIM_ALL]
func _claim_all() -> void:
	if busy: return
	busy = true
	var r = await Api.mail_claim_all()
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	var j: Dictionary = r["json"]
	var parts: Array = []
	if int(j.get("gold", 0)) > 0:
		parts.append(UiKit.coin_str(int(j.get("gold", 0))))
	if int(j.get("items", 0)) > 0:
		parts.append(Lang.t("%d item(ns)") % int(j.get("items", 0)))
	if int(j.get("resources", 0)) > 0:
		parts.append(Lang.t("%d recurso(s)") % int(j.get("resources", 0)))
	var msg := (Lang.t("Recolhido: ") + ", ".join(parts)) if not parts.is_empty() else Lang.t("Nada para recolher.")
	if int(j.get("leftItems", 0)) > 0 or int(j.get("leftResources", 0)) > 0:
		msg += "  " + Lang.t("(parte ficou — mochila cheia)")
	await _refresh()
	UiKit.flash(status, msg, 1)

# Apagar TODAS as cartas da inbox — confirma antes (avisa mais forte se há anexo não coletado). [MAIL_CLAIM_ALL]
func _confirm_delete_all() -> void:
	var warn := Lang.t("Apagar TODAS as cartas?")
	if _has_collectible(letters):
		warn += "  " + Lang.t("⚠ Há anexos não coletados — serão perdidos! (use Recolher tudo antes)")
	UiKit.confirm(self, warn, "Apagar tudo", func() -> void: await _delete_all(), true)

func _delete_all() -> void:
	if busy: return
	busy = true
	var r = await Api.mail_delete_all()
	busy = false
	if r.get("ok"):
		var msg := str(r["json"].get("message", Lang.t("Cartas apagadas."))) if r.get("json") is Dictionary else Lang.t("Cartas apagadas.")
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _tag(text: String, col: Color) -> Label:
	var l := Label.new(); l.text = text; l.add_theme_font_size_override("font_size", 12)
	l.add_theme_color_override("font_color", col)
	return l
