extends Control
# ── DIÁRIO DE MISSÕES — 3 abas [DIARIO_QUEST] ─────────────────────────────────────────
# Aberto pelo ícone quest_log do topbar. Agrega quests de reino (dailies) + deveres do recruta (únicos)
# em 3 abas: "Pra pegar" (disponíveis) / "Em progresso" (aceitas-não-resolvidas = to-do) / "Completadas"
# (só ÚNICAS). Lê GET /api/quests/journal.
#  • Deveres do recruta: agem INLINE (aceitar/entregar) — fluxo simples (sem combate/diálogo).
#  • Quests de reino: aceitar inline em "Pra pegar"; resolver = "Resolver no Mundo" (o fluxo de diálogo/
#    combate/recompensa mora no World — não é duplicado aqui).
# Avisa o Shell (UiKit.starter_changed_sink) p/ os badges. Desenho: docs/PLANO_DIARIO_QUEST.md.

signal go_back

const Icons := preload("res://ui/Icons.gd")

var content: VBoxContainer
var status: Label
var wallet: Label
var journal: Dictionary = {}
var warrior: Dictionary = {}
var busy := false
var tab := "toPickUp"   # aba ativa

const TABS := [["toPickUp", "Pra pegar"], ["inProgress", "Em progresso"], ["completed", "Completadas"]]

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
	var list := _group(tab)
	if list.is_empty():
		content.add_child(UiKit.dim(_empty_text()))
	for q in list:
		if q is Dictionary:
			content.add_child(_card(q))

func _empty_text() -> String:
	match tab:
		"toPickUp": return Lang.t("Nenhuma missão disponível agora. Volte após o próximo ciclo.")
		"inProgress": return Lang.t("Nada em progresso. Aceite uma missão na aba 'Pra pegar'.")
		"completed": return Lang.t("Nenhum feito registrado ainda.")
	return ""

func _set_tab(key: String) -> void:
	tab = key
	_render()

func _tab_bar() -> Control:
	var hb := HBoxContainer.new()
	hb.add_theme_constant_override("separation", 6)
	for t in TABS:
		var key: String = t[0]
		var b := UiKit.action("%s (%d)" % [Lang.t(t[1]), _group(key).size()], _set_tab.bind(key))
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		if key == tab:
			b.add_theme_color_override("font_color", UiKit.GOLD)
		else:
			b.modulate = Color(1, 1, 1, 0.55)
		hb.add_child(b)
	return hb

func _card(q: Dictionary) -> PanelContainer:
	return _starter_card(q) if str(q.get("source", "")) == "starter" else _kingdom_card(q)

# ── Card de quest de reino ────────────────────────────────────────────────────────────
func _kingdom_card(q: Dictionary) -> PanelContainer:
	var border := UiKit.GOLD if tab == "inProgress" else UiKit.BRONZE
	var res := UiKit.card(border)
	var box: VBoxContainer = res[1]
	var nl := Label.new()
	nl.text = str(q.get("title", "?"))
	nl.add_theme_font_size_override("font_size", 16)
	nl.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(nl)
	box.add_child(UiKit.dim(str(q.get("flavor", ""))))
	# recompensa
	var rew := HBoxContainer.new(); rew.add_theme_constant_override("separation", 10)
	var xp := int(q.get("expReward", 0))
	if xp > 0:
		var xl := Label.new(); xl.text = "+%d XP" % xp
		xl.add_theme_font_size_override("font_size", 13); xl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		xl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		rew.add_child(xl)
	rew.add_child(UiKit.coin_box(int(q.get("bronzeReward", 0)), 14))
	box.add_child(rew)
	# ação por aba
	if tab == "toPickUp":
		var k := str(q.get("kingdom", ""))
		var qt := str(q.get("questType", ""))
		box.add_child(_btn_right(UiKit.action(Lang.t("Aceitar"), _accept_kingdom.bind(k, qt))))
	elif tab == "inProgress":
		box.add_child(_btn_right(UiKit.action(Lang.t("Resolver no Mundo"), _go_world)))
	return res[0]

# ── Card de dever do recruta (único) — age inline ─────────────────────────────────────
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
	var rew := HBoxContainer.new(); rew.add_theme_constant_override("separation", 10)
	var xp := int(q.get("rewardXp", 0))
	if xp > 0:
		var xl := Label.new(); xl.text = "+%d XP" % xp
		xl.add_theme_font_size_override("font_size", 13); xl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		xl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		rew.add_child(xl)
	rew.add_child(UiKit.coin_box(int(q.get("rewardBronze", 0)), 14))
	box.add_child(rew)
	if st == "available":
		box.add_child(_btn_right(UiKit.action(Lang.t("Aceitar"), _accept_starter.bind(which))))
	elif st == "accepted":
		if comp == "QUEST":
			box.add_child(UiKit.dim(Lang.t("Complete uma missão no Mundo para cumprir este dever.")))
			box.add_child(_btn_right(UiKit.action(Lang.t("Ir ao Mundo"), _go_world)))
		else:
			box.add_child(_btn_right(UiKit.action(Lang.t("Curar") if comp == "HEAL" else Lang.t("Concluir"), _turn_in_starter.bind(which))))
	else:
		var d := Label.new(); d.text = Lang.t("Já cumprido")
		d.add_theme_font_size_override("font_size", 13); d.add_theme_color_override("font_color", UiKit.OK)
		box.add_child(d)
	return res[0]

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
