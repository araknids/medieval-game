extends Control
# ── Tela TEMPLO ──────────────────────────────────────────────────────────────────
# GET /api/temple → estado do HP + custo de cura + bênçãos (buffs) + cooldowns VIP/SoulStone.
# GET /api/inventory → itens equipados p/ proteção (guarded). Ações:
#   curar (POST /api/temple/heal | /vip-heal | /soulstone-heal), abençoar (POST /api/temple/buff/{id}),
#   proteger/desproteger item (POST /api/temple/protect|unprotect/{id}). [MIGRACAO_GODOT]

signal go_back

# raridade 1-5 → cor (igual ao Inventory)
const RARITY_COL := [Color(0.72, 0.72, 0.75), Color(0.45, 0.85, 0.45), Color(0.4, 0.6, 1.0), Color(0.78, 0.45, 0.95), Color(1.0, 0.8, 0.35)]

var data: Dictionary = {}     # cache de /api/temple
var equipped: Array = []      # itens equipados (p/ proteção), de /api/inventory
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
	var ttl := Label.new(); ttl.text = "⛪ Templo"; ttl.add_theme_font_size_override("font_size", 26)
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
	content.add_theme_constant_override("separation", 6)
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	inner.add_child(content)
	await _refresh()

func _refresh() -> void:
	status.text = "Carregando…"
	var r = await Api.temple_info()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	data = r["json"]
	# itens equipados p/ proteção (best-effort; se falhar, segue sem a seção de itens)
	var inv = await Api.get_inventory()
	equipped = []
	if inv.get("ok") and inv.get("json") is Array:
		for it in inv["json"]:
			if it is Dictionary and it.get("equipped", false):
				equipped.append(it)
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	_render_state()
	content.add_child(_spacer(10))
	_render_buffs()
	content.add_child(_spacer(10))
	_render_protection()

# ── Estado do guerreiro + curas ────────────────────────────────────────────────
func _render_state() -> void:
	content.add_child(_section("Estado do Guerreiro"))
	var hp := int(data.get("hpPercent", 100))
	var ko := bool(data.get("isKnockedOut", false))
	var hp_col := Color(0.81, 0.4, 0.47) if hp <= 0 else (Color(0.79, 0.66, 0.3) if hp < 50 else Color(0.3, 0.69, 0.51))
	var hp_txt := "💀 Inconsciente" if ko else "❤ %d%%" % hp
	content.add_child(_bar("HP", hp, 100, hp_col, hp_txt))
	if ko:
		content.add_child(_dim("Seu guerreiro está nocauteado. Cure para voltar ao combate."))
	elif hp < 100:
		content.add_child(_dim("Regenerando HP… o templo pode curar instantaneamente."))
	else:
		content.add_child(_dim("HP cheio!"))
	# cura paga/grátis
	var full := hp >= 100
	if full:
		var done := Button.new(); done.text = "✓ HP cheio"; done.disabled = true
		content.add_child(done)
	else:
		var cost := int(data.get("healCost", 100))
		var lbl := "Curar (grátis)" if bool(data.get("healFree", false)) else "Curar (%d🥉)" % cost
		content.add_child(_act(lbl, _heal))
	# VIP heal (CD 10min)
	if bool(data.get("isVip", false)):
		var cd := int(data.get("vipHealCooldownSecs", 0))
		if cd < 0: cd = 0
		var vlbl := "✓ HP cheio" if full else ("⏳ VIP Heal CD %dm %ds" % [cd / 60, cd % 60] if cd > 0 else "👑 VIP Heal (grátis)")
		var vb := _act(vlbl, _vip_heal)
		vb.disabled = full or cd > 0
		content.add_child(vb)
		content.add_child(_dim("👑 VIP · CD 10 min · grátis"))
	# SoulStone heal (CD 30min)
	var ss := int(data.get("soulStones", 0))
	if ss > 0:
		var cd2 := int(data.get("ssHealCooldownSecs", 0))
		if cd2 < 0: cd2 = 0
		var slbl := "✓ HP cheio" if full else ("⏳ Cooldown %dm %ds" % [cd2 / 60, cd2 % 60] if cd2 > 0 else "💎 Cura instantânea (1 SoulStone)")
		var sb := _act(slbl, _soulstone_heal)
		sb.disabled = full or cd2 > 0
		content.add_child(sb)
		content.add_child(_dim("💎 %d SoulStone(s) · CD 30 min" % ss))

# ── Bênçãos (buffs) ────────────────────────────────────────────────────────────
func _render_buffs() -> void:
	content.add_child(_section("Bênçãos"))
	var active := str(data.get("activeBuff", ""))
	if active != "":
		var secs := int(data.get("buffSecondsLeft", 0))
		var al := Label.new(); al.text = "Bênção 1: %s — %d min restantes" % [active, secs / 60]
		al.modulate = Color(0.5, 0.85, 0.55)
		content.add_child(al)
	else:
		content.add_child(_dim("Nenhuma bênção ativa."))
	var active2 := str(data.get("activeBuff2", ""))
	if active2 != "":
		var secs2 := int(data.get("buff2SecondsLeft", 0))
		var a2 := Label.new(); a2.text = "👑 Bênção 2 (VIP): %s — %d min restantes" % [active2, secs2 / 60]
		a2.modulate = Color(0.77, 0.71, 0.99)
		content.add_child(a2)
	elif bool(data.get("isVip", false)):
		var v2 := Label.new(); v2.text = "👑 Slot 2 do VIP disponível"; v2.modulate = Color(0.49, 0.23, 0.93)
		content.add_child(v2)
	content.add_child(_spacer(4))
	var buffs = data.get("buffs", [])
	if buffs is Array:
		for b in buffs:
			if b is Dictionary:
				content.add_child(_buff_card(b))

func _buff_card(b: Dictionary) -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1); sb.border_color = Color(0.45, 0.5, 0.6, 0.5)
	sb.set_corner_radius_all(5); sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	var left := VBoxContainer.new(); left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	nm.text = "%s %s" % [str(b.get("icon", "")), str(b.get("displayName", b.get("id", "?")))]
	nm.add_theme_font_size_override("font_size", 16)
	left.add_child(nm)
	var eff := Label.new(); eff.text = "%s · %d🥉" % [str(b.get("effect", "")), int(b.get("bronzeCost", 0))]
	eff.modulate = Color(1, 1, 1, 0.55); eff.add_theme_font_size_override("font_size", 12)
	left.add_child(eff)
	hb.add_child(left)
	hb.add_child(_act("Abençoar", _apply_buff.bind(str(b.get("id", "")))))
	return panel

# ── Proteção de itens ──────────────────────────────────────────────────────────
func _render_protection() -> void:
	var pc := int(data.get("protectedCount", 0))
	var mx := int(data.get("maxProtected", 3))
	content.add_child(_section("Proteção de Itens (%d/%d)" % [pc, mx]))
	content.add_child(_dim("Itens protegidos não são perdidos em PvP. Custo: 50🥉/item."))
	if equipped.is_empty():
		content.add_child(_dim("— nenhum item equipado —"))
		return
	for it in equipped:
		content.add_child(_protect_row(it))

func _protect_row(it: Dictionary) -> PanelContainer:
	var rarity := int(it.get("rarity", 1))
	var col: Color = RARITY_COL[clampi(rarity - 1, 0, 4)]
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1); sb.border_color = Color(col, 0.6)
	sb.set_corner_radius_all(5); sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	var nm := Label.new(); nm.text = str(it.get("name", "?")); nm.modulate = col
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	hb.add_child(nm)
	var id := int(it.get("id", 0))
	if it.get("guarded", false):
		var guarded := Label.new(); guarded.text = "🛡 protegido"; guarded.modulate = Color(0.5, 0.85, 0.55)
		hb.add_child(guarded)
		hb.add_child(_act("Remover", _unprotect.bind(id)))
	else:
		hb.add_child(_act("Proteger", _protect.bind(id)))
	return panel

# ── Ações (async com .bind) — em sucesso re-baixa tudo; em falha mostra erro ──────
func _heal() -> void:
	await _do(Api.temple_heal())

func _vip_heal() -> void:
	await _do(Api.temple_vip_heal())

func _soulstone_heal() -> void:
	await _do(Api.temple_soulstone_heal())

func _apply_buff(buff_id: String) -> void:
	await _do(Api.temple_apply_buff(buff_id))

func _protect(id: int) -> void:
	await _do(Api.temple_protect(id))

func _unprotect(id: int) -> void:
	await _do(Api.temple_unprotect(id))

# Executa a chamada (já iniciada e passada como retorno), trata sucesso/erro e re-sincroniza.
func _do(call: Variant) -> void:
	if busy: return
	busy = true
	var r = await call
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary:
		status.text = str(r["json"].get("message", "OK"))
		await _refresh()
	else:
		_show_error(r)
	busy = false

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _act(text: String, cb: Callable) -> Button:
	var b := Button.new(); b.text = text; b.custom_minimum_size = Vector2(120, 0)
	b.pressed.connect(cb)
	return b

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4); l.add_theme_font_size_override("font_size", 12)
	return l

func _bar(bname: String, value: int, maxv: int, col: Color, txt: String) -> VBoxContainer:
	var box := VBoxContainer.new()
	var lbl := Label.new(); lbl.text = "%s   %s" % [bname, txt]; box.add_child(lbl)
	var pb := ProgressBar.new()
	pb.min_value = 0; pb.max_value = max(1, maxv); pb.value = clampi(value, 0, maxv)
	pb.show_percentage = false
	pb.custom_minimum_size = Vector2(0, 14)
	var sb := StyleBoxFlat.new(); sb.bg_color = col; sb.set_corner_radius_all(3)
	pb.add_theme_stylebox_override("fill", sb)
	box.add_child(pb)
	return box

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
