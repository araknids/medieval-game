extends Control
# ── DIÁRIO DE MISSÕES — 2 abas [DIARIO_QUEST] ─────────────────────────────────────────
# "Diárias": quests de reino (repetitivas, sem histórico). UMA ativa por vez — aceitar trava as outras
#   até resolver; as já feitas na janela aparecem APAGADAS ("volta no ciclo"); resolver = no Mundo.
# "Missões": únicas/história (hoje só deveres do recruta), em seções Disponíveis / Em andamento /
#   Concluídas. Deveres agem inline (aceitar/entregar); quest de reino normal (futuro) resolve no Mundo.
# GET /api/quests/journal. Avisa o Shell (starter_changed_sink). Desenho: docs/PLANO_DIARIO_QUEST.md.

signal go_back

const Icons := preload("res://ui/Icons.gd")

var content: VBoxContainer
var status: Label
var wallet: Label
var journal: Dictionary = {}
var warrior: Dictionary = {}
var busy := false
var tab := "daily"   # "daily" | "missions"

const TABS := [["daily", "Diárias"], ["missions", "Missões"]]

func _ready() -> void:
	var ui := UiKit.scaffold(self, "Diário de Missões", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	var rs = await Api.batch_get(["/api/quests/journal", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	journal = r["json"]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _group(key: String) -> Array:
	return journal.get(key, []) if journal.get(key) is Array else []

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	content.add_child(_tab_bar())
	if tab == "daily":
		_render_daily()
	else:
		_render_missions()

func _set_tab(key: String) -> void:
	tab = key
	_render()

func _tab_bar() -> Control:
	var hb := HBoxContainer.new()
	hb.add_theme_constant_override("separation", 6)
	var counts := {
		"daily": _group("daily").size(),
		"missions": _group("missionsAvailable").size() + _group("missionsInProgress").size() + _group("missionsCompleted").size(),
	}
	for t in TABS:
		var key: String = t[0]
		var b := UiKit.action("%s (%d)" % [Lang.t(t[1]), int(counts.get(key, 0))], _set_tab.bind(key))
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		if key == tab:
			b.add_theme_color_override("font_color", UiKit.GOLD)
		else:
			b.modulate = Color(1, 1, 1, 0.55)
		hb.add_child(b)
	return hb

# ── Aba DIÁRIAS ───────────────────────────────────────────────────────────────────────
func _render_daily() -> void:
	content.add_child(UiKit.dim(Lang.t("Tarefas que renovam a cada ciclo. Só uma ativa por vez — resolva antes de pegar outra.")))
	var list := _group("daily")
	if list.is_empty():
		content.add_child(UiKit.dim(Lang.t("Nenhuma diária disponível agora.")))
		return
	for q in list:
		if q is Dictionary:
			content.add_child(_daily_card(q))

func _daily_card(q: Dictionary) -> PanelContainer:
	var st := str(q.get("dailyState", "available"))
	var done := st == "done"
	var border := UiKit.GOLD if st == "active" else (Color(1, 1, 1, 0.10) if done else UiKit.BRONZE)
	var res := UiKit.card(border)
	var box: VBoxContainer = res[1]
	if done:
		res[0].modulate = Color(1, 1, 1, 0.45)   # feita nesta janela → card apagado
	var nl := Label.new(); nl.text = str(q.get("title", "?"))
	nl.add_theme_font_size_override("font_size", 16); nl.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(nl)
	box.add_child(UiKit.dim(str(q.get("flavor", ""))))
	box.add_child(_reward_row(int(q.get("expReward", 0)), int(q.get("bronzeReward", 0))))
	if st == "active":
		box.add_child(_btn_right(UiKit.action(Lang.t("Resolver no Mundo"), _go_world)))
	elif done:
		box.add_child(_hint(Lang.t("Feita — volta no próximo ciclo")))
	else:   # available
		if bool(journal.get("dailyLocked", false)):
			box.add_child(_hint(Lang.t("Resolva a diária ativa primeiro")))
		else:
			box.add_child(_btn_right(UiKit.action(Lang.t("Aceitar"), _accept_kingdom.bind(str(q.get("kingdom", "")), str(q.get("questType", ""))))))
	return res[0]

# ── Aba MISSÕES (seções) ──────────────────────────────────────────────────────────────
func _render_missions() -> void:
	var a := _mission_section("Disponíveis", "missionsAvailable", "available")
	var b := _mission_section("Em andamento", "missionsInProgress", "inProgress")
	var c := _mission_section("Concluídas", "missionsCompleted", "completed")
	if not (a or b or c):
		content.add_child(UiKit.dim(Lang.t("Nenhuma missão ainda. As histórias do reino chegam aqui.")))

func _mission_section(title: String, key: String, section: String) -> bool:
	var list := _group(key)
	if list.is_empty():
		return false
	content.add_child(UiKit.section(title))
	for q in list:
		if q is Dictionary:
			content.add_child(_starter_card(q) if str(q.get("source", "")) == "starter" else _kingdom_mission_card(q, section))
	return true

func _kingdom_mission_card(q: Dictionary, section: String) -> PanelContainer:
	var res := UiKit.card(UiKit.GOLD if section == "inProgress" else UiKit.BRONZE)
	var box: VBoxContainer = res[1]
	var nl := Label.new(); nl.text = str(q.get("title", "?"))
	nl.add_theme_font_size_override("font_size", 16); nl.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(nl)
	box.add_child(UiKit.dim(str(q.get("flavor", ""))))
	box.add_child(_reward_row(int(q.get("expReward", 0)), int(q.get("bronzeReward", 0))))
	if section == "available":
		box.add_child(_btn_right(UiKit.action(Lang.t("Aceitar"), _accept_kingdom.bind(str(q.get("kingdom", "")), str(q.get("questType", ""))))))
	elif section == "inProgress":
		box.add_child(_btn_right(UiKit.action(Lang.t("Resolver no Mundo"), _go_world)))
	return res[0]

# ── Card de dever do recruta (único) — ação pelo state (alinha com a seção) ────────────
func _starter_card(q: Dictionary) -> PanelContainer:
	var st := str(q.get("state", "available"))
	var comp := str(q.get("comp", ""))
	var which := str(q.get("id", ""))
	var border := UiKit.OK if st == "done" else (UiKit.GOLD if st == "accepted" else UiKit.BRONZE)
	var res := UiKit.card(border)
	var box: VBoxContainer = res[1]
	var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 10)
	var portrait_key: String = {"equip": "veteran", "quest": "veteran", "heal": "priest"}.get(which, "")
	if portrait_key != "" and Icons.tex(portrait_key) != null:
		var pr := Icons.rect(portrait_key, 48); pr.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		head.add_child(pr)
	var nl := Label.new()
	nl.text = str(q.get("npc", "")).strip_edges()
	if nl.text == "": nl.text = Lang.t("Arme-se, recruta")
	nl.add_theme_font_size_override("font_size", 16)
	nl.add_theme_color_override("font_color", UiKit.OK if st == "done" else UiKit.GOLD)
	nl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	head.add_child(nl)
	box.add_child(head)
	box.add_child(UiKit.dim(str(q.get("flavor", ""))))
	box.add_child(_reward_row(int(q.get("rewardXp", 0)), int(q.get("rewardBronze", 0))))
	if st == "available":
		box.add_child(_btn_right(UiKit.action(Lang.t("Aceitar"), _accept_starter.bind(which))))
	elif st == "accepted":
		if comp == "QUEST":
			box.add_child(_hint(Lang.t("Complete uma missão no Mundo para cumprir este dever.")))
			box.add_child(_btn_right(UiKit.action(Lang.t("Ir ao Mundo"), _go_world)))
		else:
			box.add_child(_btn_right(UiKit.action(Lang.t("Curar") if comp == "HEAL" else Lang.t("Concluir"), _turn_in_starter.bind(which))))
	else:
		box.add_child(_hint(Lang.t("Já cumprido")))
	return res[0]

# ── Helpers ───────────────────────────────────────────────────────────────────────────
func _reward_row(xp: int, bronze: int) -> Control:
	var rew := HBoxContainer.new(); rew.add_theme_constant_override("separation", 10)
	if xp > 0:
		var xl := Label.new(); xl.text = "+%d XP" % xp
		xl.add_theme_font_size_override("font_size", 13); xl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		xl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		rew.add_child(xl)
	rew.add_child(UiKit.coin_box(bronze, 14))
	return rew

func _hint(text: String) -> Label:
	var d := Label.new(); d.text = text
	d.add_theme_font_size_override("font_size", 13); d.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	return d

func _btn_right(b: Button) -> Button:
	b.size_flags_horizontal = Control.SIZE_SHRINK_END
	return b

func _go_world() -> void:
	if Shell.current != null:
		Shell.current._open("World")

# ── Ações ─────────────────────────────────────────────────────────────────────────────
func _accept_kingdom(kingdom: String, quest_type: String) -> void:
	if busy: return
	busy = true
	var r = await Api.quest_start(kingdom, quest_type)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		UiKit.flash(status, Lang.t("Missão aceita!"), 1)
		if UiKit.starter_changed_sink.is_valid(): UiKit.starter_changed_sink.call()
		await _refresh()
	else:
		UiKit.show_error(status, r)

func _accept_starter(which: String) -> void:
	if busy: return
	busy = true
	var r = await Api.starter_quest_accept(which)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		UiKit.flash(status, Lang.t("Missão aceita!"), 1)
		if UiKit.starter_changed_sink.is_valid(): UiKit.starter_changed_sink.call()
		await _refresh()
	else:
		UiKit.show_error(status, r)

func _turn_in_starter(which: String) -> void:
	if busy: return
	busy = true
	var r = await Api.starter_quest_turn_in(which)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		UiKit.flash(status, Lang.t("Recompensa entregue!"), 1)
		if UiKit.starter_changed_sink.is_valid(): UiKit.starter_changed_sink.call()
		await _refresh()
	else:
		UiKit.show_error(status, r)
