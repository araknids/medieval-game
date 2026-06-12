extends Control
# ── Tela HABILIDADES DE CLASSE ────────────────────────────────────────────────────
# Lê GET /api/abilities: árvore da classe (passivas + ativas), pontos de habilidade
# (1/level), custo de respec. Aprende um nível (POST /api/abilities/learn/{id}) e
# reseta tudo (POST /api/abilities/respec). Volta pro Personagem (sinal go_back). [HABILIDADES]

signal go_back

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
	# header: ← voltar + título + re-sincronizar
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	var back := Button.new(); back.text = "←"; back.custom_minimum_size = Vector2(44, 36)
	back.pressed.connect(func() -> void: go_back.emit())
	header.add_child(back)
	var ttl := Label.new(); ttl.text = "Habilidades"; ttl.add_theme_font_size_override("font_size", 26)
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
	var r = await Api.get_abilities()
	if not (r.get("ok") and r.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	data = r["json"]
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	var pts := int(data.get("abilityPoints", 0))
	var abilities: Array = data.get("abilities", []) if data.get("abilities") is Array else []
	# Sem classe ainda (Recruta): só mostra os pontos guardados e o aviso.
	if abilities.is_empty():
		content.add_child(_section("✨ Habilidades"))
		content.add_child(_dim("Escolha uma classe (Path Trial no Nv.10) para destravar as habilidades dela."))
		content.add_child(_dim("Você tem %d ponto%s de habilidade guardado%s." % [pts, "" if pts == 1 else "s", "" if pts == 1 else "s"]))
		return
	# Cabeçalho da classe + pontos disponíveis
	var head := "✨ Habilidades — %s" % str(data.get("class", "?"))
	content.add_child(_section(head))
	if pts > 0:
		var pl := Label.new()
		pl.text = "⬆ %d ponto%s para gastar" % [pts, "" if pts == 1 else "s"]
		pl.modulate = Color(1.0, 0.8, 0.35)
		content.add_child(pl)
	content.add_child(_spacer(4))
	for a in abilities:
		if a is Dictionary:
			content.add_child(_ability_row(a, pts))
	# Respec
	content.add_child(_spacer(10))
	var respec := Button.new()
	respec.text = "↺ Resetar habilidades (%s)" % _fmt_bronze(int(data.get("respecCost", 0)))
	respec.pressed.connect(_respec)
	content.add_child(respec)

func _ability_row(a: Dictionary, pts: int) -> PanelContainer:
	var active := bool(a.get("active", false))
	var level := int(a.get("level", 0))
	var max_level := int(a.get("maxLevel", 0))
	var maxed := level >= max_level
	var col: Color = Color(0.48, 0.69, 1.0) if active else Color(0.6, 0.8, 0.6)
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1); sb.border_color = Color(col, 0.55)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	# ícone
	var icon := Label.new(); icon.text = str(a.get("icon", "•")); icon.custom_minimum_size = Vector2(28, 0)
	icon.add_theme_font_size_override("font_size", 18)
	hb.add_child(icon)
	# esquerda: nome + tipo + descrição
	var left := VBoxContainer.new(); left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new(); nm.text = str(a.get("displayName", "?")); nm.modulate = col
	nm.add_theme_font_size_override("font_size", 16)
	left.add_child(nm)
	var kind_txt := ""
	if active:
		kind_txt = "⚡ Ativa"
		var cd := int(a.get("cooldown", 0))
		if cd > 0:
			kind_txt += " · CD %d rounds" % cd
	else:
		kind_txt = "🪨 Passiva"
	var kl := Label.new(); kl.text = kind_txt; kl.modulate = Color(1, 1, 1, 0.6); kl.add_theme_font_size_override("font_size", 12)
	left.add_child(kl)
	var desc := str(a.get("description", ""))
	if desc != "":
		var dl := Label.new(); dl.text = desc; dl.modulate = Color(1, 1, 1, 0.55); dl.add_theme_font_size_override("font_size", 12)
		dl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		left.add_child(dl)
	hb.add_child(left)
	# direita: nível + botão de aprender
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 4)
	right.alignment = BoxContainer.ALIGNMENT_CENTER
	var lvl := Label.new(); lvl.text = "%d/%d" % [level, max_level]
	lvl.modulate = Color(1.0, 0.84, 0.0) if maxed else Color(0.93, 0.93, 0.93)
	lvl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	right.add_child(lvl)
	var btn := Button.new(); btn.text = "+"; btn.custom_minimum_size = Vector2(48, 0)
	btn.disabled = pts <= 0 or maxed
	btn.pressed.connect(_learn.bind(str(a.get("id", ""))))
	right.add_child(btn)
	hb.add_child(right)
	return panel

# ── Ações (1 chamada → re-sincroniza a árvore) ────────────────────────────────────
func _learn(id: String) -> void:
	if busy or id == "": return
	busy = true
	var r = await Api.ability_learn(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		status.text = str(r["json"].get("message", "Aprimorado!"))
		await _refresh()
	else:
		_show_error(r)

func _respec() -> void:
	if busy: return
	busy = true
	var r = await Api.ability_respec()
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		status.text = str(r["json"].get("message", "Habilidades resetadas."))
		await _refresh()
	else:
		_show_error(r)

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

# ── helpers de UI ─────────────────────────────────────────────────────────────────
func _fmt_bronze(n: int) -> String:
	# 100 bronze = 1 prata, 100 prata = 1 ouro. Mostra a moeda mais alta cabível.
	if n >= 10000:
		return "%d🥇" % (n / 10000)
	if n >= 100:
		return "%d🥈" % (n / 100)
	return "%d🥉" % n

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 20); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.5)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
