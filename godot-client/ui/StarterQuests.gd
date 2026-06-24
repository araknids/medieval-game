extends Control
# ── Tela DIÁRIO DE MISSÕES — Deveres do Recruta ([ONBOARDING v3]) ─────────────────────
# Aberta pelo ícone de quest no topbar. Lista os 3 deveres-tutorial por ESTADO:
# locked (pré não cumprido) / available (Aceitar) / accepted (Concluir|Curar|Entregar, por tipo) / done.
# Lê GET /api/starter-quests + /api/warrior; aceita/conclui via POST .../{accept|turn-in}.
# Avisa o Shell (UiKit.starter_changed_sink) p/ atualizar badges. Sem emoji de web [SEM_WEB_EMOJI].
# Desenho: docs/PLANO_ONBOARDING.md.

signal go_back

const Icons := preload("res://ui/Icons.gd")   # [ONBOARDING] retrato do NPC nos cards (não é autoload)

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
	var comp := str(q.get("comp", ""))
	var which := str(q.get("id", ""))
	var locked := st == "locked"
	var border := UiKit.OK if st == "done" else (UiKit.GOLD if st == "accepted" else UiKit.BRONZE)
	var res := UiKit.card(border, not locked)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	# [ONBOARDING] cabeçalho do card: retrato de quem pediu (Garrick / Padre Anselmo) + nome
	var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 10)
	var portrait_key: String = {"equip": "veteran", "quest": "veteran", "heal": "priest"}.get(str(q.get("id", "")), "")
	if portrait_key != "" and Icons.tex(portrait_key) != null:
		var pr := Icons.rect(portrait_key, 48)
		pr.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		head.add_child(pr)
	var title := str(q.get("npc", "")).strip_edges()
	if title == "":
		title = Lang.t("Arme-se, recruta")
	var nl := Label.new()
	nl.text = title
	nl.add_theme_font_size_override("font_size", 16)
	nl.add_theme_color_override("font_color", UiKit.OK if st == "done" else UiKit.GOLD)
	nl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	head.add_child(nl)
	box.add_child(head)
	box.add_child(UiKit.dim(str(q.get("flavor", ""))))
	# recompensa
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
	# ação por estado
	if st == "locked":
		box.add_child(UiKit.dim(Lang.t("Conclua o dever anterior primeiro.")))
	elif st == "available":
		box.add_child(_action_btn(UiKit.action(Lang.t("Aceitar"), _accept.bind(which))))
	elif st == "accepted":
		if comp == "QUEST":   # completa por evento (fazer 1 missão) → guia pro Mundo
			box.add_child(UiKit.dim(Lang.t("Complete uma missão no Mundo para cumprir este dever.")))
			box.add_child(_action_btn(UiKit.action(Lang.t("Ir ao Mundo"), func() -> void:
				if Shell.current != null:
					Shell.current._open("World"))))
		else:
			box.add_child(_action_btn(UiKit.action(_action_label(comp), _turn_in.bind(which))))
	else:   # done
		var d := Label.new(); d.text = Lang.t("Já cumprido")
		d.add_theme_font_size_override("font_size", 13)
		d.add_theme_color_override("font_color", UiKit.OK)
		box.add_child(d)
	return pc

func _action_label(comp: String) -> String:
	return Lang.t("Curar") if comp == "HEAL" else Lang.t("Concluir")

# [ONBOARDING] botão de ação compacto, alinhado à direita (não estica no VBox do card)
func _action_btn(b: Button) -> Button:
	b.size_flags_horizontal = Control.SIZE_SHRINK_END
	return b

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
