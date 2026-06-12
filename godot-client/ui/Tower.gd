extends Control
# ── Tela TORRE INFERNAL ──────────────────────────────────────────────────────────
# Lê GET /api/tower/current: se houver run ativa mostra o andar (boss/atmosfera/stats) +
# botão ⚔ Lutar; senão mostra o lobby (custo 25⚡ + ⚔ Entrar). Lutar resolve no backend
# (POST /api/tower/fight) e mostra o RESULTADO em texto (recompensas + log) — sem 3D.
# Andar 50: a escolha do Rei Arka (poupar/matar). Ranking de melhores andares em baixo.
# Espelha loadTower/showTowerLobby/showTowerFloor do app.js. [MIGRACAO_GODOT]

signal go_back

const STAMINA_COST := 25

var content: VBoxContainer
var status: Label
var busy := false
var warrior: Dictionary = {}     # p/ saber estamina + destacar "me" no ranking
var state: Dictionary = {}       # GET /api/tower/current
var ranking: Array = []          # GET /api/tower/ranking
var last_result: Dictionary = {} # resultado da última luta (texto), se houver
var arka_pending := false        # escolha do Rei Arka no topo

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
	var ttl := Label.new(); ttl.text = "🏰 Torre Infernal"; ttl.add_theme_font_size_override("font_size", 26)
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
	content.add_theme_constant_override("separation", 6)
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	inner.add_child(content)
	await _refresh()

func _refresh() -> void:
	status.text = "Carregando…"
	# warrior (estamina) — não é fatal se falhar
	var rw = await Api.get_warrior()
	if rw.get("ok") and rw.get("json") is Dictionary:
		warrior = rw["json"]
	# estado da run
	var rc = await Api.tower_current()
	if not (rc.get("ok") and rc.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(rc.get("status", "?"))
		return
	state = rc["json"]
	# ranking
	var rr = await Api.tower_ranking()
	ranking = rr["json"] if (rr.get("ok") and rr.get("json") is Array) else []
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	# resultado da última luta (se houver) sempre no topo
	if not last_result.is_empty():
		_render_result()
	if arka_pending:
		_render_arka()
	elif bool(state.get("active", false)):
		_render_floor()
	else:
		_render_lobby()
	# ranking de melhores andares
	content.add_child(_spacer(10))
	_render_ranking()

# ── LOBBY (sem run ativa) ────────────────────────────────────────────────────────
func _render_lobby() -> void:
	var stamina := int(warrior.get("stamina", 0))
	var no_stamina := stamina < STAMINA_COST
	var box := _card(Color(0.45, 0.4, 0.3))
	var vb := box.get_child(0)
	var h := Label.new(); h.text = "⚔ Entrar na Torre"; h.add_theme_font_size_override("font_size", 19)
	h.modulate = Color(0.79, 0.66, 0.3)
	vb.add_child(h)
	var cost := Label.new()
	cost.text = "Custo: ⚡ %d estamina   ·   Sua estamina: %d/100" % [STAMINA_COST, stamina]
	cost.modulate = Color(1, 1, 1, 0.6); cost.add_theme_font_size_override("font_size", 12)
	vb.add_child(cost)
	var desc := Label.new()
	desc.text = "Lute andar por andar. Se perder, é expulso. Vá o mais longe que conseguir!"
	desc.modulate = Color(1, 1, 1, 0.5); desc.add_theme_font_size_override("font_size", 12)
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(desc)
	var enter := Button.new()
	enter.text = "⚔ Entrar" if not no_stamina else "Sem estamina"
	enter.custom_minimum_size = Vector2(160, 44)
	enter.disabled = no_stamina
	enter.pressed.connect(_enter)
	vb.add_child(enter)
	content.add_child(box)

# ── ANDAR (run ativa) ────────────────────────────────────────────────────────────
func _render_floor() -> void:
	var is_mvp := bool(state.get("isMvp", false))
	var border := Color(0.79, 0.66, 0.3) if is_mvp else Color(0.33, 0.33, 0.4)
	var box := _card(border)
	var vb := box.get_child(0)
	var cur := int(state.get("currentFloor", 1))
	var maxf := int(state.get("maxFloor", 0))
	var num := Label.new()
	num.text = "🏰 Andar %d%s" % [cur, ("  /  %d" % maxf) if maxf > 0 else ""]
	num.add_theme_font_size_override("font_size", 21); num.modulate = Color(0.79, 0.66, 0.3)
	vb.add_child(num)
	var highest := int(state.get("highestFloor", 0))
	if highest > 0:
		var hc := Label.new(); hc.text = "✓ Andar mais alto vencido: %d" % highest
		hc.modulate = Color(0.5, 0.85, 0.5); hc.add_theme_font_size_override("font_size", 12)
		vb.add_child(hc)
	var atmo := str(state.get("atmosphere", ""))
	if atmo != "":
		var al := Label.new(); al.text = atmo
		al.modulate = Color(0.6, 0.65, 0.7); al.add_theme_font_size_override("font_size", 12)
		al.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		vb.add_child(al)
	# boss
	var monsters: Array = state.get("monsters", []) if state.get("monsters") is Array else []
	var boss_name := str(monsters[0]) if monsters.size() > 0 else str(state.get("bossName", "?"))
	var bn := Label.new()
	bn.text = ("👑 " if is_mvp else "") + boss_name
	bn.add_theme_font_size_override("font_size", 17); bn.modulate = border
	vb.add_child(bn)
	if monsters.size() > 1:
		var names: Array = []
		for mob in monsters:
			names.append(str(mob))
		var gl := Label.new(); gl.text = "⚔ Gauntlet — %s" % " · ".join(names)
		gl.modulate = Color(0.8, 0.4, 0.6); gl.add_theme_font_size_override("font_size", 12)
		gl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		vb.add_child(gl)
	var rec := int(state.get("recommendedLevel", 0))
	if rec > 0:
		var rl := Label.new(); rl.text = "⚑ Nível recomendado %d+" % rec
		rl.modulate = Color(0.9, 0.64, 0.24); rl.add_theme_font_size_override("font_size", 12)
		vb.add_child(rl)
	var stats := Label.new()
	stats.text = "❤ %d HP    ⚔ %d ATK    🛡 %d DEF    🎯 AC %d" % [
		int(state.get("bossHp", 0)), int(state.get("bossAtk", 0)),
		int(state.get("bossDef", 0)), int(state.get("bossAc", 0))]
	stats.modulate = Color(0.85, 0.85, 0.9); stats.add_theme_font_size_override("font_size", 13)
	vb.add_child(stats)
	var rew := Label.new()
	rew.text = "Recompensa: 🪙 %d bronze · ⭐ %d exp" % [cur * 40, cur * 20]
	rew.modulate = Color(0.8, 0.7, 0.4); rew.add_theme_font_size_override("font_size", 12)
	vb.add_child(rew)
	var fight := Button.new(); fight.text = "⚔ Lutar"; fight.custom_minimum_size = Vector2(160, 44)
	fight.pressed.connect(_fight)
	vb.add_child(fight)
	content.add_child(box)

# ── Escolha do Rei Arka (andar 50) ───────────────────────────────────────────────
func _render_arka() -> void:
	var box := _card(Color(0.79, 0.66, 0.3))
	var vb := box.get_child(0)
	var h := Label.new(); h.text = "👑 O Rei Cai"; h.add_theme_font_size_override("font_size", 19)
	h.modulate = Color(0.79, 0.66, 0.3)
	vb.add_child(h)
	var txt := Label.new()
	txt.text = "O Rei Arka cai de joelhos, a luz emprestada se apagando. Por um instante, o homem que "
	txt.text += "fundou um reino olha para você — e tem medo. \"Misericórdia\", ele sussurra. \"Por favor.\""
	txt.modulate = Color(0.8, 0.85, 0.85); txt.add_theme_font_size_override("font_size", 13)
	txt.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(txt)
	var spare := Button.new(); spare.text = "🕊 Poupá-lo"; spare.custom_minimum_size = Vector2(220, 40)
	spare.pressed.connect(_arka.bind(true))
	vb.add_child(spare)
	var strike := Button.new(); strike.text = "🗡 Executá-lo"; strike.custom_minimum_size = Vector2(220, 40)
	strike.pressed.connect(_arka.bind(false))
	vb.add_child(strike)
	var warn := Label.new(); warn.text = "Esta escolha é definitiva."
	warn.modulate = Color(0.5, 0.5, 0.5); warn.add_theme_font_size_override("font_size", 11)
	vb.add_child(warn)
	content.add_child(box)

# ── Resultado da última luta (texto) ─────────────────────────────────────────────
func _render_result() -> void:
	var won := bool(last_result.get("won", false))
	var floor_num := int(last_result.get("floor", 0))
	var border := Color(0.3, 0.7, 0.5) if won else Color(0.94, 0.33, 0.31)
	var box := _card(border)
	var vb := box.get_child(0)
	var h := Label.new()
	h.text = ("🏆 Andar %d vencido!" % floor_num) if won else ("💀 Derrotado no andar %d" % floor_num)
	h.add_theme_font_size_override("font_size", 18); h.modulate = border
	vb.add_child(h)
	if won:
		var br := Label.new(); br.text = "🪙 Bronze: %d" % int(last_result.get("bronzeEarned", 0))
		br.modulate = Color(0.8, 0.5, 0.2); vb.add_child(br)
		var xp := Label.new(); xp.text = "⭐ Experiência: +%d XP" % int(last_result.get("expEarned", 0))
		xp.modulate = Color(1, 0.84, 0); vb.add_child(xp)
	else:
		var d := Label.new(); d.text = "☠ Derrotado — cure-se no Templo"
		d.modulate = Color(0.94, 0.33, 0.31); vb.add_child(d)
	var note := str(last_result.get("atmosphere", ""))
	if note == "" and won and not bool(last_result.get("runOver", false)):
		note = "Chefe derrotado! Suba para o próximo andar quando quiser."
	if note != "":
		var nl := Label.new(); nl.text = note
		nl.modulate = Color(0.6, 0.65, 0.7); nl.add_theme_font_size_override("font_size", 12)
		nl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		vb.add_child(nl)
	# log da batalha (texto)
	var log: Array = last_result.get("log", []) if last_result.get("log") is Array else []
	if not log.is_empty():
		vb.add_child(_spacer(4))
		var lt := Label.new(); lt.text = "— Log da batalha —"
		lt.modulate = Color(1, 1, 1, 0.4); lt.add_theme_font_size_override("font_size", 11)
		vb.add_child(lt)
		for line in log:
			var ll := Label.new(); ll.text = str(line)
			ll.modulate = Color(0.78, 0.8, 0.85); ll.add_theme_font_size_override("font_size", 12)
			ll.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
			vb.add_child(ll)
	var dismiss := Button.new(); dismiss.text = "OK"; dismiss.custom_minimum_size = Vector2(120, 36)
	dismiss.pressed.connect(func() -> void: last_result = {}; _render())
	vb.add_child(dismiss)
	content.add_child(box)
	content.add_child(_spacer(8))

# ── Ranking de melhores andares ──────────────────────────────────────────────────
func _render_ranking() -> void:
	content.add_child(_section("🏰 Ranking — Melhores Andares"))
	if ranking.is_empty():
		content.add_child(_dim("— nenhum registro ainda —"))
		return
	var my_name := str(warrior.get("name", ""))
	var i := 0
	for r in ranking:
		if not (r is Dictionary):
			continue
		i += 1
		var name := str(r.get("warriorName", "?"))
		var title := str(r.get("title", ""))
		var mine := name == my_name
		var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
		var pos := Label.new(); pos.text = "%d." % i; pos.custom_minimum_size = Vector2(34, 0)
		pos.modulate = Color(1, 1, 1, 0.6)
		row.add_child(pos)
		var nm := Label.new()
		nm.text = (title + " " if title != "" else "") + name
		nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		if mine:
			nm.modulate = Color(1, 0.85, 0.4)
		row.add_child(nm)
		var fl := Label.new(); fl.text = "🏰 %d" % int(r.get("bestFloor", 0))
		fl.modulate = Color(0.79, 0.66, 0.3)
		row.add_child(fl)
		content.add_child(row)

# ── Ações async ──────────────────────────────────────────────────────────────────
func _enter() -> void:
	if busy: return
	busy = true
	status.text = "Entrando…"
	var r = await Api.tower_enter()
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); busy = false; return
	# o app.js luta automaticamente ao entrar
	busy = false
	await _do_fight()

func _fight() -> void:
	if busy: return
	await _do_fight()

func _do_fight() -> void:
	if busy: return
	busy = true
	status.text = "Lutando…"
	var r = await Api.tower_fight()
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); busy = false; return
	var data: Dictionary = r["json"]
	last_result = data
	arka_pending = bool(data.get("arkaChoicePending", false))
	status.text = ""
	# re-sincroniza estamina/HP + próximo andar (ou lobby se perdeu)
	var rw = await Api.get_warrior()
	if rw.get("ok") and rw.get("json") is Dictionary:
		warrior = rw["json"]
	var rc = await Api.tower_current()
	if rc.get("ok") and rc.get("json") is Dictionary:
		state = rc["json"]
	var rr = await Api.tower_ranking()
	ranking = rr["json"] if (rr.get("ok") and rr.get("json") is Array) else ranking
	busy = false
	_render()

func _arka(spare: bool) -> void:
	if busy: return
	busy = true
	status.text = "…"
	var r = await Api.tower_arka(spare)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); return
	arka_pending = false
	# substitui o resultado pelo desfecho narrativo da escolha
	last_result = {
		"won": true,
		"floor": int(state.get("currentFloor", 0)),
		"bronzeEarned": 0,
		"expEarned": 0,
		"runOver": true,
		"atmosphere": str(r["json"].get("message", "")),
		"log": [("🏆 Título desbloqueado: %s" % ("O Misericordioso" if spare else "Regicida"))],
	}
	status.text = ""
	_render()

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

# ── helpers de UI ────────────────────────────────────────────────────────────────
# card = PanelContainer com borda colorida → primeiro filho é o VBox de conteúdo
func _card(border: Color) -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.11, 0.1, 0.13)
	sb.set_border_width_all(1); sb.border_color = Color(border, 0.7)
	sb.set_corner_radius_all(6)
	sb.set_content_margin_all(12)
	panel.add_theme_stylebox_override("panel", sb)
	var vb := VBoxContainer.new(); vb.add_theme_constant_override("separation", 6)
	vb.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	panel.add_child(vb)
	return panel

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4)
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
