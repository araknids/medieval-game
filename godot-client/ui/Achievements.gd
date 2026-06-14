extends Control
# ── Tela CONQUISTAS / TÍTULOS ─────────────────────────────────────────────────────
# Lê GET /api/achievements (catálogo + título ativo), lista desbloqueadas/bloqueadas
# por categoria e deixa escolher o título ativo (POST /api/achievements/title). [TITULOS]
# Padrão visual: UiKit [PADRAO_UI_GODOT]. Volta pro Hub (sinal go_back). [MIGRACAO_GODOT]

signal go_back

var data: Dictionary = {}        # {activeTitle, achievements:[...]} vindo do backend
var warrior: Dictionary = {}     # /api/warrior (carteira do header)
var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var status_filter := "all"       # filtro do catálogo: all / unlocked / locked

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🏆 Conquistas", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_DEFAULT)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/achievements", "/api/warrior"])
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
	var all: Array = data.get("achievements", []) if data.get("achievements") is Array else []
	var active := str(data.get("activeTitle", ""))
	var unlocked: Array = []
	for a in all:
		if a is Dictionary and bool(a.get("unlocked", false)):
			unlocked.append(a)

	# ── Cabeçalho: contagem + dica ──
	content.add_child(UiKit.section(Lang.t("Conquistas & Títulos   (%d/%d)") % [unlocked.size(), all.size()]))
	var hint := Label.new()
	hint.text = "👑 Título ativo — escolha um para exibir antes do seu nome (todos veem):"
	hint.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	hint.add_theme_font_size_override("font_size", 13)
	hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	content.add_child(hint)

	# ── Picker de título (None + cada desbloqueado) ──
	if unlocked.is_empty():
		content.add_child(UiKit.empty("Nenhum título ainda", "Desbloqueie conquistas abaixo para ganhar títulos."))
	else:
		content.add_child(_title_card("Nenhum", "", active == ""))
		for a in unlocked:
			var t := str(a.get("title", ""))
			content.add_child(_title_card("👑 %s" % t, str(a.get("id", "")), t == active))

	# ── Catálogo por categoria (com filtro de status no topo) ──
	content.add_child(UiKit.section("Catálogo"))
	content.add_child(UiKit.filter_row([
		{"label": "Todas", "value": "all", "color": UiKit.GOLD},
		{"label": "Desbloqueadas", "value": "unlocked", "color": UiKit.OK},
		{"label": "Bloqueadas", "value": "locked", "color": UiKit.TEXT_DIM},
	], status_filter, _set_filter))
	var cats: Array = []                  # mantém ordem de aparição
	var by_cat: Dictionary = {}
	for a in all:
		if not (a is Dictionary): continue
		if not _passes_filter(a): continue
		var cat := str(a.get("category", "—"))
		if not by_cat.has(cat):
			by_cat[cat] = []
			cats.append(cat)
		by_cat[cat].append(a)
	if cats.is_empty():
		content.add_child(UiKit.dim("— nenhuma conquista neste filtro —"))
	for cat in cats:
		content.add_child(UiKit.section(cat))
		# cards compactos em grid (2–3 col) p/ encurtar a tela longa
		var cards: Array = by_cat[cat]
		content.add_child(UiKit.grid(self, cards, _ach_card, true))

# Filtro de status do catálogo (não afeta o picker de título).
func _passes_filter(a: Dictionary) -> bool:
	if status_filter == "unlocked":
		return bool(a.get("unlocked", false))
	if status_filter == "locked":
		return not bool(a.get("unlocked", false))
	return true

func _set_filter(v) -> void:
	status_filter = str(v)
	_render()

# ── linha de conquista ──────────────────────────────────────────────────────────
func _ach_card(a: Dictionary) -> PanelContainer:
	var unlocked := bool(a.get("unlocked", false))
	var res := UiKit.card(UiKit.GOLD_SOFT if unlocked else Color(0.3, 0.3, 0.34, 0.6), unlocked)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new()
	hb.add_theme_constant_override("separation", 10)
	box.add_child(hb)
	var icon := Label.new()
	icon.text = "🏆" if unlocked else "🔒"
	icon.custom_minimum_size = Vector2(28, 0)
	icon.add_theme_font_size_override("font_size", 18)
	hb.add_child(icon)
	# meio: nome + título + descrição
	var mid := VBoxContainer.new()
	mid.add_theme_constant_override("separation", 2)
	mid.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	nm.text = "%s  “%s”" % [str(a.get("displayName", "?")), str(a.get("title", ""))]
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	mid.add_child(nm)
	mid.add_child(UiKit.dim(str(a.get("description", ""))))
	hb.add_child(mid)
	# direita: progresso / ✔
	var val := Label.new()
	if unlocked:
		val.text = "✔"
		val.add_theme_color_override("font_color", UiKit.GOLD)
	else:
		val.text = "%d/%d" % [int(a.get("current", 0)), int(a.get("threshold", 0))]
		val.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	val.add_theme_font_size_override("font_size", 14)
	val.custom_minimum_size = Vector2(70, 0)
	val.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	hb.add_child(val)
	# P2: barra de progresso p/ conquista bloqueada com métrica conhecida
	if not unlocked:
		var threshold := int(a.get("threshold", 0))
		if threshold > 0:
			box.add_child(UiKit.bar("Progresso", int(a.get("current", 0)), threshold, UiKit.GOLD_SOFT))
	return pc

# ── ação: escolher título ──────────────────────────────────────────────────────
# Cartão de título com botão "Usar título" / "Remover" (já ativo).
func _title_card(label: String, id: String, on: bool) -> PanelContainer:
	var res := UiKit.card(UiKit.GOLD if on else UiKit.BRONZE)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new()
	hb.add_theme_constant_override("separation", 10)
	box.add_child(hb)
	var nm := Label.new()
	nm.text = label
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.GOLD if on else UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	hb.add_child(nm)
	if on:
		var act := Label.new()
		act.text = "✔ em uso"
		act.add_theme_font_size_override("font_size", 13)
		act.add_theme_color_override("font_color", UiKit.OK)
		hb.add_child(act)
		# já ativo: oferece remover (a não ser que seja "Nenhum", que já é o estado limpo)
		if id != "":
			hb.add_child(UiKit.small_btn("Remover", _select_title.bind(""), true))
	else:
		hb.add_child(UiKit.small_btn("Usar título", _select_title.bind(id)))
	return pc

func _select_title(id: String) -> void:
	if busy: return
	busy = true
	# body: {id} — string vazia limpa o título (backend trata blank/"none" como limpar)
	var r = await Api.select_title(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		data["activeTitle"] = str(r["json"].get("activeTitle", ""))
		UiKit.flash(status, Lang.t("Título atualizado."), 1)
		_render()
	else:
		UiKit.show_error(status, r)
