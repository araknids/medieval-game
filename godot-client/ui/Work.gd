extends Control
# ── Tela TRABALHO (idle) ──────────────────────────────────────────────────────────
# [WORK_IDLE] Atividade idle com TIMER REAL: inicia (1/2/6/12h), espera o timer e coleta;
# pode cancelar p/ recompensa parcial. Enquanto trabalha o jogador fica travado de aventurar.
# Lê GET /api/work/current → se tem sessão mostra o PROGRESSO; senão lista os empregos
# (GET /api/work/jobs). Ações: start / collect / cancel. Espelha loadWork/showWorkJobList/
# renderWorkProgress do app.js. Padrão visual: UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]

signal go_back

const HOURS := [1, 2, 6, 12]
const Icons := preload("res://ui/Icons.gd")  # [WORK_GIF] GIF de trabalho no hover

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var jobs: Array = []          # cache da lista de empregos
var session: Dictionary = {}  # sessão atual (vazio = sem trabalho ativo)
var warrior: Dictionary = {}  # /api/warrior (carteira do header)
var warrior_level := 1
var training: Dictionary = {} # [TRAINING] estado do Training Hall (/api/world/COMBAT/training) — movido pra cá
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
	UiKit.show_loading(self)
	# [AUDIT] /api/work/jobs entrou no batch (paralelo) — antes era um await sequencial extra no caminho sem-sessão
	var rs = await Api.batch_get(["/api/work/current", "/api/warrior", "/api/world/COMBAT/training", "/api/work/jobs"])
	var cur = rs[0]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	warrior_level = int(warrior.get("level", 1))
	var tr = rs[2]
	training = tr["json"] if (tr.get("ok") and tr.get("json") is Dictionary) else {}
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
	var r = rs[3]   # jobs já veio no batch acima (paralelo)
	if not (r.get("ok") and r.get("json") is Array):
		UiKit.show_error(status, r)
		return
	jobs = r["json"]
	_render_jobs()

# ── PAINEL: Training Hall (topo) + lista de empregos ────────────────────────────────
func _render_jobs() -> void:
	_clear()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	_build_training_section()   # [TRAINING] Training Hall EM CIMA do trabalho (movido do Mundo)
	# [DIARIO_QUEST] A tela de Trabalho é SÓ de empregos — sem botão de quest aqui (era o que piscava ao aceitar
	# a 3ª quest). A 3ª quest pega-se pelo Diário de Missões (o badge de missão no topbar guia até lá).
	content.add_child(UiKit.section(Lang.t("Empregos (%d)") % jobs.size()))
	if jobs.is_empty():
		content.add_child(UiKit.empty("Nenhum emprego disponível", "Suba de nível para destravar novos trabalhos."))
		return
	# grid COMPACTO (até 3 col, cards baixos) → cabe tudo sem scroll [UI_TRABALHO]
	content.add_child(UiKit.grid(self, jobs, func(j): return _job_card(j) if j is Dictionary else null, false, 280, 3))

# [UI_TRABALHO] Card de emprego COMPACTO: nome+nível · barra de XP fina · rendimento/h · botões de hora.
# Descrição vira TOOLTIP do card; cada botão de hora tem TOOLTIP com o ganho total + XP. [hover]
func _job_card(job: Dictionary) -> PanelContainer:
	var locked := not bool(job.get("meetsLevelReq", true))
	var res := UiKit.card(UiKit.BRONZE if not locked else Color(0.5, 0.5, 0.5, 0.35), not locked)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	box.add_theme_constant_override("separation", 4)
	var desc := str(job.get("description", ""))
	if desc != "":
		pc.tooltip_text = desc   # hover no card = o que o trabalho faz
	# linha 1: nome + nível (+bônus)
	var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 8)
	# [WORK_GIF] ícone do trabalho à esquerda do nome — para parado no frame 0, anima no hover do card (anim/work_<id>/)
	var anim_key := "work_" + str(job.get("id", "")).to_lower()
	if not Icons.frames(anim_key).is_empty():
		var icon := TextureRect.new()
		icon.custom_minimum_size = Vector2(46, 46)
		icon.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		icon.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		icon.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
		icon.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		icon.mouse_filter = Control.MOUSE_FILTER_IGNORE
		Icons.anim_rect(pc, icon, anim_key)   # hover no card → cicla os frames in-place
		head.add_child(icon)
	var nm := Label.new()
	nm.text = str(job.get("displayName", job.get("id", "?")))
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.clip_text = true
	if desc != "":
		nm.tooltip_text = desc   # hover no NOME = descrição (mais confiável que só no card)
	head.add_child(nm)
	var bonus := int(job.get("bonusPct", 0))
	var lvl := Label.new()
	lvl.text = "Lv.%d%s" % [int(job.get("profLevel", 1)), ("  +%d%%" % bonus) if bonus > 0 else ""]
	lvl.add_theme_font_size_override("font_size", 13)
	lvl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	head.add_child(lvl)
	box.add_child(head)
	# linha 2: barra de XP da profissão FINA (sem rótulo) — tooltip com os números
	var px := int(job.get("profXp", 0))
	var pn := maxi(1, int(job.get("profXpNeeded", 1)))
	var xpbar := _thin_bar(px, pn, Color(0.78, 0.6, 0.3))
	xpbar.tooltip_text = Lang.t("XP da profissão: %d / %d") % [px, pn]
	box.add_child(xpbar)
	# linha 3: rendimento por hora (compacto)
	var gph := int(job.get("goldPerHourWithBonus", 0))
	var rend := HBoxContainer.new(); rend.add_theme_constant_override("separation", 5)
	rend.add_child(UiKit.coin_box(gph, 14))
	var rx := Label.new(); rx.text = Lang.t("/h · ⭐%d/h") % int(job.get("xpPerHour", 0))
	rx.add_theme_font_size_override("font_size", 12); rx.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	rx.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rend.add_child(rx)
	box.add_child(rend)
	if locked:
		var req := Label.new()
		req.text = Lang.t("🔒 Requer nível %d") % int(job.get("minWorkLevel", 1))
		req.add_theme_font_size_override("font_size", 12)
		req.add_theme_color_override("font_color", UiKit.ERR)
		box.add_child(req)
	else:
		# linha 4: botões de hora compactos — TOOLTIP com ganho total + XP [hover]
		var hrs := HBoxContainer.new(); hrs.add_theme_constant_override("separation", 5)
		var wid := str(job.get("id", ""))
		var xph := int(job.get("xpPerHour", 0))
		for h in HOURS:
			var b := UiKit.small_btn("%dh" % h, _start.bind(wid, h))
			b.custom_minimum_size = Vector2(0, 34)
			b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			b.tooltip_text = Lang.t("Trabalhar %dh · ganha %s · +%d XP de profissão") % [h, UiKit.coin_str(gph * h), xph * h]
			hrs.add_child(b)
		box.add_child(hrs)
	return pc

# [TRAINING] Training Hall (movido do Mundo). Duas vias: IDLE grátis (timer real, ocupa o guerreiro, XP
# modesto) e PAGO instantâneo (bronze → XP na hora). [TREINO_IDLE]
func _build_training_section() -> void:
	# [SEM_SCROLL] título DENTRO do card (era uma seção externa, que adicionava spacer+régua acima e
	# empurrava tudo pra baixo gerando o scroll). Sem ela, o card cola na régua do "Trabalho".
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var box: VBoxContainer = res[1]
	box.add_theme_constant_override("separation", 0)
	# [SEM_SCROLL] card em 2 colunas de MESMA altura: ESQ = título + GIF (o gif estica p/ a altura da DIR);
	# DIR = textos temáticos + botões. Sem título full-width em cima → card bem mais curto.
	var outer := HBoxContainer.new(); outer.add_theme_constant_override("separation", 14)
	box.add_child(outer)
	var left := VBoxContainer.new(); left.add_theme_constant_override("separation", 4)
	left.size_flags_vertical = Control.SIZE_FILL
	outer.add_child(left)
	var ttl := Label.new()
	ttl.text = "Training Hall"   # pt: "Salão de Treino" via Lang PT_OVERRIDE (auto_translate do Label)
	ttl.add_theme_font_size_override("font_size", 16)
	ttl.add_theme_color_override("font_color", UiKit.GOLD)
	left.add_child(ttl)
	if not Icons.frames("training_hall").is_empty():
		var ic := TextureRect.new()
		ic.custom_minimum_size = Vector2(128, 56)   # largura fixa; a ALTURA preenche (= coluna direita)
		ic.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		ic.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		ic.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
		ic.size_flags_vertical = Control.SIZE_EXPAND_FILL   # gif estica p/ casar a altura dos botões+textos
		ic.mouse_filter = Control.MOUSE_FILTER_IGNORE
		Icons.anim_rect(res[0], ic, "training_hall")   # frame 0 parado; cicla no hover do card
		left.add_child(ic)
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 4)
	col.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	col.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	outer.add_child(col)
	if bool(training.get("active", false)):
		_train_active(col)
	else:
		var total_bronze := int(warrior.get("gold", 0)) * 10000 + int(warrior.get("silver", 0)) * 100 + int(warrior.get("bronze", 0))
		# ── IDLE: paga com TEMPO (ocupa o guerreiro), grátis. Texto TEMÁTICO; o custo (horas) está no botão. ──
		col.add_child(UiKit.dim("Fique treinando no boneco ao seu ritmo"))
		var idle_row := HBoxContainer.new(); idle_row.add_theme_constant_override("separation", 5)
		for h: int in [1, 4, 8]:
			var fxp := warrior_level * 10 * h
			var fb := UiKit.small_btn("+%d XP\n%dh" % [fxp, h], _train_start.bind(h, true))
			fb.custom_minimum_size = Vector2(0, 40); fb.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			fb.add_theme_font_size_override("font_size", 12)
			fb.tooltip_text = Lang.t("Treina %dh de graça → +%d XP. Enquanto treina, não pode aventurar.") % [h, fxp]
			idle_row.add_child(fb)
		col.add_child(idle_row)
		# ── PAGO: paga com BRONZE, XP na hora. Texto TEMÁTICO; o custo (bronze) está no botão. ──
		col.add_child(UiKit.dim("Ou contrate um mestre de armas pela lição"))
		var paid_row := HBoxContainer.new(); paid_row.add_theme_constant_override("separation", 5)
		for tier: int in [1, 4, 12]:
			var cost := warrior_level * 10 * tier
			var pxp := warrior_level * 25 * tier
			var pb := UiKit.small_btn("+%d XP\n%s" % [pxp, UiKit.coin_str(cost)], _train_start.bind(tier, false))
			pb.custom_minimum_size = Vector2(0, 40); pb.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			pb.add_theme_font_size_override("font_size", 12)
			pb.disabled = total_bronze < cost
			pb.tooltip_text = Lang.t("Compra +%d XP por %s (na hora)") % [pxp, UiKit.coin_str(cost)]
			paid_row.add_child(pb)
		col.add_child(paid_row)
	content.add_child(res[0])

# [TREINO_IDLE] Treino EM ANDAMENTO: idle mostra timer + barra; pronto/pago mostra coletar. Cancelar sempre.
func _train_active(box: VBoxContainer) -> void:
	var is_free := bool(training.get("free", false))
	var ready := bool(training.get("readyToCollect", false))
	var sid := int(training.get("id", 0))
	var secs := int(training.get("secondsRemaining", 0))
	var info := Label.new()
	if is_free and not ready:
		info.text = Lang.t("Treinando (idle): +%d XP · faltam %s") % [int(training.get("xpReward", 0)), _fmt_train_time(secs)]
	else:
		info.text = Lang.t("Treino pronto: +%d XP") % int(training.get("xpReward", 0))
	info.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	box.add_child(info)
	if is_free and not ready:
		var total := maxi(1, int(training.get("hours", 0)) * 3600)
		box.add_child(_thin_bar(total - secs, total, UiKit.GOLD))
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8); row.alignment = BoxContainer.ALIGNMENT_END
	if ready:
		row.add_child(UiKit.small_btn("⭐ Coletar XP", _train_collect.bind(sid)))
	var xb := UiKit.small_btn("✖", _train_cancel.bind(sid), true)
	xb.tooltip_text = Lang.t("Cancelar o treino")
	row.add_child(xb)
	box.add_child(row)

func _fmt_train_time(s: int) -> String:
	var hh := s / 3600; var mm := (s % 3600) / 60
	if hh > 0: return "%dh %dmin" % [hh, mm]
	if mm > 0: return "%dmin" % mm
	return "%ds" % maxi(s, 0)

# Barra de progresso FINA (10px, sem rótulo) p/ a XP de profissão nos cards compactos.
func _thin_bar(value: int, maxv: int, fill: Color) -> ProgressBar:
	var pb := ProgressBar.new()
	var cap := maxi(maxv, 1)
	pb.min_value = 0; pb.max_value = cap; pb.value = clampi(value, 0, cap)
	pb.show_percentage = false
	pb.custom_minimum_size = Vector2(0, 10)
	var bg := StyleBoxFlat.new()
	bg.bg_color = Color(0.05, 0.045, 0.06); bg.set_border_width_all(1)
	bg.border_color = Color(0.40, 0.32, 0.20, 0.6); bg.set_corner_radius_all(2)
	var fg := StyleBoxFlat.new(); fg.bg_color = fill; fg.set_corner_radius_all(2)
	pb.add_theme_stylebox_override("background", bg)
	pb.add_theme_stylebox_override("fill", fg)
	return pb

# ── Training Hall: ações (treino é INSTANTÂNEO; start→collect direto) ───────────────
func _train_start(hours: int, free := false) -> void:
	if busy: return
	busy = true
	var r = await Api.training_start(hours, free)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		if free:
			await _refresh()   # [TREINO_IDLE] timer rodando → mostra o estado (NÃO coleta na hora)
			UiKit.flash(status, Lang.t("Treino idle iniciado — o guerreiro está ocupado por %dh.") % hours, 0)
		else:
			await _train_collect(int(r["json"].get("id", 0)))   # pago: coleta instantânea
	else:
		UiKit.show_error(status, r)
		await _refresh()

func _train_collect(session_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.training_collect(session_id)
	var xp := -1
	if r.get("ok") and r.get("json") is Dictionary:
		xp = int(r["json"].get("xpEarned", 0))
	else:
		UiKit.show_error(status, r)
	busy = false
	await _refresh()
	if xp >= 0:
		UiKit.reward_toast(self, Lang.t("🏋 Treino completo!"), [["star", "+%d XP" % xp]])

func _train_cancel(session_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.training_cancel(session_id)
	busy = false
	await _refresh()
	# [TREINO_CANCEL_FIX] antes engolia a falha e flashava "cancelado" mesmo em erro → o treino ficava
	# IN_PROGRESS e o guard busy_training travava aventurar. Agora reflete o resultado real.
	if r.get("ok"):
		UiKit.flash(status, Lang.t("Treino cancelado."), 0)
	else:
		UiKit.show_error(status, r)

# ── PAINEL: progresso do trabalho ──────────────────────────────────────────────────
func _render_progress() -> void:
	_clear()
	UiKit.hide_loading()
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
		UiKit.hide_loading()
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
