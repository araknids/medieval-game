extends Control
# ── Tela DIÁRIO DE MISSÕES — Deveres do Recruta ([ONBOARDING v2]) ─────────────────────
# Aberta pelo ícone de quest no topbar. Lista as quests por estado: available (Aceitar),
# accepted (Entregar) e done. Lê GET /api/starter-quests + /api/warrior; aceita/entrega via
# POST /api/starter-quests/{which}/{accept|turn-in}. Avisa o Shell (UiKit.starter_changed_sink)
# p/ atualizar os badges (nav dos NPCs + topbar). Sem emoji de web [SEM_WEB_EMOJI].
# Desenho: docs/PLANO_ONBOARDING.md.

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var data: Dictionary = {}
var warrior: Dictionary = {}
var busy := false

func _ready() -> void:
	var ui := UiKit.scaffold(self, "Diário de Missões", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	var rs = await Api.batch_get(["/api/starter-quests", "/api/warrior"])
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
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	content.add_child(UiKit.section("Deveres do Recruta"))
	content.add_child(UiKit.dim(Lang.t("A guarnição precisa de provas do seu valor. Traga o que pedem e eles te recompensam.")))
	var quests: Array = data.get("quests", []) if data.get("quests") is Array else []
	for q in quests:
		if q is Dictionary:
			content.add_child(_quest_card(q))

func _quest_card(q: Dictionary) -> PanelContainer:
	var st := str(q.get("state", "available"))
	var need_qty := int(q.get("needQty", 0))
	var have := int(q.get("have", 0))
	var enough := have >= need_qty
	var border := UiKit.OK if st == "done" else (UiKit.GOLD if st == "accepted" else UiKit.BRONZE)
	var res := UiKit.card(border, true)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var npc := Label.new()
	npc.text = str(q.get("npc", "?"))
	npc.add_theme_font_size_override("font_size", 16)
	npc.add_theme_color_override("font_color", UiKit.OK if st == "done" else UiKit.GOLD)
	box.add_child(npc)
	box.add_child(UiKit.dim(str(q.get("flavor", ""))))
	var need_lbl := Label.new()
	need_lbl.text = Lang.t("Você precisa de %d %s (tem %d)") % [need_qty, str(q.get("needName", "?")), have]
	need_lbl.add_theme_font_size_override("font_size", 13)
	need_lbl.add_theme_color_override("font_color", UiKit.TEXT if enough else UiKit.WARN)
	box.add_child(need_lbl)
	var rew := HBoxContainer.new(); rew.add_theme_constant_override("separation", 10)
	var xp := int(q.get("rewardXp", 0))
	if xp > 0:
		var xl := Label.new(); xl.text = "+%d XP" % xp
		xl.add_theme_font_size_override("font_size", 13)
		xl.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		xl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		rew.add_child(xl)
	rew.add_child(UiKit.coin_box(int(q.get("rewardBronze", 0)), 14))
	box.add_child(rew)
	var which := str(q.get("id", ""))
	if st == "done":
		var d := Label.new(); d.text = Lang.t("Já cumprido")
		d.add_theme_font_size_override("font_size", 13)
		d.add_theme_color_override("font_color", UiKit.OK)
		box.add_child(d)
	elif st == "accepted":
		var b := UiKit.action(Lang.t("Entregar"), _turn_in.bind(which))
		if not enough:
			b.disabled = true
		box.add_child(b)
	else:   # available
		box.add_child(UiKit.dim(Lang.t("Disponível com %s") % str(q.get("npc", "?"))))
		box.add_child(UiKit.action(Lang.t("Aceitar"), _accept.bind(which)))
	return pc

func _accept(which: String) -> void:
	if busy:
		return
	busy = true
	var r = await Api.starter_quest_accept(which)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		data = r["json"]
		_render()
		UiKit.flash(status, Lang.t("Missão aceita!"), 1)
		if UiKit.starter_changed_sink.is_valid():
			UiKit.starter_changed_sink.call()
	else:
		UiKit.show_error(status, r)

func _turn_in(which: String) -> void:
	if busy:
		return
	busy = true
	var r = await Api.starter_quest_turn_in(which)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		data = r["json"]
		var wrs = await Api.batch_get(["/api/warrior"])
		var wr = wrs[0]
		if wr.get("ok") and wr.get("json") is Dictionary:
			warrior = wr["json"]
		_render()
		UiKit.flash(status, Lang.t("Recompensa entregue!"), 1)
		if UiKit.starter_changed_sink.is_valid():
			UiKit.starter_changed_sink.call()
	else:
		UiKit.show_error(status, r)
