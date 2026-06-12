extends Control
# ── Tela TRABALHO (idle) ──────────────────────────────────────────────────────────
# [WORK_IDLE] Atividade idle com TIMER REAL: inicia (1/2/6/12h), espera o timer e coleta;
# pode cancelar p/ recompensa parcial. Enquanto trabalha o jogador fica travado de aventurar.
# Lê GET /api/work/current → se tem sessão mostra o PROGRESSO; senão lista os empregos
# (GET /api/work/jobs). Ações: start / collect / cancel. Espelha loadWork/showWorkJobList/
# renderWorkProgress do app.js. Volta pro Hub (sinal go_back). [MIGRACAO_GODOT]

signal go_back

const HOURS := [1, 2, 6, 12]

var content: VBoxContainer
var status: Label
var busy := false
var jobs: Array = []          # cache da lista de empregos
var session: Dictionary = {}  # sessão atual (vazio = sem trabalho ativo)
var _timer_left := 0          # segundos restantes p/ o countdown local
var _tick: Timer              # atualiza o countdown de 1 em 1s

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
	var ttl := Label.new(); ttl.text = "Trabalho"; ttl.add_theme_font_size_override("font_size", 26)
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
	# timer p/ o countdown do progresso (1Hz)
	_tick = Timer.new(); _tick.wait_time = 1.0; _tick.one_shot = false
	_tick.timeout.connect(_on_tick)
	add_child(_tick)
	await _refresh()

# Carrega o estado: primeiro a sessão atual (decide qual painel), depois a lista de empregos.
func _refresh() -> void:
	_tick.stop()
	status.text = "Carregando…"
	var cur = await Api.work_current()
	if not (cur.get("ok") and cur.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(cur.get("status", "?"))
		return
	var cj: Dictionary = cur["json"]
	# /current devolve {active:false} quando não há sessão; senão a WorkResponse (tem "id")
	if cj.has("id"):
		session = cj
		_render_progress()
		status.text = ""
		return
	session = {}
	var r = await Api.work_jobs()
	if not (r.get("ok") and r.get("json") is Array):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	jobs = r["json"]
	status.text = ""
	_render_jobs()

# ── PAINEL: lista de empregos ──────────────────────────────────────────────────────
func _render_jobs() -> void:
	_clear()
	content.add_child(_section("Empregos (%d)" % jobs.size()))
	if jobs.is_empty():
		content.add_child(_dim("— nenhum emprego —"))
		return
	for j in jobs:
		if j is Dictionary:
			content.add_child(_job_card(j))

func _job_card(job: Dictionary) -> PanelContainer:
	var locked := not bool(job.get("meetsLevelReq", true))
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1)
	sb.border_color = Color(0.5, 0.4, 0.25, 0.6) if not locked else Color(0.5, 0.5, 0.5, 0.35)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(10)
	panel.add_theme_stylebox_override("panel", sb)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 4)
	panel.add_child(box)
	# nome + nível da profissão (+bônus)
	var head := HBoxContainer.new()
	var nm := Label.new(); nm.text = str(job.get("displayName", job.get("id", "?")))
	nm.add_theme_font_size_override("font_size", 17)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	if locked:
		nm.modulate = Color(1, 1, 1, 0.55)
	head.add_child(nm)
	var lvl := Label.new()
	var bonus := int(job.get("bonusPct", 0))
	lvl.text = "Lv.%d%s" % [int(job.get("profLevel", 1)), ("  +%d%%" % bonus) if bonus > 0 else ""]
	lvl.modulate = Color(0.85, 0.75, 0.4)
	head.add_child(lvl)
	box.add_child(head)
	# barra de xp da profissão
	var px := int(job.get("profXp", 0))
	var pn := max(1, int(job.get("profXpNeeded", 1)))
	box.add_child(_bar(px, pn, Color(0.78, 0.6, 0.3)))
	# descrição
	var desc := Label.new(); desc.text = str(job.get("description", ""))
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	desc.modulate = Color(1, 1, 1, 0.55); desc.add_theme_font_size_override("font_size", 12)
	box.add_child(desc)
	# stats: ouro/h + xp/h
	var stats := Label.new()
	stats.text = "🪙 %d/h    ⭐ %d xp/h" % [int(job.get("goldPerHourWithBonus", 0)), int(job.get("xpPerHour", 0))]
	stats.add_theme_font_size_override("font_size", 12); stats.modulate = Color(0.8, 0.85, 0.7)
	box.add_child(stats)
	if locked:
		var req := Label.new()
		req.text = "🔒 Requer nível %d" % int(job.get("minWorkLevel", 1))
		req.modulate = Color(1, 0.6, 0.5); req.add_theme_font_size_override("font_size", 12)
		box.add_child(req)
	else:
		# botões de horas: cada um mostra o ganho total estimado
		var hrs := HBoxContainer.new(); hrs.add_theme_constant_override("separation", 6)
		var gph := int(job.get("goldPerHourWithBonus", 0))
		var wid := str(job.get("id", ""))
		for h in HOURS:
			var b := Button.new()
			b.text = "%dh\n🪙%d" % [h, gph * h]
			b.custom_minimum_size = Vector2(72, 44)
			b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			b.pressed.connect(_start.bind(wid, h))
			hrs.add_child(b)
		box.add_child(hrs)
	return panel

# ── PAINEL: progresso do trabalho ──────────────────────────────────────────────────
func _render_progress() -> void:
	_clear()
	_timer_left = int(session.get("secondsRemaining", 0))
	var done := _timer_left <= 0 or bool(session.get("readyToCollect", false))
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.14, 0.12, 0.12)
	sb.set_border_width_all(1); sb.border_color = Color(0.55, 0.43, 0.26, 0.7)
	sb.set_corner_radius_all(6); sb.set_content_margin_all(14)
	panel.add_theme_stylebox_override("panel", sb)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 8)
	panel.add_child(box)
	var nm := Label.new(); nm.text = str(session.get("jobName", "Trabalho"))
	nm.add_theme_font_size_override("font_size", 22)
	box.add_child(nm)
	var desc := Label.new(); desc.text = str(session.get("description", ""))
	desc.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	desc.modulate = Color(1, 1, 1, 0.55); desc.add_theme_font_size_override("font_size", 13)
	box.add_child(desc)
	var rew := Label.new()
	rew.text = "🪙 %d    ⭐ %d xp    ⏳ %dh" % [int(session.get("goldReward", 0)), int(session.get("xpReward", 0)), int(session.get("hours", 0))]
	rew.modulate = Color(0.85, 0.85, 0.6)
	box.add_child(rew)
	if not done:
		var hint := Label.new()
		hint.text = "Trabalhando… você não pode aventurar enquanto trabalha."
		hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		hint.modulate = Color(0.79, 0.66, 0.3); hint.add_theme_font_size_override("font_size", 12)
		box.add_child(hint)
	# countdown grande (atualizado pelo Timer)
	var clock := Label.new()
	clock.name = "Clock"
	clock.add_theme_font_size_override("font_size", 30)
	clock.text = "✓ Pronto!" if done else _fmt_time(_timer_left)
	clock.modulate = Color(0.5, 0.9, 0.5) if done else Color(0.9, 0.82, 0.45)
	box.add_child(clock)
	# botão coletar
	var sid := int(session.get("id", 0))
	var collect := Button.new()
	collect.name = "Collect"
	collect.text = "💰 Coletar dinheiro" if done else "Em andamento…"
	collect.custom_minimum_size = Vector2(0, 44)
	collect.disabled = not done
	collect.pressed.connect(_collect.bind(sid))
	box.add_child(collect)
	# botão cancelar (só enquanto em andamento)
	if not done:
		var cancel := Button.new()
		cancel.text = "Cancelar (recebe parcial)"
		cancel.custom_minimum_size = Vector2(0, 36)
		cancel.pressed.connect(_cancel.bind(sid))
		box.add_child(cancel)
	content.add_child(panel)
	if not done:
		_tick.start()

func _on_tick() -> void:
	_timer_left -= 1
	var clock := content.find_child("Clock", true, false) as Label
	if clock == null:
		_tick.stop()
		return
	if _timer_left <= 0:
		_tick.stop()
		clock.text = "✓ Pronto!"
		clock.modulate = Color(0.5, 0.9, 0.5)
		var btn := content.find_child("Collect", true, false) as Button
		if btn != null:
			btn.disabled = false
			btn.text = "💰 Coletar dinheiro"
	else:
		clock.text = _fmt_time(_timer_left)

# ── Ações ──────────────────────────────────────────────────────────────────────────
func _start(work_type: String, hours: int) -> void:
	if busy: return
	busy = true
	var r = await Api.work_start(work_type, hours)
	if r.get("ok") and r.get("json") is Dictionary:
		session = r["json"]
		# defensivo: com timer real vem readyToCollect=false; se já veio pronto, coleta
		if bool(session.get("readyToCollect", false)):
			busy = false
			await _collect(int(session.get("id", 0)))
			return
		status.text = ""
		_render_progress()
	else:
		_show_error(r)
	busy = false

func _collect(session_id: int) -> void:
	if busy: return
	busy = true
	_tick.stop()
	var r = await Api.work_collect(session_id)
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = "⚒ Trabalho concluído! +🪙%d   +⭐%d XP (%s)" % [
			int(j.get("goldEarned", 0)), int(j.get("xpEarned", 0)), str(j.get("jobName", ""))]
		busy = false
		await _refresh()   # volta pra lista (guerreiro livre)
		return
	else:
		_show_error(r)
	busy = false

func _cancel(session_id: int) -> void:
	if busy: return
	busy = true
	_tick.stop()
	var r = await Api.work_cancel(session_id)
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		var earned := int(j.get("goldEarned", 0))
		if earned > 0:
			status.text = "Trabalho cancelado — parcial: +🪙%d   +⭐%d XP" % [earned, int(j.get("xpEarned", 0))]
		else:
			status.text = "Trabalho cancelado — nenhuma hora completa."
		busy = false
		await _refresh()
		return
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
func _clear() -> void:
	for c in content.get_children():
		c.queue_free()

func _fmt_time(secs: int) -> String:
	var s := max(0, secs)
	var h := s / 3600
	var mn := (s % 3600) / 60
	var ss := s % 60
	if h > 0:
		return "%d:%02d:%02d" % [h, mn, ss]
	return "%02d:%02d" % [mn, ss]

func _bar(value: int, maxv: int, col: Color) -> ProgressBar:
	var pb := ProgressBar.new()
	pb.min_value = 0; pb.max_value = max(1, maxv); pb.value = clampi(value, 0, maxv)
	pb.show_percentage = false
	pb.custom_minimum_size = Vector2(0, 10)
	var sb := StyleBoxFlat.new(); sb.bg_color = col; sb.set_corner_radius_all(3)
	pb.add_theme_stylebox_override("fill", sb)
	return pb

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4)
	return l
