extends Control
# ── Tela VIP / SoulStone ──────────────────────────────────────────────────────────
# Lê GET /api/vip/status (isVip, vipExpiresAt, soulStones, arenaFights…) e mostra o status
# VIP + saldo de SoulStone. Ação primária: comprar/renovar VIP (POST /api/vip/buy, 15💎,
# +30 dias). Mount celestial/pet/expansão de bag ficaram de fora (endpoints à parte). Volta
# pro Hub (sinal go_back). [MIGRACAO_GODOT]

signal go_back

const VIP_COST := 15

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
	var ttl := Label.new(); ttl.text = "VIP"; ttl.add_theme_font_size_override("font_size", 26)
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

func _refresh() -> void:
	status.text = "Carregando…"
	var r = await Api.vip_status()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	data = r["json"]
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	var is_vip := bool(data.get("isVip", false))
	var ss := int(data.get("soulStones", 0))
	# ── Banner de status ──
	if is_vip:
		var days := _days_left(str(data.get("vipExpiresAt", "")))
		content.add_child(_banner("👑 VIP Ativo — %d dia%s restante%s" % [days, "" if days == 1 else "s", "" if days == 1 else "s"],
			Color(0.77, 0.71, 0.93), Color(0.49, 0.18, 0.39)))
		var exp := str(data.get("vipExpiresAt", ""))
		if exp.length() >= 10:
			content.add_child(_dim("Expira em %s" % exp.substr(0, 10)))
		var lim := int(data.get("arenaFightLimit", 0))
		var rem := int(data.get("arenaFightsRemaining", 0))
		content.add_child(_dim("⚔ Lutas de arena: %d/%d" % [lim - rem, lim]))
	else:
		content.add_child(_banner("Você não tem VIP ativo.", Color(0.7, 0.7, 0.7), Color(0.12, 0.06, 0.22)))
	content.add_child(_spacer(6))
	# ── Card de compra ──
	var card := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.1, 0.1, 0.18)
	sb.set_border_width_all(1); sb.border_color = Color(0.49, 0.23, 0.93, 0.7)
	sb.set_corner_radius_all(6); sb.set_content_margin_all(12)
	card.add_theme_stylebox_override("panel", sb)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 6)
	card.add_child(box)
	var title := Label.new()
	title.text = "👑 VIP — 30 dias   (%d 💎)" % VIP_COST
	title.add_theme_font_size_override("font_size", 17); title.modulate = Color(0.77, 0.71, 0.93)
	box.add_child(title)
	var perks := Label.new()
	perks.text = "Inclui: mochila maior · cura grátis · 10 lutas de arena/dia · 2 buffs simultâneos"
	perks.modulate = Color(1, 1, 1, 0.55); perks.add_theme_font_size_override("font_size", 12)
	perks.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	box.add_child(perks)
	var can_buy := ss >= VIP_COST
	var buy := Button.new()
	buy.text = ("👑 Renovar VIP (+30 dias)" if is_vip else "👑 Ativar VIP") + ("" if can_buy else "  (💎 insuficiente)")
	buy.custom_minimum_size = Vector2(0, 44)
	buy.disabled = busy or not can_buy
	buy.pressed.connect(func() -> void: await _buy_vip())
	box.add_child(buy)
	if not can_buy:
		box.add_child(_dim("Precisa de %d 💎 (você tem %d)" % [VIP_COST, ss]))
	content.add_child(card)
	# ── Saldo ──
	content.add_child(_spacer(8))
	var bal := Label.new()
	bal.text = "💎 Saldo: %d SoulStone%s" % [ss, "" if ss == 1 else "s"]
	bal.modulate = Color(0.66, 0.55, 0.85)
	bal.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	bal.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_child(bal)

# ── Ação: comprar/renovar VIP ───────────────────────────────────────────────────
func _buy_vip() -> void:
	if busy: return
	busy = true
	var r = await Api.vip_buy()
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		# resposta traz vipExpiresAt + soulStones atualizados → reflete no cache local
		data["isVip"] = true
		data["vipExpiresAt"] = str(j.get("vipExpiresAt", data.get("vipExpiresAt", "")))
		data["soulStones"] = int(j.get("soulStones", data.get("soulStones", 0)))
		status.text = str(j.get("message", "👑 VIP ativado!"))
		busy = false
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

# Dias restantes a partir de um ISO timestamp (ex.: "2026-07-11T12:00:00").
func _days_left(iso: String) -> int:
	if iso.length() < 10:
		return 0
	var exp := Time.get_unix_time_from_datetime_string(iso)
	if exp <= 0:
		return 0
	var now := Time.get_unix_time_from_system()
	return max(0, int(ceil((exp - now) / 86400.0)))

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _banner(text: String, fg: Color, bgc: Color) -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = bgc
	sb.set_border_width_all(1); sb.border_color = Color(0.49, 0.23, 0.93, 0.7)
	sb.set_corner_radius_all(6); sb.set_content_margin_all(12)
	panel.add_theme_stylebox_override("panel", sb)
	var l := Label.new(); l.text = text; l.modulate = fg
	l.add_theme_font_size_override("font_size", 15)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(l)
	return panel

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.5); l.add_theme_font_size_override("font_size", 12)
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
