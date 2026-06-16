extends Control
# ── Tela INCURSÃO / DELVE [INCURSAO] ───────────────────────────────────────────────
# Run roguelike de mapa ramificado (Slay-the-Spire). Lança uma run (quest=KINGDOM ou coleta=ZONE),
# navega o mapa de nós (COMBAT/ELITE/TREASURE/EVENT/CAMP/BOSS), e a cada parada decide SACAR (seguro)
# ou AVANÇAR (mais fundo = mais forte = loot melhor). HP carrega entre batalhas; KO/abandono perde a
# bolsa não-travada. Espelha o bloco [INCURSAO] do app.js. Backend: /api/expedition. Padrão: UiKit.

signal go_back
signal request_battle(data)   # pede ao App o replay 3D (overlay), como World/Tower [MIGRACAO_GODOT]
signal open_screen(name)      # [INCURSAO] sem run → botão "Ir ao Mundo" (a aba saiu)

const Icons := preload("res://ui/Icons.gd")

# tipo de nó → [emoji-fallback, rótulo, cor, ícone-pixel] (ícones PixelLab em assets/ui/icons/)
const NODE := {
	"COMBAT":   ["⚔", "Combate",  Color(0.62, 0.64, 0.70), "node_combat"],
	"ELITE":    ["💀", "Elite",    Color(0.79, 0.49, 0.86), "node_elite"],
	"TREASURE": ["🎁", "Tesouro",  Color(0.96, 0.66, 0.26), "node_treasure"],
	"EVENT":    ["📜", "Evento",   Color(0.50, 0.70, 1.0),  "node_event"],
	"CAMP":     ["🔥", "Descanso", Color(0.30, 0.80, 0.51), "node_camp"],
	"BOSS":     ["👑", "Chefe",    Color(0.94, 0.33, 0.33), "node_boss"],
}
const KINGDOMS := [
	["COMBAT", "⚔ Fortaleza Maldita"], ["FISHING", "🎣 Garganta dos Ossos"], ["MINING", "⛏ Minas de Ferro Negro"],
	["GRUTAS_DE_CRISTAL", "🔮 Grutas de Cristal"], ["MAR_ABENCOADO", "🌊 Mar Abençoado"],
]
const SKILLS := [["FISHING", "🎣 Pesca"], ["MINING", "⛏ Mineração"], ["GARIMPO", "🔎 Garimpo"]]
const ELEMENTS := [["", "∅"], ["FIRE", "🔥"], ["WATER", "💧"], ["EARTH", "🪨"], ["AIR", "💨"]]
const TIERS := [[1, "Fácil"], [2, "Normal"], [3, "Difícil"]]

var content: VBoxContainer
var status: Label
var wallet
var warrior: Dictionary = {}
var run: Dictionary = {}          # run ativa (/api/expedition/current) ou {} (launcher)
var busy := false
var sel_tier := 1
var sel_element := ""
var _pending_after := {}          # resultado guardado durante o replay 3D [BATTLE_REPORT]

func _ready() -> void:
	var ui := UiKit.scaffold(self, "📜 Incursão", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/warrior", "/api/expedition/current"])
	var wr = rs[0]
	if wr.get("ok") and wr.get("json") is Dictionary:
		warrior = wr["json"]
	var cr = rs[1]
	run = cr["json"] if (cr.get("ok") and cr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	if bool(run.get("active", false)):
		_render_map()
	else:
		_render_launcher()

# ── Sem run ativa: a aba saiu da nav; oriente a entrar por uma ZONA do reino no Mundo ──────────────
func _render_launcher() -> void:
	content.add_child(UiKit.dim("Você não está em nenhuma Incursão."))
	content.add_child(UiKit.dim("Entre numa zona (🟢/🟡/🔴) de um reino no Mundo para começar — a cor define a dificuldade e o loot."))
	var b := UiKit.action("🌍 Ir ao Mundo", func() -> void: open_screen.emit("World"))
	b.custom_minimum_size = Vector2(200, 40)
	content.add_child(b)

func _set_tier(t: int) -> void:
	sel_tier = t
	_render()

func _set_element(e: String) -> void:
	sel_element = e
	_render()

func _start_kingdom(kingdom: String) -> void:
	if busy: return
	busy = true
	var r = await Api.expedition_start("KINGDOM", kingdom, null, null, sel_element, sel_tier)
	busy = false
	await _after_start(r)

func _start_zone(skill: String) -> void:
	if busy: return
	var zone := "HIGH_RISK" if sel_tier >= 3 else ("PVP" if sel_tier == 2 else "SAFE")
	var kingdom := "FISHING" if skill == "FISHING" else ("MINING" if skill == "MINING" else "GRUTAS_DE_CRISTAL")
	busy = true
	var r = await Api.expedition_start("ZONE", kingdom, zone, skill, sel_element, sel_tier)
	busy = false
	await _after_start(r)

func _after_start(r) -> void:
	if r.get("ok") and r.get("json") is Dictionary:
		run = r["json"]
		await _reload_warrior()   # estamina mudou
		_render()
	else:
		_show_error(r)

# ── Mapa da run ─────────────────────────────────────────────────────────────────────
func _render_map() -> void:
	var cur := int(run.get("currentLayer", 0))
	var depth := int(run.get("depth", 0))
	var status_str := str(run.get("status", ""))
	content.add_child(UiKit.section("📜 Incursão — Tier %d" % int(run.get("tier", 1))))
	content.add_child(_bag_row())

	# [INCURSAO_AUTO_EXTRACT] Vencida (chefe derrotado) → saca AUTOMÁTICO, loot direto pro inventário.
	# Sem botão "Sacar e Sair" — o que foi garantido na run vai pro inventário sozinho.
	if cur >= depth:
		content.add_child(UiKit.dim(Lang.t("✅ Incursão vencida! Recolhendo o loot…")))
		if not busy:
			_extract()
		return
	for layer in run.get("map", []):
		if layer is Dictionary:
			content.add_child(_layer_row(layer, cur, status_str))

	content.add_child(HSeparator.new())
	# Só "Abandonar": o garantido vai pro inventário; o carregado (em risco) é perdido.
	content.add_child(UiKit.action_danger("Abandonar", _confirm_abandon))

	# EVENTO pendente → botão pra (re)abrir o diálogo. NÃO auto-abre aqui (o _handle_step já abre uma
	# vez); o botão cobre o caso de sair e voltar pra tela no meio de um evento.
	if status_str == "NODE_PENDING" and run.get("dialog") is Dictionary:
		content.add_child(UiKit.action("📜 Continuar evento", func() -> void: _show_event_dialog(run["dialog"])))

func _layer_row(layer: Dictionary, cur: int, status_str: String) -> VBoxContainer:
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 4)
	var idx := int(layer.get("index", 0))
	if idx == cur and status_str == "IN_PROGRESS":
		box.add_child(UiKit.dim("▼ Escolha seu caminho"))
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 8)
	for n in layer.get("nodes", []):
		if n is Dictionary:
			hb.add_child(_node_chip(n))
	box.add_child(hb)
	return box

func _node_chip(n: Dictionary) -> Control:
	var type := str(n.get("type", ""))
	var meta = NODE.get(type, ["?", type, UiKit.TEXT_DIM])
	var icon_key := str(meta[3]) if meta.size() > 3 else ""
	var reachable := bool(n.get("reachable", false))
	if reachable:
		var b := UiKit.action("%s %s" % [str(meta[0]), str(meta[1])], _choose_node.bind(str(n.get("id", ""))))
		b.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		b.custom_minimum_size = Vector2(128, 52)
		b.add_theme_color_override("font_color", meta[2])
		# ícone pixel à esquerda do rótulo (fallback: mantém o emoji no texto)
		if icon_key != "" and Icons.set_icon(b, icon_key):
			b.add_theme_constant_override("icon_max_width", 30)
			b.text = str(meta[1])
		return b
	# inalcançável → chip estático (preview do caminho)
	var res := UiKit.card(Color(0.3, 0.3, 0.3, 0.5))
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	panel.custom_minimum_size = Vector2(128, 52)
	panel.modulate = Color(1, 1, 1, 0.5)
	var icon_tex: Texture2D = Icons.tex(icon_key) if icon_key != "" else null
	if icon_tex != null:
		var row := HBoxContainer.new()
		row.alignment = BoxContainer.ALIGNMENT_CENTER
		row.add_theme_constant_override("separation", 6)
		row.add_child(Icons.rect(icon_key, 22))
		var nl := Label.new()
		nl.text = str(meta[1])
		nl.add_theme_font_size_override("font_size", 13)
		nl.add_theme_color_override("font_color", meta[2])
		row.add_child(nl)
		vb.add_child(row)
	else:
		var l := Label.new()
		l.text = "%s %s" % [str(meta[0]), str(meta[1])]
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		l.add_theme_font_size_override("font_size", 13)
		l.add_theme_color_override("font_color", meta[2])
		vb.add_child(l)
	return panel

func _bag_row() -> HBoxContainer:
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	hb.add_child(_bag_card("🎒 Carregado (em risco)", run.get("carried", {}), UiKit.WARN))
	hb.add_child(_bag_card("🔒 Garantido", run.get("secured", {}), UiKit.OK))
	return hb

func _bag_card(title: String, bag, col: Color) -> PanelContainer:
	var res := UiKit.card(col)
	var panel: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	panel.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	# [ICONES_MARCADOR] título com ícone PixelLab (carregado=mochila / garantido=cadeado)
	var t := UiKit.icon_text(title, 12, col, 18)
	vb.add_child(t)
	vb.add_child(_bag_chips(bag if bag is Dictionary else {}))
	return panel

# Conteúdo da bolsa como CHIPS [ícone][número] (bronze/xp/recursos) em vez de string com emoji. [ICONES_MARCADOR]
func _bag_chips(bag: Dictionary) -> Control:
	var flow := HFlowContainer.new()
	flow.add_theme_constant_override("h_separation", 10)
	flow.add_theme_constant_override("v_separation", 2)
	var any := false
	if int(bag.get("bronze", 0)) > 0:
		flow.add_child(_chip("bronze", str(int(bag.get("bronze", 0))))); any = true
	if int(bag.get("xp", 0)) > 0:
		flow.add_child(_chip("star", str(int(bag.get("xp", 0))))); any = true
	if bag.get("resources") is Array:
		for d in bag["resources"]:
			if d is Dictionary:
				flow.add_child(_chip("package", "%s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))])); any = true
	if not any:
		return UiKit.dim("vazio")
	return flow

func _chip(icon_key: String, text: String) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 4)
	var ic := Icons.rect(icon_key, 16); ic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(ic)
	var l := Label.new(); l.text = text
	l.add_theme_font_size_override("font_size", 12)
	l.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(l)
	return h

# ── Ações da run ────────────────────────────────────────────────────────────────────
func _run_id() -> int:
	return int(run.get("id", 0))

# Run vencida (chegou ao fim) e ainda ativa → o _render dispara o saque automático. [INCURSAO_AUTO_EXTRACT]
func _is_won() -> bool:
	return bool(run.get("active", false)) and int(run.get("currentLayer", 0)) >= int(run.get("depth", 0))

func _choose_node(node_id: String) -> void:
	if busy: return
	busy = true
	var r = await Api.expedition_choose(_run_id(), node_id)
	busy = false
	await _handle_step(r)

func _resolve_event(option_id: String) -> void:
	if busy: return
	busy = true
	var r = await Api.expedition_node(_run_id(), option_id)
	busy = false
	await _handle_step(r)

# Trata a resposta de choose/node: evento pendente → diálogo; combate → replay 3D; senão → relatório.
func _handle_step(r) -> void:
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); await _refresh(); return
	var j: Dictionary = r["json"]
	if bool(j.get("nodePending", false)) and j.get("dialog") is Dictionary:
		if j.get("state") is Dictionary:
			run = j["state"]
		_render()
		_show_event_dialog(j["dialog"])
		return
	var be = j.get("battleEvents")
	if be is Array and be.size() >= 2:
		# encontrou combate → replay 3D por cima; guarda o resultado p/ o relatório pós-replay
		_pending_after = {"result": j}
		request_battle.emit({"events": be, "scene": str(j.get("scene", "")), "won": not bool(j.get("ko", false)), "enemy": str(j.get("monsterName", ""))})
		return
	if j.get("state") is Dictionary:
		run = j["state"]
	await _reload_warrior()   # CAMP cura HP; nós dão estado fresco
	_render()                 # se venceu, _render dispara o saque automático (mostra o relatório de loot)
	if not _is_won():
		_show_step_report(j)

# o App/Shell chama isto quando o replay 3D termina
func _on_battle_over() -> void:
	var result: Dictionary = _pending_after["result"] if _pending_after.get("result") is Dictionary else {}
	_pending_after = {}
	await _refresh()          # se venceu, _render disparou o saque automático
	if not _is_won() and not result.is_empty():
		_show_step_report(result)

func _extract() -> void:
	if busy: return
	busy = true
	var r = await Api.expedition_extract(_run_id())
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		_show_error(r); await _refresh(); return
	var j: Dictionary = r["json"]
	await _refresh()   # run encerrada → volta pro launcher
	_show_extract_report(j)

func _confirm_abandon() -> void:
	_choice_dialog("Abandonar a incursão? Você fica só com o que já foi garantido (descansos/extração) — o loot não-sacado é perdido.",
		[["Abandonar", "yes"], ["Voltar", "no"]],
		func(choice) -> void:
			if str(choice) != "yes":
				return
			if busy: return
			busy = true
			var r = await Api.expedition_abandon(_run_id())
			busy = false
			if not (r.get("ok")):
				_show_error(r)
			await _refresh()
			UiKit.flash(status, "Incursão abandonada.", 0))

func _reload_warrior() -> void:
	var r = await Api.get_warrior()
	if r.get("ok") and r.get("json") is Dictionary:
		warrior = r["json"]

# ── Relatórios / diálogos (espelham World.gd) ───────────────────────────────────────
func _show_step_report(j: Dictionary) -> void:
	var ko := bool(j.get("ko", false))
	var log: Array = j.get("battleLog", []) if j.get("battleLog") is Array else []
	if not log.is_empty():
		var won := not ko
		var mob := str(j.get("monsterName", "inimigo"))
		var title := (Lang.t("⚔ %s derrotado!") % mob) if won else (Lang.t("💀 Derrotado por %s!") % mob)
		UiKit.show_battle_report(self, won, title, _reward_rows(j, ko), log)
	else:
		_show_result(_step_text(j))

func _reward_rows(j: Dictionary, ko := false) -> Array:
	var rows: Array = []
	if int(j.get("bronzeGained", 0)) > 0:
		rows.append(UiKit.kv_node("Bronze", UiKit.coin_box(int(j.get("bronzeGained", 0)), 18)))
	if int(j.get("xpGained", 0)) > 0:
		rows.append(UiKit.kv("⭐ Experiência", "+%d XP" % int(j.get("xpGained", 0))))
	if j.get("drops") is Array:
		for d in j["drops"]:
			if d is Dictionary:
				rows.append(UiKit.icon_text("📦 %s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))], 12, UiKit.TEXT_DIM, 16))
	if str(j.get("lootItemName", "")) != "":
		rows.append(UiKit.icon_text("🎁 " + str(j.get("lootItemName")), 12, UiKit.TEXT_DIM, 16))
	if ko:
		rows.append(UiKit.icon_text(Lang.t("☠ Você caiu — o loot não-sacado foi perdido. Cure-se no Templo."), 12, UiKit.ERR, 16))
	return rows

func _step_text(j: Dictionary) -> String:
	var parts: Array = []
	if str(j.get("resolvedType", "")) == "CAMP":
		parts.append(Lang.t("🔥 Descanso: vida recuperada e loot garantido."))
	elif str(j.get("narrative", "")) != "":
		parts.append(str(j.get("narrative")))
	if int(j.get("bronzeGained", 0)) > 0: parts.append("🥉 +%d bronze" % int(j.get("bronzeGained", 0)))
	if int(j.get("xpGained", 0)) > 0: parts.append("⭐ +%d XP" % int(j.get("xpGained", 0)))
	if j.get("drops") is Array:
		for d in j["drops"]:
			if d is Dictionary:
				parts.append("📦 %s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))])
	if str(j.get("lootItemName", "")) != "": parts.append("🎁 " + str(j.get("lootItemName")))
	return "   ".join(parts) if not parts.is_empty() else Lang.t("Você segue em frente.")

func _show_extract_report(j: Dictionary) -> void:
	var rows: Array = []
	if int(j.get("bronzeBanked", 0)) > 0:
		rows.append(UiKit.kv_node("Bronze", UiKit.coin_box(int(j.get("bronzeBanked", 0)), 18)))
	if int(j.get("xpBanked", 0)) > 0:
		rows.append(UiKit.kv("⭐ Experiência", "+%d XP" % int(j.get("xpBanked", 0))))
	if j.get("bankedResources") is Array:
		for d in j["bankedResources"]:
			if d is Dictionary:
				rows.append(UiKit.icon_text("📦 %s x%d" % [str(d.get("displayName", "?")), int(d.get("quantity", 0))], 12, UiKit.TEXT_DIM, 16))
	if int(j.get("keptItems", 0)) > 0:
		rows.append(UiKit.icon_text(Lang.t("🛡 %d item(ns) na mochila") % int(j.get("keptItems", 0)), 12, UiKit.TEXT_DIM, 16))
	if int(j.get("mailedItems", 0)) > 0:
		rows.append(UiKit.icon_text(Lang.t("📬 %d item(ns) no correio (mochila cheia)") % int(j.get("mailedItems", 0)), 12, UiKit.TEXT_DIM, 16))
	UiKit.show_battle_report(self, true, Lang.t("🔒 Loot garantido!"), rows, [])

# Diálogo do nó EVENTO: intro + um botão por opção (resolve com o optionId). Espelha World._show_quest_dialog.
func _show_event_dialog(dialog: Dictionary) -> void:
	var opts: Array = []
	for o in dialog.get("options", []):
		if o is Dictionary:
			var lbl := str(o.get("label", "?"))
			if str(o.get("hint", "")) != "":
				lbl += "   🎲 " + str(o.get("hint"))
			opts.append([lbl, str(o.get("id", ""))])
	_choice_dialog(str(dialog.get("intro", "")), opts, func(opt_id) -> void:
		await _resolve_event(str(opt_id)))

# Overlay genérico de escolha: título + botões. cb.call(valor) ao escolher. (copiado de World.gd)
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

# Modal de resultado simples (texto + OK). (copiado de World.gd)
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

func _show_error(r) -> void:
	UiKit.show_error(status, r)
	_show_result("⚠ " + UiKit.err_text(r))
