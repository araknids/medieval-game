extends Control
# ── Tela MUNDO / REINOS ───────────────────────────────────────────────────────────
# Lista os reinos (GET /api/world), abre um e mostra suas DAILY QUESTS + zonas de coleta/caça.
# Iniciar quest (start→collect direto, como o web) e coletar a recompensa; resultado/log em TEXTO
# (sem 3D). Coleta/caça de zona via /api/zones (enter→collect instantâneo). Espelha loadWorld /
# renderWorldOverview / renderKingdomDetail do app.js. [MIGRACAO_GODOT]

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
var busy := false
var kingdoms: Array = []          # GET /api/world
var open_kingdom := ""            # reino expandido (só um por vez)
var warrior_level := 1
var selected_element := "FIRE"    # picker de área de elemento
# detalhe do reino aberto (carregado sob demanda)
var quests: Array = []
var active_quests: Array = []
var training: Dictionary = {}
var zone_session: Dictionary = {}

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
	var ttl := Label.new(); ttl.text = "Mundo"; ttl.add_theme_font_size_override("font_size", 26)
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
	# nível do guerreiro (gate das zonas) — best-effort
	var wr = await Api.get_warrior()
	if wr.get("ok") and wr.get("json") is Dictionary:
		warrior_level = int(wr["json"].get("level", 1))
	var r = await Api.world_kingdoms()
	if not (r.get("ok") and r.get("json") is Array):
		status.text = "Erro ao carregar (%s)" % str(r.get("status", "?"))
		return
	kingdoms = r["json"]
	status.text = ""
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
	status.text = "Abrindo %s…" % kingdom
	var rq = await Api.quest_list(kingdom)
	quests = rq["json"] if (rq.get("ok") and rq.get("json") is Array) else []
	var ra = await Api.quest_active(kingdom)
	active_quests = ra["json"] if (ra.get("ok") and ra.get("json") is Array) else []
	var rt: Dictionary = {}
	if kingdom == "COMBAT":
		var rtr = await Api.training_current()
		if rtr.get("ok") and rtr.get("json") is Dictionary:
			rt = rtr["json"]
	training = rt
	var rz = await Api.zone_current()
	zone_session = rz["json"] if (rz.get("ok") and rz.get("json") is Dictionary) else {}
	status.text = ""
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
	for k in kingdoms:
		if k is Dictionary:
			content.add_child(_kingdom_card(k))

func _kingdom_card(k: Dictionary) -> PanelContainer:
	var kid := str(k.get("kingdom", ""))
	var is_open := kid == open_kingdom
	var is_mine := bool(k.get("isMine", false))
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.10, 0.10, 0.18)
	sb.set_border_width_all(1)
	sb.border_color = Color(0.30, 0.78, 0.30, 0.7) if is_mine else Color(0.27, 0.27, 0.27)
	sb.set_corner_radius_all(8); sb.set_content_margin_all(12)
	panel.add_theme_stylebox_override("panel", sb)
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 4)
	panel.add_child(box)
	# cabeçalho do card: clicar alterna o reino
	var head := Button.new()
	head.flat = true
	head.alignment = HORIZONTAL_ALIGNMENT_LEFT
	head.text = "%s %s" % [str(k.get("icon", "")), str(k.get("displayName", kid))]
	head.add_theme_font_size_override("font_size", 17)
	head.pressed.connect(_toggle.bind(kid))
	box.add_child(head)
	var ctrl := Label.new()
	var cg := str(k.get("controllingGuild", ""))
	ctrl.text = ("🛡 " + cg) if cg != "" else "Neutro"
	ctrl.modulate = Color(0.30, 0.80, 0.30) if cg != "" else Color(0.67, 0.67, 0.67)
	ctrl.add_theme_font_size_override("font_size", 12)
	box.add_child(ctrl)
	if is_mine:
		var bonus := Label.new()
		bonus.text = "Sua guilda: +%d%% XP · +%d%% bronze · +%d%% bônus" % [int(k.get("xpBonus", 0)), int(k.get("bronzeBonus", 0)), int(k.get("exclusiveBonus", 0))]
		bonus.modulate = Color(0.30, 0.80, 0.30); bonus.add_theme_font_size_override("font_size", 11)
		box.add_child(bonus)
	var lore := Label.new(); lore.text = str(k.get("lore", ""))
	lore.modulate = Color(1, 1, 1, 0.5); lore.add_theme_font_size_override("font_size", 12)
	lore.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	box.add_child(lore)
	if is_open:
		box.add_child(_spacer(6))
		_build_detail(box, kid)
	return panel

# Detalhe do reino aberto: pvp banner + tarefas ativas + quests + zonas de coleta/caça.
func _build_detail(box: VBoxContainer, kingdom: String) -> void:
	var sep := HSeparator.new()
	box.add_child(sep)
	# tarefa de zona ativa pendurada
	if zone_session.get("active", false):
		var zname := str(zone_session.get("zoneName", zone_session.get("zone", "")))
		var ready := bool(zone_session.get("readyToCollect", false))
		box.add_child(_dim("⚔ Expedição em andamento (%s)" % zname))
		if ready:
			box.add_child(_act("Coletar loot", _collect_zone.bind(int(zone_session.get("id", 0)))))
		else:
			box.add_child(_act("✕ Cancelar expedição", _cancel_zone.bind(int(zone_session.get("id", 0)))))
	# quests ativas (coletar / abandonar)
	if not active_quests.is_empty():
		box.add_child(_section("Quests Ativas"))
		for q in active_quests:
			if q is Dictionary:
				var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
				var lbl := Label.new(); lbl.text = str(q.get("displayName", "?"))
				lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
				row.add_child(lbl)
				var qid := int(q.get("id", 0))
				if bool(q.get("readyToCollect", false)):
					if bool(q.get("interactive", false)) and q.get("dialog") is Dictionary:
						var dlg: Dictionary = q["dialog"]   # interativa: re-abre a escolha
						row.add_child(_act("Escolher", func() -> void: _show_quest_dialog(kingdom, qid, dlg)))
					else:
						row.add_child(_act("Coletar", _collect_quest.bind(kingdom, qid)))
				else:
					var rem := Label.new(); rem.text = "%dm" % int(q.get("secondsRemaining", 0) / 60); rem.modulate = Color(1, 1, 1, 0.5)
					row.add_child(rem)
				row.add_child(_act("✕", _abandon_quest.bind(kingdom, qid)))
				box.add_child(row)
	# Training Hall (só COMBAT)
	if kingdom == "COMBAT":
		_build_training(box)
	# DAILY QUESTS
	if not quests.is_empty():
		box.add_child(_section("🗓 Daily Quests"))
		for q in quests:
			if q is Dictionary:
				box.add_child(_quest_card(kingdom, q))
	# Zonas de coleta / caça
	if ZONES.has(kingdom):
		box.add_child(_section("⚗ Áreas de Elemento"))
		box.add_child(_element_picker())
		box.add_child(_section("⚔ Zonas" if kingdom == "COMBAT" else "🌍 Zonas"))
		for z in ZONES[kingdom]:
			box.add_child(_zone_card(kingdom, z))

func _build_training(box: VBoxContainer) -> void:
	box.add_child(_section("🏋 Training Hall"))
	if training.get("active", false):
		box.add_child(_dim("+%d XP — coletar" % int(training.get("xpReward", 0))))
		if bool(training.get("readyToCollect", false)):
			box.add_child(_act("⭐ Coletar XP", _collect_training.bind(int(training.get("id", 0)))))
		box.add_child(_act("✕ Cancelar", _cancel_training.bind(int(training.get("id", 0)))))
	else:
		box.add_child(_dim("Pague bronze por XP puro."))
		if _has_active_task():
			box.add_child(_dim("⏳ Colete sua tarefa ativa primeiro"))
		else:
			box.add_child(_act("🏋 Treinar (2h)", _start_training.bind(2)))

func _quest_card(kingdom: String, q: Dictionary) -> PanelContainer:
	var done := bool(q.get("doneToday", false))
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.10, 0.10, 0.18)
	sb.set_border_width_all(1); sb.border_color = Color(0.18, 0.30, 0.18) if done else Color(0.20, 0.20, 0.20)
	sb.set_corner_radius_all(6); sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	var vb := VBoxContainer.new(); vb.add_theme_constant_override("separation", 3)
	panel.add_child(vb)
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var nm := Label.new(); nm.text = str(q.get("displayName", "?")); nm.add_theme_font_size_override("font_size", 14)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(nm)
	var info := Label.new()
	info.text = "🪙%d  ⭐%d  ⚡%d" % [int(q.get("bronzeReward", 0)), int(q.get("expReward", 0)), int(q.get("staminaCost", 0))]
	info.modulate = Color(1, 1, 1, 0.55); info.add_theme_font_size_override("font_size", 12)
	top.add_child(info)
	vb.add_child(top)
	var flavor := str(q.get("flavor", ""))
	if flavor != "":
		var fl := Label.new(); fl.text = flavor; fl.modulate = Color(0.54, 0.58, 0.63)
		fl.add_theme_font_size_override("font_size", 12); fl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		vb.add_child(fl)
	if done:
		vb.add_child(_dim("✓ Feito hoje"))
	elif _has_active_task():
		vb.add_child(_dim("Termine sua tarefa ativa"))
	elif not bool(q.get("canStart", false)):
		vb.add_child(_dim("Estamina insuficiente"))
	else:
		var label := "📜 Começar" if bool(q.get("interactive", false)) else "Iniciar Quest"
		vb.add_child(_act(label, _start_quest.bind(kingdom, str(q.get("id", "")))))
	return panel

func _element_picker() -> HBoxContainer:
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 6)
	for e in ELEMENTS:
		var b := Button.new(); b.text = str(e[1])
		b.toggle_mode = true; b.button_pressed = (str(e[0]) == selected_element)
		b.pressed.connect(_select_element.bind(str(e[0])))
		row.add_child(b)
	return row

func _zone_card(kingdom: String, z: Array) -> PanelContainer:
	var zname := str(z[0]); var tier := str(z[1]); var skill := str(z[2]); var min_lv := int(z[3])
	var locked := warrior_level < min_lv
	var col: Color = TIER_COL.get(tier, Color(0.6, 0.6, 0.6))
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.10, 0.10, 0.18)
	sb.set_border_width_all(1); sb.border_color = Color(0.20, 0.20, 0.20) if locked else Color(col, 0.4)
	sb.set_corner_radius_all(6); sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	if locked:
		panel.modulate = Color(1, 1, 1, 0.5)
	var vb := VBoxContainer.new(); vb.add_theme_constant_override("separation", 3)
	panel.add_child(vb)
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 8)
	var nm := Label.new(); nm.text = zname; nm.modulate = col; nm.add_theme_font_size_override("font_size", 14)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top.add_child(nm)
	var tag := Label.new()
	tag.text = "🔒 Lv.%d+" % min_lv if locked else ("⚔ PvP" if tier != "SAFE" else "✓ Seguro")
	tag.modulate = Color(1, 1, 1, 0.5); tag.add_theme_font_size_override("font_size", 11)
	top.add_child(tag)
	vb.add_child(top)
	if locked:
		vb.add_child(_dim("Alcance o nível %d para destravar." % min_lv))
	elif _has_active_task():
		vb.add_child(_dim("⏳ Colete sua tarefa ativa primeiro"))
	else:
		var stam := maxi(5, ZONE_DURATION / 2)
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
		vb.add_child(_act(verb, _enter_zone.bind(kingdom, tier, role, skill)))
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
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.12, 0.10, 0.10)
	sb.set_border_width_all(2); sb.border_color = Color(0.5, 0.4, 0.22)
	sb.set_corner_radius_all(4); sb.set_content_margin_all(16)
	panel.add_theme_stylebox_override("panel", sb)
	center.add_child(panel)
	var vb := VBoxContainer.new()
	vb.custom_minimum_size = Vector2(460, 0)
	vb.add_theme_constant_override("separation", 10)
	panel.add_child(vb)
	var lbl := Label.new()
	lbl.text = title_text
	lbl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	lbl.custom_minimum_size = Vector2(460, 0)
	vb.add_child(lbl)
	for opt in options:
		var b := Button.new()
		b.text = str(opt[0])
		b.custom_minimum_size = Vector2(0, 40)
		var val = opt[1]
		b.pressed.connect(func() -> void:
			overlay.queue_free()
			cb.call(val))
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
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.12, 0.10, 0.10)
	sb.set_border_width_all(2); sb.border_color = Color(0.5, 0.4, 0.22)
	sb.set_corner_radius_all(4); sb.set_content_margin_all(16)
	panel.add_theme_stylebox_override("panel", sb)
	center.add_child(panel)
	var vb := VBoxContainer.new()
	vb.custom_minimum_size = Vector2(460, 0)
	vb.add_theme_constant_override("separation", 12)
	panel.add_child(vb)
	var lbl := Label.new()
	lbl.text = text
	lbl.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	lbl.custom_minimum_size = Vector2(460, 0)
	vb.add_child(lbl)
	var ok := Button.new()
	ok.text = "OK"
	ok.custom_minimum_size = Vector2(0, 40)
	ok.pressed.connect(func() -> void: overlay.queue_free())
	vb.add_child(ok)
	ok.call_deferred("grab_focus")

func _abandon_quest(kingdom: String, quest_id: int) -> void:
	if busy: return
	busy = true
	await Api.quest_abandon(kingdom, quest_id)
	status.text = "Quest abandonada."
	busy = false
	await _open(kingdom)

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
	status.text = "Treino cancelado."
	busy = false
	await _open("COMBAT")

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
	status.text = "Expedição cancelada."
	busy = false
	if open_kingdom != "":
		await _open(open_kingdom)

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
		parts.append("🪙 +%d bronze" % int(r.get("bronzeGained", 0)))
	if int(r.get("xpGained", 0)) > 0:
		parts.append("⭐ +%d XP" % int(r.get("xpGained", 0)))
	return "   ".join(parts)

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

# ── helpers de UI ────────────────────────────────────────────────────────────────
func _act(text: String, cb: Callable) -> Button:
	var b := Button.new(); b.text = text; b.custom_minimum_size = Vector2(140, 0)
	b.pressed.connect(cb)
	return b

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 18); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.45)
	l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
