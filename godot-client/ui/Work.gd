extends Control
# ── Tela TRABALHO (idle) ──────────────────────────────────────────────────────────
# [WORK_IDLE] Atividade idle com TIMER REAL: inicia (1/2/6/12h), espera o timer e coleta;
# pode cancelar p/ recompensa parcial. Enquanto trabalha o jogador fica travado de aventurar.
# Lê GET /api/work/current → se tem sessão mostra o PROGRESSO; senão lista os empregos
# (GET /api/work/jobs). Ações: start / collect / cancel. Espelha loadWork/showWorkJobList/
# renderWorkProgress do app.js. Padrão visual: UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]

signal go_back

const HOURS := [1, 2, 6, 12]

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var jobs: Array = []          # cache da lista de empregos
var session: Dictionary = {}  # sessão atual (vazio = sem trabalho ativo)
var warrior: Dictionary = {}  # /api/warrior (carteira do header)
var _timer_left := 0          # segundos restantes p/ o countdown local
var _tick: Timer              # atualiza o countdown de 1 em 1s

func _ready() -> void:
	var ui := UiKit.scaffold(self, "💼 Trabalho", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	# timer p/ o countdown do progresso (1Hz)
	_tick = Timer.new(); _tick.wait_time = 1.0; _tick.one_shot = false
	_tick.timeout.connect(_on_tick)
	add_child(_tick)
	await _refresh()

# Carrega o estado: primeiro a sessão atual (decide qual painel), depois a lista de empregos.
func _refresh() -> void:
	_tick.stop()
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/work/current", "/api/warrior"])
	var cur = rs[0]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	if not (cur.get("ok") and cur.get("json") is Dictionary):
		UiKit.show_error(status, cur)
		return
	var cj: Dictionary = cur["json"]
	# /current devolve {active:false} quando não há sessão; senão a WorkResponse (tem "id")
	if cj.has("id"):
		session = cj
		_render_progress()
		return
	session = {}
	var r = await Api.work_jobs()
	if not (r.get("ok") and r.get("json") is Array):
		UiKit.show_error(status, r)
		return
	jobs = r["json"]
	_render_jobs()

# ── PAINEL: lista de empregos ──────────────────────────────────────────────────────
func _render_jobs() -> void:
	_clear()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	content.add_child(UiKit.section(Lang.t("Empregos (%d)") % jobs.size()))
	if jobs.is_empty():
		content.add_child(UiKit.empty("Nenhum emprego disponível", "Suba de nível para destravar novos trabalhos."))
		return
	# empregos em grid (2 col) p/ encurtar a lista; o painel de progresso fica fora disso
	content.add_child(UiKit.grid(self, jobs, func(j): return _job_card(j) if j is Dictionary else null))

func _job_card(job: Dictionary) -> PanelContainer:
	var locked := not bool(job.get("meetsLevelReq", true))
	var res := UiKit.card(UiKit.BRONZE if not locked else Color(0.5, 0.5, 0.5, 0.35), not locked)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	# nome + nível da profissão (+bônus)
	var head := HBoxContainer.new()
	head.add_theme_constant_override("separation", 8)
	var nm := Label.new()
	nm.text = str(job.get("displayName", job.get("id", "?")))
	nm.add_theme_font_size_override("font_size", 17)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	head.add_child(nm)
	var lvl := Label.new()
	var bonus := int(job.get("bonusPct", 0))
	lvl.text = "Lv.%d%s" % [int(job.get("profLevel", 1)), ("  +%d%%" % bonus) if bonus > 0 else ""]
	lvl.add_theme_font_size_override("font_size", 14)
	lvl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	head.add_child(lvl)
	box.add_child(head)
	# barra de xp da profissão
	var px := int(job.get("profXp", 0))
	var pn := maxi(1, int(job.get("profXpNeeded", 1)))
	box.add_child(UiKit.bar("XP da profissão", px, pn, Color(0.78, 0.6, 0.3)))
	# descrição
	box.add_child(UiKit.dim(str(job.get("description", ""))))
	# stats: rendimento/h + xp/h — [MOEDA] moeda em ícone pixel-art
	var rend := HBoxContainer.new(); rend.add_theme_constant_override("separation", 4)
	rend.add_child(UiKit.coin_box(int(job.get("goldPerHourWithBonus", 0)), 16))
	var rend_x := Label.new(); rend_x.text = Lang.t("/h    ⭐ %d xp/h") % int(job.get("xpPerHour", 0))
	rend_x.add_theme_font_size_override("font_size", 14); rend_x.add_theme_color_override("font_color", UiKit.TEXT)
	rend_x.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rend.add_child(rend_x)
	box.add_child(UiKit.kv_node("Rendimento", rend))
	if locked:
		var req := Label.new()
		req.text = Lang.t("🔒 Requer nível %d") % int(job.get("minWorkLevel", 1))
		req.add_theme_font_size_override("font_size", 12)
		req.add_theme_color_override("font_color", UiKit.ERR)
		box.add_child(req)
	else:
		# botões de horas: cada um mostra o ganho total estimado
		var hrs := HBoxContainer.new()
		hrs.add_theme_constant_override("separation", 6)
		var gph := int(job.get("goldPerHourWithBonus", 0))
		var wid := str(job.get("id", ""))
		for h in HOURS:
			var b := UiKit.action(Lang.t("%dh · 🪙%d") % [h, gph * h], _start.bind(wid, h))
			b.custom_minimum_size = Vector2(0, 44)
			b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			hrs.add_child(b)
		box.add_child(hrs)
	return pc

# ── PAINEL: progresso do trabalho ──────────────────────────────────────────────────
func _render_progress() -> void:
	_clear()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	_timer_left = int(session.get("secondsRemaining", 0))
	var done := _timer_left <= 0 or bool(session.get("readyToCollect", false))
	content.add_child(UiKit.section("Trabalhando"))
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var nm := Label.new()
	nm.text = str(session.get("jobName", "Trabalho"))
	nm.add_theme_font_size_override("font_size", 22)
	nm.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(nm)
	box.add_child(UiKit.dim(str(session.get("description", ""))))
	# [MOEDA] recompensa com moeda em ícone pixel-art
	var rew := HBoxContainer.new(); rew.add_theme_constant_override("separation", 4)
	rew.add_child(UiKit.coin_box(int(session.get("goldReward", 0)), 16))
	var rew_x := Label.new()
	rew_x.text = "⭐ %d xp    ⏳ %dh" % [int(session.get("xpReward", 0)), int(session.get("hours", 0))]
	rew_x.add_theme_font_size_override("font_size", 14)
	rew_x.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	rew_x.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rew.add_child(rew_x)
	box.add_child(rew)
	if not done:
		var hint := Label.new()
		hint.text = "Trabalhando… você não pode aventurar enquanto trabalha."
		hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		hint.add_theme_font_size_override("font_size", 12)
		hint.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		box.add_child(hint)
	# barra de progresso do trabalho (preenche conforme o tempo passa)
	var total := maxi(1, int(session.get("hours", 0)) * 3600)
	box.add_child(UiKit.bar("Progresso", total - _timer_left, total, Color(0.78, 0.6, 0.3)))
	# countdown grande (atualizado pelo Timer)
	var clock := Label.new()
	clock.name = "Clock"
	clock.add_theme_font_size_override("font_size", 30)
	clock.text = "✔ Pronto!" if done else _fmt_time(_timer_left)
	clock.add_theme_color_override("font_color", UiKit.OK if done else UiKit.WARN)
	box.add_child(clock)
	# botão coletar
	var sid := int(session.get("id", 0))
	var collect := UiKit.action_big("🪙 Coletar dinheiro" if done else "Em andamento…", _collect.bind(sid))
	collect.name = "Collect"
	collect.custom_minimum_size = Vector2(0, 44)
	collect.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	collect.disabled = not done
	box.add_child(collect)
	# botão cancelar (só enquanto em andamento) → confirma (perde progresso)
	if not done:
		var cancel := UiKit.action_danger("Cancelar (recebe parcial)", _confirm_cancel.bind(sid))
		cancel.custom_minimum_size = Vector2(0, 36)
		cancel.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		box.add_child(cancel)
	content.add_child(pc)
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
		clock.text = "✔ Pronto!"
		clock.add_theme_color_override("font_color", UiKit.OK)
		var btn := content.find_child("Collect", true, false) as Button
		if btn != null:
			btn.disabled = false
			btn.text = "🪙 Coletar dinheiro"
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
		UiKit.flash(status, "", 0)
		_render_progress()
	else:
		UiKit.show_error(status, r)
	busy = false

func _collect(session_id: int) -> void:
	if busy: return
	busy = true
	_tick.stop()
	var r = await Api.work_collect(session_id)
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		busy = false
		await _refresh()   # volta pra lista (guerreiro livre)
		# [MOEDA] goldEarned é em BRONZE (base)
		UiKit.flash(status, Lang.t("⚒ Trabalho concluído! +%s   +⭐%d XP (%s)") % [
			UiKit.coin_str(int(j.get("goldEarned", 0))), int(j.get("xpEarned", 0)), str(j.get("jobName", ""))], 1)
		return
	else:
		UiKit.show_error(status, r)
	busy = false

func _confirm_cancel(session_id: int) -> void:
	UiKit.confirm(self,
		"Cancelar o trabalho? Você perde o progresso da hora em andamento e recebe só as horas completas.",
		"Cancelar trabalho",
		func() -> void: await _cancel(session_id))

func _cancel(session_id: int) -> void:
	if busy: return
	busy = true
	_tick.stop()
	var r = await Api.work_cancel(session_id)
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		var earned := int(j.get("goldEarned", 0))
		busy = false
		await _refresh()
		if earned > 0:
			UiKit.flash(status, Lang.t("Trabalho cancelado — parcial: +%s   +⭐%d XP") % [UiKit.coin_str(earned), int(j.get("xpEarned", 0))], 1)
		else:
			UiKit.flash(status, "Trabalho cancelado — nenhuma hora completa.", 0)
		return
	else:
		UiKit.show_error(status, r)
	busy = false

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _clear() -> void:
	for c in content.get_children():
		c.queue_free()

func _fmt_time(secs: int) -> String:
	var s := maxi(0, secs)
	var h := s / 3600
	var mn := (s % 3600) / 60
	var ss := s % 60
	if h > 0:
		return "%d:%02d:%02d" % [h, mn, ss]
	return "%02d:%02d" % [mn, ss]
