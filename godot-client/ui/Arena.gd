extends Control
# ── Tela ARENA (PvP por ranking) ───────────────────────────────────────────────
# GET /api/arena/rank → top 20 (nome, título, pontos, V/D). Botão "Lutar" dispara
# POST /api/arena/fight (duelo instantâneo) e mostra o RESULTADO/log em texto — sem
# lançar o 3D (espelha o showCollectModal do app.js). Volta pro Hub (go_back). [MIGRACAO_GODOT]

signal go_back

const STAMINA_COST := 25

var content: VBoxContainer
var status: Label
var busy := false
var rank: Array = []          # cache do ranking
var w: Dictionary = {}        # warrior (p/ estamina / nome destacado no rank)
var last_result: Dictionary = {}   # resultado do último duelo (mostrado em texto)

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
	# header: ← voltar + título + sync
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	var back := Button.new(); back.text = "←"; back.custom_minimum_size = Vector2(44, 36)
	back.pressed.connect(func() -> void: go_back.emit())
	header.add_child(back)
	var ttl := Label.new(); ttl.text = "⚔️ Arena"; ttl.add_theme_font_size_override("font_size", 26)
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
	# warrior (p/ estamina + destacar meu nome no rank); ranking
	var rw = await Api.get_warrior()
	if rw.get("ok") and rw.get("json") is Dictionary:
		w = rw["json"]
	var rr = await Api.arena_rank()
	if not (rr.get("ok") and rr.get("json") is Array):
		status.text = "Erro ao carregar (%s)" % str(rr.get("status", "?"))
		return
	rank = rr["json"]
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	# ── Painel de luta ──
	var stamina := int(w.get("stamina", 100))
	var ko := bool(w.get("isKnockedOut", false))
	var no_stamina := stamina < STAMINA_COST
	content.add_child(_section("Entrar em batalha"))
	content.add_child(_dim("Custo: ⚡ %d estamina  ·  Sua estamina: %d/100" % [STAMINA_COST, stamina]))
	content.add_child(_dim("Duelo instantâneo. Vitória: +25 rank, ~200 bronze."))
	var fight := Button.new()
	fight.custom_minimum_size = Vector2(160, 44)
	if ko:
		fight.text = "💀 Nocauteado"
		fight.disabled = true
	elif no_stamina:
		fight.text = "⚡ Sem estamina"
		fight.disabled = true
	else:
		fight.text = "⚔ Lutar"
		fight.pressed.connect(_start_fight)
	content.add_child(fight)
	# ── Resultado do último duelo (texto, no lugar do canvas/replay) ──
	if not last_result.is_empty():
		content.add_child(_spacer(8))
		content.add_child(_result_box(last_result))
	# ── Ranking ──
	content.add_child(_spacer(10))
	content.add_child(_section("🏆 Ranking"))
	if rank.is_empty():
		content.add_child(_dim("— nenhum jogador ainda —"))
	else:
		var my_name := str(w.get("name", ""))
		var i := 0
		for r in rank:
			if r is Dictionary:
				i += 1
				content.add_child(_rank_row(i, r, str(r.get("warriorName", "")) == my_name))

# ── Resultado do duelo: título + recompensas + log de batalha (texto) ──
func _result_box(d: Dictionary) -> PanelContainer:
	var won := bool(d.get("won", false))
	var col: Color = Color(0.3, 0.8, 0.45) if won else Color(0.94, 0.33, 0.33)
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.11, 0.10, 0.13)
	sb.set_border_width_all(2); sb.border_color = col
	sb.set_corner_radius_all(7)
	sb.set_content_margin_all(12)
	panel.add_theme_stylebox_override("panel", sb)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 4)
	panel.add_child(box)
	var ttl := Label.new()
	var opp := str(d.get("opponent", "?"))
	ttl.text = ("🏆 Vitória vs %s!" % opp) if won else ("💀 Derrota para %s" % opp)
	ttl.modulate = col; ttl.add_theme_font_size_override("font_size", 18)
	box.add_child(ttl)
	var rc := int(d.get("rankChange", 0))
	var rl := Label.new()
	rl.text = "🏅 Rank: %s%d pts    🪙 Bronze: %d" % ["+" if rc > 0 else "", rc, int(d.get("goldEarned", 0))]
	box.add_child(rl)
	# log de batalha (linha a linha, descartando a tag WINNER:)
	var log: Array = d.get("log", []) if d.get("log") is Array else []
	if not log.is_empty():
		box.add_child(_spacer(4))
		for line in log:
			var s := str(line)
			if s.begins_with("WINNER:"):
				continue
			var ll := Label.new(); ll.text = s
			ll.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
			ll.modulate = Color(1, 1, 1, 0.78); ll.add_theme_font_size_override("font_size", 12)
			box.add_child(ll)
	return panel

func _rank_row(pos: int, r: Dictionary, is_me: bool) -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.15, 0.14, 0.10) if is_me else Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1)
	sb.border_color = Color(1.0, 0.8, 0.35, 0.7) if is_me else Color(1, 1, 1, 0.12)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(7)
	panel.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	panel.add_child(hb)
	var pl := Label.new(); pl.text = "#%d" % pos; pl.custom_minimum_size = Vector2(40, 0)
	pl.modulate = Color(1, 0.85, 0.4) if pos <= 3 else Color(1, 1, 1, 0.6)
	hb.add_child(pl)
	var title := str(r.get("title", ""))
	var nm := Label.new()
	nm.text = (title + " " if title != "" else "") + str(r.get("warriorName", "?"))
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	if is_me:
		nm.modulate = Color(1.0, 0.85, 0.4)
	hb.add_child(nm)
	var pts := Label.new(); pts.text = "%d pts" % int(r.get("rankPoints", 0)); pts.custom_minimum_size = Vector2(70, 0)
	hb.add_child(pts)
	var wl := Label.new(); wl.text = "%d/%d" % [int(r.get("wins", 0)), int(r.get("losses", 0))]
	wl.custom_minimum_size = Vector2(60, 0); wl.modulate = Color(1, 1, 1, 0.6)
	hb.add_child(wl)
	return panel

# ── Ação: duelo instantâneo. 1 chamada resolve tudo; guarda o resultado e re-renderiza. ──
func _start_fight() -> void:
	if busy: return
	busy = true
	status.text = "Lutando…"
	var r = await Api.arena_fight()
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		if j.has("error"):
			status.text = str(j.get("error"))
			return
		last_result = j
		status.text = ""
		# atualiza estamina/rank após a luta
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
func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.55); l.add_theme_font_size_override("font_size", 13)
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
