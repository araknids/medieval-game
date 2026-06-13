extends Control
# ── Tela RECOMPENSA DIÁRIA ([DAILY]) ───────────────────────────────────────────────
# Lê GET /api/daily-reward/status (ciclo de 7 dias de peixe de stamina + streak) + /api/warrior
# (carteira do header) e mostra o calendário; reivindica com POST /api/daily-reward/claim.
# Espelha loadDailyReward / renderDailyCalendar / claimDailyReward do app.js. Padrão visual:
# UiKit [PADRAO_UI_GODOT]. Volta pro Hub (sinal go_back). [MIGRACAO_GODOT]

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var data: Dictionary = {}
var warrior: Dictionary = {}
var busy := false

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🎁 Diário", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_SOCIAL)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/daily-reward/status", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	data = r["json"]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	var streak := int(data.get("streak", 0))
	var claim_day := int(data.get("claimDay", 1))
	var can_claim := bool(data.get("canClaim", false))
	# streak
	content.add_child(UiKit.section("Recompensa Diária"))
	var streak_lbl := Label.new()
	streak_lbl.text = "🔥 Sequência: %d" % streak
	streak_lbl.add_theme_font_size_override("font_size", 18)
	streak_lbl.add_theme_color_override("font_color", UiKit.GOLD)
	content.add_child(streak_lbl)
	# botão de reivindicar / aviso (prominente, no topo)
	if can_claim:
		content.add_child(UiKit.action_big("🎁 Reivindicar", _claim if not busy else Callable()))
	else:
		content.add_child(UiKit.dim("Volte amanhã para a próxima recompensa."))
	# calendário (grid de 7 dias)
	content.add_child(UiKit.section("Ciclo de 7 dias"))
	var grid := GridContainer.new()
	grid.columns = 4
	grid.add_theme_constant_override("h_separation", 8)
	grid.add_theme_constant_override("v_separation", 8)
	content.add_child(grid)
	var days: Array = data.get("days", []) if data.get("days") is Array else []
	for d in days:
		if d is Dictionary:
			grid.add_child(_day_card(d, claim_day, can_claim))

func _day_card(d: Dictionary, claim_day: int, can_claim: bool) -> PanelContainer:
	var day := int(d.get("day", 0))
	var is_today := day == claim_day
	var past := day < claim_day
	# borda dourada hoje · bronze passado/futuro; card "desligado" p/ dias travados
	var border := UiKit.GOLD if is_today else (UiKit.GOLD_SOFT if past else UiKit.BRONZE)
	var locked := not is_today and not past
	var res := UiKit.card(border, not locked)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	pc.custom_minimum_size = Vector2(96, 0)
	if is_today:
		var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
		sb.set_border_width_all(2)
	var dlbl := Label.new(); dlbl.text = "Dia %d" % day
	dlbl.add_theme_font_size_override("font_size", 12)
	dlbl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	dlbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(dlbl)
	var fish := Label.new(); fish.text = "🐟"
	fish.add_theme_font_size_override("font_size", 22)
	fish.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(fish)
	var nm := Label.new()
	nm.text = "%s ×%d" % [str(d.get("fishName", "?")), int(d.get("qty", 0))]
	nm.add_theme_font_size_override("font_size", 12)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	nm.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	box.add_child(nm)
	var bronze := int(d.get("bronze", 0))
	if bronze > 0:
		var bl := UiKit.coin_box(bronze, 14)   # [MOEDA] ícone pixel-art (era "+N 🥉")
		bl.alignment = BoxContainer.ALIGNMENT_CENTER
		bl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		box.add_child(bl)
	if is_today:
		var tag := Label.new()
		tag.text = "◀ hoje" if can_claim else "✔"
		tag.add_theme_font_size_override("font_size", 11)
		tag.add_theme_color_override("font_color", UiKit.OK if not can_claim else UiKit.GOLD)
		tag.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		box.add_child(tag)
	elif past:
		var tag := Label.new()
		tag.text = "✔"
		tag.add_theme_font_size_override("font_size", 11)
		tag.add_theme_color_override("font_color", UiKit.OK)
		tag.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		box.add_child(tag)
	return pc

func _claim() -> void:
	if busy: return
	busy = true
	var r = await Api.daily_claim()
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		# espelha o showCollectModal do app.js, em texto
		var parts: Array = ["🐟 %s ×%d" % [str(j.get("fishName", "?")), int(j.get("qty", 0))]]
		if int(j.get("bronze", 0)) > 0:
			parts.append("🥉 %d Bronze" % int(j.get("bronze", 0)))
		if int(j.get("mailed", 0)) > 0:
			parts.append("📬 %d por correio (mochila cheia)" % int(j.get("mailed", 0)))
		await _refresh()
		UiKit.flash(status, "🎁 Recebido! 🔥 %d   —   %s" % [int(j.get("streak", 0)), "   ".join(parts)], 1)
	else:
		UiKit.show_error(status, r)
