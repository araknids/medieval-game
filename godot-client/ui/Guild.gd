extends Control
# ── Tela GUILDA ───────────────────────────────────────────────────────────────────
# Espelha loadGuild/renderGuildPanel/renderNoGuildPanel do app.js.
# GET /api/guild → se inGuild: painel (info + membros + doação + ranking + sair/dissolver,
#   e p/ líder: expulsar/transferir). Se não: criar guilda + lista p/ entrar (GET /api/guild/list).
# Guerra de guilda / formação 3×5 / territórios = sub-telas FUTURAS (só notadas). [MIGRACAO_GODOT]

signal go_back

var content: VBoxContainer
var status: Label
var busy := false
var data: Dictionary = {}      # detalhe da guilda (quando inGuild)
var guild_list: Array = []     # lista de guildas (quando sem guilda)
# campos de input (criar guilda / doar) — guardados p/ ler no submit
var name_edit: LineEdit
var desc_edit: LineEdit
var donate_gold: SpinBox
var donate_silver: SpinBox
var donate_bronze: SpinBox

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
	var ttl := Label.new(); ttl.text = "Guilda"; ttl.add_theme_font_size_override("font_size", 26)
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
	var r = await Api.guild_get()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	data = r["json"]
	status.text = ""
	if bool(data.get("inGuild", false)):
		_render_panel()
	else:
		# sem guilda → busca a lista pra entrar, depois renderiza o painel de criação
		var lr = await Api.guild_list()
		guild_list = lr["json"] if (lr.get("ok") and lr.get("json") is Array) else []
		_render_no_guild()

func _clear() -> void:
	for c in content.get_children():
		c.queue_free()
	name_edit = null; desc_edit = null
	donate_gold = null; donate_silver = null; donate_bronze = null

# ── Painel COM guilda ──────────────────────────────────────────────────────────────
func _render_panel() -> void:
	_clear()
	var g := data
	var is_leader := bool(g.get("isLeader", false))
	# cabeçalho da guilda
	var head := Label.new()
	head.text = "%s   Lv.%d" % [str(g.get("name", "?")), int(g.get("level", 1))]
	head.add_theme_font_size_override("font_size", 24)
	content.add_child(head)
	var desc := Label.new(); desc.text = str(g.get("description", "")) if str(g.get("description", "")) != "" else "Sem descrição."
	desc.modulate = Color(1, 1, 1, 0.6); desc.add_theme_font_size_override("font_size", 13)
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	content.add_child(desc)
	var members: Array = g.get("members", []) if g.get("members") is Array else []
	content.add_child(_kv("🏦 Tesouro", _fmt_bronze(int(g.get("treasuryBronze", 0)))))
	content.add_child(_kv("👥 Membros", "%d/%d" % [members.size(), int(g.get("maxMembers", 0))]))
	# bônus
	var xpb := int(g.get("xpBonus", 0)); var dropb := int(g.get("dropBonus", 0)); var brb := int(g.get("bronzeBonus", 0))
	if xpb != 0 or dropb != 0 or brb != 0:
		var bl := Label.new()
		bl.text = "Bônus: +%d%% XP · +%d%% drop · +%d%% bronze" % [xpb, dropb, brb]
		bl.modulate = Color(0.55, 0.76, 0.29); bl.add_theme_font_size_override("font_size", 12)
		content.add_child(bl)
	# progresso de nível [GUILD_LEVEL_GOLD]
	var maxed := int(g.get("level", 1)) >= int(g.get("maxLevel", 10))
	if maxed:
		var ml := Label.new()
		ml.text = "⭐ Nível máximo (Lv.%d) — total contribuído: %s" % [int(g.get("maxLevel", 10)), _fmt_bronze(int(g.get("lifetimeGold", 0)))]
		ml.modulate = Color(1, 0.84, 0); ml.add_theme_font_size_override("font_size", 12)
		content.add_child(ml)
	else:
		content.add_child(_bar("Nível", int(g.get("levelProgressPct", 0)), 100, Color(1, 0.84, 0),
			"Lv.%d → Lv.%d  (faltam %s)" % [int(g.get("level", 1)), int(g.get("level", 1)) + 1, _fmt_bronze(int(g.get("goldToNextLevel", 0)))]))
	content.add_child(_spacer(8))

	# ── Membros ──
	content.add_child(_section("Membros (%d)" % members.size()))
	for mm in members:
		if mm is Dictionary:
			content.add_child(_member_row(mm, is_leader))
	content.add_child(_spacer(10))

	# ── Doar ──
	content.add_child(_section("Doar para o tesouro"))
	var donate_row := HBoxContainer.new(); donate_row.add_theme_constant_override("separation", 6)
	donate_gold = _spin("🥇")
	donate_silver = _spin("🥈")
	donate_bronze = _spin("🥉")
	donate_row.add_child(_labeled("🥇 Ouro", donate_gold))
	donate_row.add_child(_labeled("🥈 Prata", donate_silver))
	donate_row.add_child(_labeled("🥉 Bronze", donate_bronze))
	content.add_child(donate_row)
	content.add_child(_act("💰 Doar", _donate, Vector2(140, 0)))
	content.add_child(_spacer(10))

	# ── Sair / Dissolver ──
	if is_leader:
		var db := _act("💀 Dissolver Guilda", _disband, Vector2(180, 0))
		db.modulate = Color(1, 0.6, 0.6)
		content.add_child(db)
	else:
		content.add_child(_act("🚪 Sair da Guilda", _leave, Vector2(180, 0)))
	content.add_child(_spacer(12))

	# ── Top Doadores ──
	var rank: Array = g.get("donationRank", []) if g.get("donationRank") is Array else []
	content.add_child(_section("🏆 Top Doadores"))
	if rank.is_empty():
		content.add_child(_dim("— sem doações ainda —"))
	else:
		var i := 0
		for d in rank:
			if d is Dictionary:
				var medal := "🥇" if i == 0 else ("🥈" if i == 1 else ("🥉" if i == 2 else "%d." % (i + 1)))
				var me := bool(d.get("isMe", false))
				var row := _kv("%s %s%s" % [medal, str(d.get("warriorName", "?")), " (você)" if me else ""], _fmt_bronze(int(d.get("donatedBronze", 0))))
				if me:
					row.modulate = Color(1, 0.84, 0)
				content.add_child(row)
			i += 1

	# nota sobre sub-telas futuras
	content.add_child(_spacer(10))
	content.add_child(_dim("⚔ Guerra de guilda, formação 3×5 e territórios virão em telas próprias."))

func _member_row(mm: Dictionary, is_leader: bool) -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1); sb.border_color = Color(0.27, 0.27, 0.3)
	sb.set_corner_radius_all(5); sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 8)
	panel.add_child(hb)
	var ml := bool(mm.get("isLeader", false))
	var me := bool(mm.get("isMe", false))
	var title := str(mm.get("title", ""))
	var nm := Label.new()
	nm.text = (title + " " if title != "" else "") + str(mm.get("warriorName", "?")) + (" 👑" if ml else "") + (" (você)" if me else "")
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	hb.add_child(nm)
	var fat := int(mm.get("fatiguePct", 0))
	if fat > 0:
		var fl := Label.new(); fl.text = "😓 -%d%%" % fat; fl.modulate = Color(0.9, 0.45, 0.45); fl.add_theme_font_size_override("font_size", 11)
		hb.add_child(fl)
	# botões do líder (não em si mesmo / kick não no líder)
	if is_leader and not me:
		var pid := int(mm.get("playerId", 0))
		if not ml:
			var k := _act("Expulsar", _kick.bind(pid), Vector2(80, 0))
			k.add_theme_font_size_override("font_size", 12); k.modulate = Color(1, 0.6, 0.6)
			hb.add_child(k)
		var t := _act("Transferir", _transfer.bind(pid), Vector2(90, 0))
		t.add_theme_font_size_override("font_size", 12)
		hb.add_child(t)
	return panel

# ── Painel SEM guilda ──────────────────────────────────────────────────────────────
func _render_no_guild() -> void:
	_clear()
	var note := Label.new(); note.text = "Você não pertence a nenhuma guilda."
	note.modulate = Color(1, 1, 1, 0.7)
	content.add_child(note)
	content.add_child(_spacer(6))

	# criar
	content.add_child(_section("Criar nova guilda  (custa 100 bronze)"))
	name_edit = LineEdit.new(); name_edit.placeholder_text = "Nome (3-30 chars)"; name_edit.max_length = 30
	name_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_child(name_edit)
	desc_edit = LineEdit.new(); desc_edit.placeholder_text = "Descrição (opcional)"; desc_edit.max_length = 120
	desc_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_child(desc_edit)
	content.add_child(_act("🛡 Criar Guilda", _create, Vector2(160, 0)))
	content.add_child(_spacer(12))

	# lista p/ entrar
	content.add_child(_section("Guildas existentes"))
	if guild_list.is_empty():
		content.add_child(_dim("— nenhuma guilda criada ainda. Seja o primeiro! —"))
	for g in guild_list:
		if g is Dictionary:
			content.add_child(_guild_list_row(g))

func _guild_list_row(g: Dictionary) -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.1, 0.1, 0.18)
	sb.set_border_width_all(1); sb.border_color = Color(0.27, 0.27, 0.3)
	sb.set_corner_radius_all(6); sb.set_content_margin_all(10)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 8)
	panel.add_child(hb)
	var left := VBoxContainer.new(); left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new(); nm.text = "%s   Nv.%d" % [str(g.get("name", "?")), int(g.get("level", 1))]
	nm.add_theme_font_size_override("font_size", 16)
	left.add_child(nm)
	var d := str(g.get("description", ""))
	if d != "":
		var dl := Label.new(); dl.text = d; dl.modulate = Color(1, 1, 1, 0.5); dl.add_theme_font_size_override("font_size", 12)
		left.add_child(dl)
	var members := int(g.get("members", 0)); var maxm := int(g.get("maxMembers", 0))
	var cl := Label.new(); cl.text = "👥 %d/%d" % [members, maxm]; cl.add_theme_font_size_override("font_size", 12)
	left.add_child(cl)
	hb.add_child(left)
	var full := members >= maxm
	var join := _act("Cheia" if full else "Entrar", _join.bind(int(g.get("id", 0))), Vector2(90, 0))
	join.disabled = full
	hb.add_child(join)
	return panel

# ── Ações (async, 1 chamada → re-refresh) ──────────────────────────────────────────
func _create() -> void:
	if busy: return
	if name_edit == null: return
	var nm := name_edit.text.strip_edges()
	var ds := desc_edit.text.strip_edges() if desc_edit else ""
	busy = true
	var r = await Api.guild_create(nm, ds)
	busy = false
	if r.get("ok"):
		status.text = "Guilda criada!"
		await _refresh()
	else:
		_show_error(r)

func _join(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.guild_join(id)
	busy = false
	if r.get("ok"):
		status.text = "Você entrou na guilda!"
		await _refresh()
	else:
		_show_error(r)

func _leave() -> void:
	if busy: return
	busy = true
	var r = await Api.guild_leave()
	busy = false
	if r.get("ok"):
		await _refresh()
	else:
		_show_error(r)

func _disband() -> void:
	if busy: return
	busy = true
	var r = await Api.guild_disband()
	busy = false
	if r.get("ok"):
		await _refresh()
	else:
		_show_error(r)

func _kick(pid: int) -> void:
	if busy: return
	busy = true
	var r = await Api.guild_kick(pid)
	busy = false
	if r.get("ok"):
		status.text = "Membro expulso."
		await _refresh()
	else:
		_show_error(r)

func _transfer(pid: int) -> void:
	if busy: return
	busy = true
	var r = await Api.guild_transfer(pid)
	busy = false
	if r.get("ok"):
		status.text = "Liderança transferida."
		await _refresh()
	else:
		_show_error(r)

func _donate() -> void:
	if busy: return
	if donate_gold == null: return
	var amount := int(donate_gold.value) * 10000 + int(donate_silver.value) * 100 + int(donate_bronze.value)
	if amount <= 0:
		status.text = "Informe um valor válido."
		return
	busy = true
	var r = await Api.guild_donate(amount)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		if bool(j.get("leveledUp", false)):
			status.text = "🎉 A doação subiu a guilda para o nível %d!" % int(j.get("level", 0))
		else:
			status.text = "Doado! Tesouro: %s" % _fmt_bronze(int(j.get("guildGold", 0)))
		await _refresh()
	else:
		_show_error(r)

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("error", j.get("message", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

# ── helpers ────────────────────────────────────────────────────────────────────────
func _fmt_bronze(total: int) -> String:
	var gold := total / 10000
	var silver := (total % 10000) / 100
	var bronze := total % 100
	var parts: Array = []
	if gold > 0: parts.append("%d🥇" % gold)
	if silver > 0: parts.append("%d🥈" % silver)
	if bronze > 0 or parts.is_empty(): parts.append("%d🥉" % bronze)
	return " ".join(parts)

func _act(text: String, cb: Callable, minsize: Vector2 = Vector2(120, 0)) -> Button:
	var b := Button.new(); b.text = text; b.custom_minimum_size = minsize
	b.pressed.connect(cb)
	return b

func _spin(_hint: String) -> SpinBox:
	var s := SpinBox.new()
	s.min_value = 0; s.max_value = 999999; s.step = 1; s.value = 0
	s.custom_minimum_size = Vector2(90, 0)
	return s

func _labeled(text: String, node: Control) -> VBoxContainer:
	var box := VBoxContainer.new()
	var l := Label.new(); l.text = text; l.add_theme_font_size_override("font_size", 11); l.modulate = Color(1, 1, 1, 0.6)
	box.add_child(l); box.add_child(node)
	return box

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _kv(k: String, v: String) -> HBoxContainer:
	var row := HBoxContainer.new()
	var lk := Label.new(); lk.text = k; lk.custom_minimum_size = Vector2(170, 0); lk.modulate = Color(1, 1, 1, 0.7)
	var lv := Label.new(); lv.text = v
	row.add_child(lk); row.add_child(lv)
	return row

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

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
