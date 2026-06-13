extends Control
# ── Tela MUNDO / REINOS ───────────────────────────────────────────────────────────
# Lista os reinos (GET /api/world), abre um e mostra suas DAILY QUESTS + zonas de coleta/caça.
# Iniciar quest (start→collect direto, como o web) e coletar a recompensa; resultado/log em TEXTO
# (sem 3D). Coleta/caça de zona via /api/zones (enter→collect instantâneo). Espelha loadWorld /
# renderWorldOverview / renderKingdomDetail do app.js. Padrão visual: UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]

signal go_back

# Reinos de coleta/caça → as 3 zonas (tier SAFE/PVP/HIGH_RISK) que o web mostra em renderKingdomDetail.
# [name, tier, skillType("" p/ COMBAT), minLevel, role]
const ZONES := {
	"FISHING": [
		["🏖 Safe Shore", "SAFE", "FISHING", 1], ["🌊 Wild Coast", "PVP", "FISHING", 10], ["🦈 Deep Sea", "HIGH_RISK", "FISHING", 20],
	],
	"MAR_ABENCOADO": [
		["🌅 Sacred Cove", "SAFE", "FISHING", 1], ["🐠 Deep Reef", "PVP", "FISHING", 10], ["🔱 Blessed Abyss", "HIGH_RISK", "FISHING", 20],
	],
	"MINING": [
		["⛏ Open Mine", "SAFE", "MINING", 1], ["🪨 Deep Tunnels", "PVP", "MINING", 10], ["💎 Forbidden Mines", "HIGH_RISK", "MINING", 20],
	],
	"GRUTAS_DE_CRISTAL": [
		["🔎 Shallow Vein", "SAFE", "GARIMPO", 1], ["💠 Deep Grottoes", "PVP", "GARIMPO", 10], ["💎 Forbidden Cavern", "HIGH_RISK", "GARIMPO", 20],
	],
	"COMBAT": [
		["🏰 Haunted Courtyard", "SAFE", "", 1], ["⚔ Battlefield", "PVP", "", 10], ["🔥 War Zone", "HIGH_RISK", "", 20],
	],
}
const TIER_COL := {"SAFE": Color(0.30, 0.80, 0.30), "PVP": Color(1.0, 0.76, 0.0), "HIGH_RISK": Color(0.94, 0.33, 0.33)}
const ELEMENTS := [["FIRE", "🔥 Fire"], ["WATER", "💧 Water"], ["EARTH", "🪨 Earth"], ["AIR", "💨 Air"]]
const ZONE_DURATION := 20   # ação instantânea de tamanho fixo (~10⚡ via d/2), igual ao web

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var kingdoms: Array = []          # GET /api/world
var open_kingdom := ""            # reino expandido (só um por vez)
var warrior: Dictionary = {}      # /api/warrior (carteira + gate de nível)
var warrior_level := 1
var selected_element := "FIRE"    # picker de área de elemento
# detalhe do reino aberto (carregado sob demanda)
var quests: Array = []
var active_quests: Array = []
var training: Dictionary = {}
var zone_session: Dictionary = {}

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🌍 Mundo", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	# guerreiro (gate das zonas) + reinos em PARALELO — chamadas independentes
	var rs = await Api.batch_get(["/api/warrior", "/api/world"])
	var wr = rs[0]
	if wr.get("ok") and wr.get("json") is Dictionary:
		warrior = wr["json"]
		warrior_level = int(warrior.get("level", 1))
	var r = rs[1]
	if not (r.get("ok") and r.get("json") is Array):
		UiKit.show_error(status, r)
		return
	kingdoms = r["json"]
	# abre o 1º reino por padrão (sem cair numa parede de cards fechados), como o web
	if open_kingdom == "" and not kingdoms.is_empty() and kingdoms[0] is Dictionary:
		await _open(str(kingdoms[0].get("kingdom", "")))
	else:
		_render()

# Carrega o detalhe do reino (quests + zona ativa) e marca como aberto.
func _open(kingdom: String) -> void:
	if kingdom == "":
		return
	open_kingdom = kingdom
	UiKit.flash(status, "Abrindo %s…" % kingdom, 0)
	# dispara tudo em PARALELO (independentes); training só no COMBAT — máx. 4 = cabe no pool
	var has_training := kingdom == "COMBAT"
	var paths := ["/api/world/%s/quests" % kingdom, "/api/world/%s/quests/active" % kingdom, "/api/zones/current"]
	if has_training:
		paths.append("/api/world/COMBAT/training")
	var rs = await Api.batch_get(paths)
	var rq = rs[0]
	quests = rq["json"] if (rq.get("ok") and rq.get("json") is Array) else []
	var ra = rs[1]
	active_quests = ra["json"] if (ra.get("ok") and ra.get("json") is Array) else []
	var rz = rs[2]
	zone_session = rz["json"] if (rz.get("ok") and rz.get("json") is Dictionary) else {}
	var rt: Dictionary = {}
	if has_training:
		var rtr = rs[3]
		if rtr.get("ok") and rtr.get("json") is Dictionary:
			rt = rtr["json"]
	training = rt
	_render()

# "tem tarefa ativa pra coletar" neste reino → bloqueia começar outra (espelha os checks do backend).
func _has_active_task() -> bool:
	if not active_quests.is_empty():
		return true
	if zone_session.get("active", false):
		return true
	if training.get("active", false) and not training.get("readyToCollect", false):
		return true
	return false

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	for k in kingdoms:
		if k is Dictionary:
			content.add_child(_kingdom_card(k))

func _kingdom_card(k: Dictionary) -> PanelContainer:
	var kid := str(k.get("kingdom", ""))
	var is_open := kid == open_kingdom
	var is_mine := bool(k.get("isMine", false))
	var res := UiKit.card(UiKit.OK if is_mine else UiKit.BRONZE)
	var panel: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	# cabeçalho do card: clicar em QUALQUER parte da área visível alterna o reino.
	# header captura o clique (STOP); os labels deixam passar (IGNORE) → o detalhe abaixo fica fora dele,
	# então os botões de quest/zona continuam clicáveis.
	var header := VBoxContainer.new()
	header.add_theme_constant_override("separation", 4)
	header.mouse_filter = Control.MOUSE_FILTER_STOP
	header.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	header.gui_input.connect(func(ev: InputEvent) -> void:
		if ev is InputEventMouseButton and ev.pressed and ev.button_index == MOUSE_BUTTON_LEFT:
			_toggle(kid))
	box.add_child(header)
	# P0: chevron ▸/▾ deixa a afordância de clique visível
	var head := Label.new()
	head.text = "%s %s %s" % ["▾" if is_open else "▸", str(k.get("icon", "")), str(k.get("displayName", kid))]
	head.add_theme_font_size_override("font_size", 17)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	head.mouse_filter = Control.MOUSE_FILTER_IGNORE
	header.add_child(head)
	var ctrl := Label.new()
	var cg := str(k.get("controllingGuild", ""))
	ctrl.text = ("🛡 " + cg) if cg != "" else "Neutro"
	ctrl.add_theme_color_override("font_color", UiKit.OK if cg != "" else UiKit.TEXT_DIM)
	ctrl.add_theme_font_size_override("font_size", 12)
	ctrl.mouse_filter = Control.MOUSE_FILTER_IGNORE
	header.add_child(ctrl)
	if is_mine:
		var bonus := Label.new()
		bonus.text = "Sua guilda: +%d%% XP · +%d%% bronze · +%d%% bônus" % [int(k.get("xpBonus", 0)), int(k.get("bronzeBonus", 0)), int(k.get("exclusiveBonus", 0))]
		bonus.add_theme_color_override("font_color", UiKit.OK); bonus.add_theme_font_size_override("font_size", 11)
		bonus.mouse_filter = Control.MOUSE_FILTER_IGNORE
		header.add_child(bonus)
	var lore_text := str(k.get("lore", ""))
	if lore_text != "":
		var lore := UiKit.dim(lore_text)
		lore.mouse_filter = Control.MOUSE_FILTER_IGNORE
		header.add_child(lore)
	if is_open:
		box.add_child(UiKit.spacer(6))
		_build_detail(box, kid)
	return panel

# Detalhe do reino aberto: pvp banner + tarefas ativas + quests + zonas de coleta/caça.
func _build_detail(box: VBoxContainer, kingdom: String) -> void:
	box.add_child(HSeparator.new())
	# tarefa de zona ativa pendurada
	if zone_session.get("active", false):
		var zname := str(zone_session.get("zoneName", zone_session.get("zone", "")))
		var ready := bool(zone_session.get("readyToCollect", false))
		box.add_child(UiKit.dim("⚔ Expedição em andamento (%s)" % zname))
		if ready:
			box.add_child(UiKit.action("Coletar loot", _collect_zone.bind(int(zone_session.get("id", 0)))))
		else:
			box.add_child(UiKit.action_danger("✖ Cancelar expedição", _cancel_zone.bind(int(zone_session.get("id", 0)))))
	# quests ativas (coletar / abandonar)
	if not active_quests.is_empty():
		box.add_child(UiKit.section("Quests Ativas"))
		for q in active_quests:
			if q is Dictionary:
				var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
				var lbl := UiKit.body(str(q.get("displayName", "?")))
				lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
				row.add_child(lbl)
				var qid := int(q.get("id", 0))
				if bool(q.get("readyToCollect", false)):
					if bool(q.get("interactive", false)) and q.get("dialog") is Dictionary:
						var dlg: Dictionary = q["dialog"]   # interativa: re-abre a escolha
						row.add_child(UiKit.small_btn("Escolher", func() -> void: _show_quest_dialog(kingdom, qid, dlg)))
					else:
						row.add_child(UiKit.small_btn("Coletar", _collect_quest.bind(kingdom, qid)))
				else:
					var tl := Label.new()
					tl.text = "%dm" % int(q.get("secondsRemaining", 0) / 60)
					tl.add_theme_font_size_override("font_size", 12)
					tl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
					row.add_child(tl)
				row.add_child(UiKit.small_btn("✖", _abandon_quest.bind(kingdom, qid), true))
				box.add_child(row)
	# Training Hall (só COMBAT)
	if kingdom == "COMBAT":
		_build_training(box)
	# DAILY QUESTS
	if not quests.is_empty():
		box.add_child(UiKit.section("🗓 Daily Quests"))
		for q in quests:
			if q is Dictionary:
				box.add_child(_quest_card(kingdom, q))
	# Zonas de coleta / caça
	if ZONES.has(kingdom):
		box.add_child(UiKit.section("⚗ Áreas de Elemento"))
		box.add_child(_element_picker())
		box.add_child(UiKit.section("⚔ Zonas" if kingdom == "COMBAT" else "🌍 Zonas"))
		for z in ZONES[kingdom]:
			box.add_child(_zone_card(kingdom, z))

func _build_training(box: VBoxContainer) -> void:
	box.add_child(UiKit.section("🏋 Training Hall"))
	if training.get("active", false):
		box.add_child(UiKit.dim("+%d XP — coletar" % int(training.get("xpReward", 0))))
		if bool(training.get("readyToCollect", false)):
			box.add_child(UiKit.action("⭐ Coletar XP", _collect_training.bind(int(training.get("id", 0)))))
		box.add_child(UiKit.action_danger("✖ Cancelar", _cancel_training.bind(int(training.get("id", 0)))))
	else:
		box.add_child(UiKit.dim("Pague bronze por XP puro."))
		if _has_active_task():
			var b := UiKit.action("Colete a tarefa ativa", Callable())
			b.disabled = true
			box.add_child(b)
		else:
			box.add_child(UiKit.action("🏋 Treinar (2h)", _start_training.bind(2)))

func _quest_card(kingdom: String, q: Dictionary) -> PanelContainer:
	var done := bool(q.get("doneToday", false))
	var res := UiKit.card(UiKit.OK if done else UiKit.BRONZE)
	var vb: VBoxContainer = res[1]
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var nm := Label.new(); nm.text = str(q.get("displayName", "?")); nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(nm)
	var info := Label.new()
	info.text = "🥉%d  ⭐%d  ⚡%d" % [int(q.get("bronzeReward", 0)), int(q.get("expReward", 0)), int(q.get("staminaCost", 0))]
	info.add_theme_color_override("font_color", UiKit.TEXT_DIM); info.add_theme_font_size_override("font_size", 12)
	top.add_child(info)
	vb.add_child(top)
	var flavor := str(q.get("flavor", ""))
	if flavor != "":
		vb.add_child(UiKit.dim(flavor))
	if done:
		vb.add_child(UiKit.dim("✔ Feito hoje"))
	elif _has_active_task():
		var b := UiKit.action("Termine a tarefa ativa", Callable())
		b.disabled = true
		vb.add_child(b)
	elif not bool(q.get("canStart", false)):
		var b := UiKit.action("Sem estamina", Callable())
		b.disabled = true
		vb.add_child(b)
	else:
		var stam := int(q.get("staminaCost", 0))
		var label := "📜 Começar" if bool(q.get("interactive", false)) else "Iniciar Quest"
		if stam > 0:
			label += " · ⚡%d" % stam
		vb.add_child(UiKit.action(label, _start_quest.bind(kingdom, str(q.get("id", "")))))
	return res[0]

func _element_picker() -> HBoxContainer:
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 6)
	for e in ELEMENTS:
		var b := Button.new(); b.text = str(e[1])
		StoneStyle.apply(b)
		b.add_theme_font_size_override("font_size", 13)
		b.custom_minimum_size = Vector2(96, 36)
		b.toggle_mode = true; b.button_pressed = (str(e[0]) == selected_element)
		b.pressed.connect(_select_element.bind(str(e[0])))
		row.add_child(b)
	return row

func _zone_card(kingdom: String, z: Array) -> PanelContainer:
	var zname := str(z[0]); var tier := str(z[1]); var skill := str(z[2]); var min_lv := int(z[3])
	var locked := warrior_level < min_lv
	var col: Color = TIER_COL.get(tier, Color(0.6, 0.6, 0.6))
	var res := UiKit.card(Color(0.3, 0.3, 0.3, 0.5) if locked else col)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	if locked:
		panel.modulate = Color(1, 1, 1, 0.6)
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var nm := Label.new(); nm.text = zname; nm.add_theme_color_override("font_color", col); nm.add_theme_font_size_override("font_size", 15)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(nm)
	var tag := Label.new()
	tag.text = "🔒 Lv.%d+" % min_lv if locked else ("⚔ PvP" if tier != "SAFE" else "✔ Seguro")
	tag.add_theme_color_override("font_color", UiKit.TEXT_DIM); tag.add_theme_font_size_override("font_size", 11)
	top.add_child(tag)
	vb.add_child(top)
	var stam := maxi(5, ZONE_DURATION / 2)
	if locked:
		# P0: gate visível na própria ação (botão desabilitado diz POR QUE)
		var b := UiKit.action("Requer Nv %d" % min_lv, Callable())
		b.disabled = true
		vb.add_child(b)
	elif _has_active_task():
		var b := UiKit.action("Colete a tarefa ativa", Callable())
		b.disabled = true
		vb.add_child(b)
	else:
		var verb: String
		if kingdom == "COMBAT":
			verb = "⚔ Caçar · ⚡%d" % stam
		elif skill == "MINING":
			verb = "⛏ Minerar · ⚡%d" % stam
		elif skill == "GARIMPO":
			verb = "🔎 Garimpar · ⚡%d" % stam
		else:
			verb = "🎣 Pescar · ⚡%d" % stam
		var role := "COMBAT" if kingdom == "COMBAT" else "GATHERING"
		vb.add_child(UiKit.action(verb, _enter_zone.bind(kingdom, tier, role, skill)))
	return panel

# ── Ações (1 chamada cada; em sucesso re-abre o reino p/ refrescar; em falha mostra o erro) ───────
func _toggle(kingdom: String) -> void:
	if busy: return
	if kingdom == open_kingdom:
		open_kingdom = ""
		_render()
		return
	busy = true
	await _open(kingdom)
	busy = false

func _select_element(el: String) -> void:
	selected_element = el
	_render()

func _start_quest(kingdom: String, quest_type: String) -> void:
	if busy: return
	busy = true
	var r = await Api.quest_start(kingdom, quest_type)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); await _open(kingdom); return
	var j: Dictionary = r["json"]
	var qid := int(j.get("id", 0))
	# interativa: mostra o diálogo (intro + opções) → coleta com optionId. Senão resolve direto.
	if bool(j.get("interactive", false)) and j.get("dialog") is Dictionary:
		_show_quest_dialog(kingdom, qid, j["dialog"])
	else:
		await _collect_quest(kingdom, qid)

func _collect_quest(kingdom: String, quest_id: int, option_id := "") -> void:
	if busy: return
	busy = true
	var r = await Api.quest_collect(kingdom, quest_id, option_id)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); await _open(kingdom); return
	var j: Dictionary = r["json"]
	if bool(j.get("lunaPending", false)):   # a Luna interrompeu → ajudar ou terminar
		_show_luna_dialog(kingdom, quest_id)
		return
	var text := _quest_result_text(j)
	await _open(kingdom)   # refresca a lista; status some aqui → resultado vai no modal
	_show_result(text)

# Diálogo de quest interativa: intro + um botão por opção (coleta com o optionId escolhido).
func _show_quest_dialog(kingdom: String, quest_id: int, dialog: Dictionary) -> void:
	var opts: Array = []
	for o in dialog.get("options", []):
		if o is Dictionary:
			opts.append([str(o.get("label", "?")), str(o.get("id", ""))])
	_choice_dialog(str(dialog.get("intro", "")), opts, func(opt_id) -> void:
		await _collect_quest(kingdom, quest_id, str(opt_id)))

# A Luna apareceu: ajudar (abre mão da recompensa) ou terminar a missão.
func _show_luna_dialog(kingdom: String, quest_id: int) -> void:
	_choice_dialog("🐶 Uma cãozinha (Luna) apareceu e interrompeu a missão! O que fazer?",
		[["Ajudar a Luna", "help"], ["Terminar a missão", "ignore"]],
		func(action) -> void:
			busy = true
			var r = await Api.quest_luna(kingdom, quest_id, str(action))
			busy = false
			var text := ""
			if r.get("ok") and r.get("json") is Dictionary:
				text = _quest_result_text(r["json"])
			else:
				_show_error(r)
			await _open(kingdom)
			if text != "":
				_show_result(text))

# Overlay genérico de escolha: título + botões. cb.call(valor) ao escolher. [MIGRACAO_GODOT]
func _choice_dialog(title_text: String, options: Array, cb: Callable) -> void:
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.add_child(center)
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	var sb: StyleBoxFlat = panel.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	vb.add_theme_constant_override("separation", 10)
	center.add_child(panel)
	var lbl := UiKit.body(title_text)
	lbl.custom_minimum_size = Vector2(460, 0)
	vb.add_child(lbl)
	for opt in options:
		var val = opt[1]
		var b := UiKit.action(str(opt[0]), func() -> void:
			overlay.queue_free()
			cb.call(val))
		b.custom_minimum_size = Vector2(460, 40)
		vb.add_child(b)

# Modal de RESULTADO: texto + botão OK. Persiste (o status some no _open). Substitui o showCollectModal do web.
func _show_result(text: String) -> void:
	if text.strip_edges() == "":
		return
	var overlay := ColorRect.new()
	overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.color = Color(0, 0, 0, 0.72)
	overlay.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(overlay)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	overlay.add_child(center)
	var res := UiKit.card(UiKit.GOLD_SOFT)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	var sb: StyleBoxFlat = panel.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	vb.add_theme_constant_override("separation", 12)
	center.add_child(panel)
	var lbl := UiKit.body(text)
	lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	lbl.custom_minimum_size = Vector2(460, 0)
	vb.add_child(lbl)
	var ok := UiKit.action("OK", func() -> void: overlay.queue_free())
	ok.custom_minimum_size = Vector2(460, 40)
	vb.add_child(ok)
	ok.call_deferred("grab_focus")

func _abandon_quest(kingdom: String, quest_id: int) -> void:
	if busy: return
	busy = true
	await Api.quest_abandon(kingdom, quest_id)
	busy = false
	await _open(kingdom)
	UiKit.flash(status, "Quest abandonada.", 0)

func _start_training(hours: int) -> void:
	if busy: return
	busy = true
	var r = await Api.training_start(hours)
	if r.get("ok") and r.get("json") is Dictionary:
		# [SEM_TIMER] instantâneo: resolve e mostra o resultado direto
		busy = false
		await _collect_training(int(r["json"].get("id", 0)))
		return
	else:
		_show_error(r)
	busy = false
	await _open("COMBAT")

func _collect_training(session_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.training_collect(session_id)
	var msg := ""
	if r.get("ok") and r.get("json") is Dictionary:
		msg = "🏋 Treino completo! +%d XP" % int(r["json"].get("xpEarned", 0))
	else:
		_show_error(r)
	busy = false
	await _open("COMBAT")
	if msg != "":
		_show_result(msg)

func _cancel_training(session_id: int) -> void:
	if busy: return
	busy = true
	await Api.training_cancel(session_id)
	busy = false
	await _open("COMBAT")
	UiKit.flash(status, "Treino cancelado.", 0)

# Coleta/caça de zona: enter → collect direto (instantâneo, como o web). Resultado em texto.
func _enter_zone(kingdom: String, tier: String, role: String, skill: String) -> void:
	if busy: return
	busy = true
	var skill_arg: Variant = skill if skill != "" else null
	var r = await Api.zone_enter(tier, role, skill_arg, ZONE_DURATION, kingdom, selected_element)
	if r.get("ok") and r.get("json") is Dictionary:
		var id := int(r["json"].get("id", 0))
		busy = false
		await _collect_zone(id)
		return
	else:
		_show_error(r)
	busy = false
	await _open(kingdom)

func _collect_zone(activity_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.zone_collect(activity_id)
	var msg := ""
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		if bool(j.get("bossPending", false)):
			# [ZONA_CHEFE] chefe errante: não dá pra escolher fugir/encarar aqui — informa.
			msg = "💀 %s (Lv %d) apareceu! Resolva o chefe na versão web." % [str(j.get("bossName", "Chefe")), int(j.get("bossLevel", 0))]
		else:
			msg = _zone_result_text(j)
	else:
		_show_error(r)
	busy = false
	if open_kingdom != "":
		await _open(open_kingdom)
	if msg != "":
		_show_result(msg)

func _cancel_zone(activity_id: int) -> void:
	if busy: return
	busy = true
	await Api.zone_cancel(activity_id)
	busy = false
	if open_kingdom != "":
		await _open(open_kingdom)
	UiKit.flash(status, "Expedição cancelada.", 0)

# ── Texto de resultado (substitui o showCollectModal do web) ──────────────────────────────────────
func _quest_result_text(r: Dictionary) -> String:
	if bool(r.get("acquiredPet", false)) or str(r.get("acquiredPet", "")) != "":
		var pet := str(r.get("acquiredPet", ""))
		if pet != "" and pet != "false":
			return "🎉 Novo companheiro: %s!" % pet
	var lost := bool(r.get("monsterEncountered", false)) and not bool(r.get("monsterDefeated", false))
	if lost:
		return "💀 Derrotado por %s — sem recompensa." % str(r.get("monsterName", "monstro"))
	var parts: Array = []
	if bool(r.get("monsterEncountered", false)):
		parts.append("⚔ %s derrotado!" % str(r.get("monsterName", "inimigo")))
	else:
		parts.append("✅ Quest concluída!")
	parts.append("+%d XP · +%d bronze" % [int(r.get("xpEarned", 0)), int(r.get("bronzeEarned", 0))])
	if r.get("droppedItem") is Dictionary:
		parts.append("🎁 " + str(r["droppedItem"].get("name", "item")))
	return "   ".join(parts)

func _zone_result_text(r: Dictionary) -> String:
	if bool(r.get("wasAttacked", false)) and not bool(r.get("survived", false)):
		var s := "💀 Derrotado na expedição!"
		if str(r.get("attackerName", "")) != "":
			s += " (por %s)" % str(r.get("attackerName"))
		if str(r.get("lostItemName", "")) != "":
			s += " · item roubado: %s" % str(r.get("lostItemName"))
		return s
	var parts: Array = []
	var slew_boss := bool(r.get("wasAttacked", false)) and bool(r.get("survived", false)) and str(r.get("lootItemName", "")) != ""
	if slew_boss:
		parts.append("🏆 Chefe errante abatido!")
	elif bool(r.get("wasAttacked", false)):
		parts.append("⚔ Sobreviveu à expedição!")
	else:
		parts.append("✅ Expedição concluída!")
	if str(r.get("lootItemName", "")) != "":
		parts.append("🎁 " + str(r.get("lootItemName")))
	if r.get("drops") is Array:
		for d in r["drops"]:
			if d is Dictionary:
				parts.append("📦 %s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))])
	if int(r.get("bronzeGained", 0)) > 0:
		parts.append("🥉 +%d bronze" % int(r.get("bronzeGained", 0)))
	if int(r.get("xpGained", 0)) > 0:
		parts.append("⭐ +%d XP" % int(r.get("xpGained", 0)))
	return "   ".join(parts)

func _show_error(r) -> void:
	UiKit.show_error(status, r)
	_show_result("⚠ " + UiKit.err_text(r))   # erro vai no modal também (o status fica no topo, fora de vista)
