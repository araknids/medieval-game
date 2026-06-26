extends Control
# ── Tela LOJA ─────────────────────────────────────────────────────────────────────
# Lista GET /api/shop (mercador + itens em rotação de 6h) + /api/warrior (carteira/preço)
# e compra item único (POST /api/shop/buy/{id}). Item comprado fica marcado "✔ Comprado" e
# afunda pro fim da lista. Nome colorido pela raridade. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

const Icons := preload("res://ui/Icons.gd")
const MERCHANT_ICONS := 20   # [LOJA_MERCADOR] variações de retrato (merchant_1..20.png); varia por mercador

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var data: Dictionary = {}        # cache do GET /api/shop (items + mercador + timer)
var warrior: Dictionary = {}     # /api/warrior (carteira + bronze p/ affordability)
var secs := 0                    # segundos até a próxima rotação (decai por _process)
var rarity_filter := 0           # filtro de raridade dos itens à venda (0=Todas, 1-5)
var category_filter := "all"     # [LOJA_FILTRO] categoria: all|weapon|armor|accessory (mesmo da Forja)
var sort_by_price := false       # [PLAYTEST_FIX] ordenar itens por preço (asc) em vez da ordem de rotação

# [LOJA_FILTRO] chips de categoria (espelha CRAFT_CATEGORIES da Forge.gd)
const SHOP_CATEGORIES := [["all", "Todas"], ["weapon", "⚔ Armas"], ["armor", "🛡 Armadura"], ["accessory", "💍 Acessórios"]]

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🛒 Loja", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	var rs = await Api.batch_get(["/api/shop", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	data = r["json"]
	secs = int(data.get("secondsUntilNext", 0))
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

# timer da rotação: decai em tempo real; ao zerar, recarrega a loja.
func _process(delta: float) -> void:
	if secs <= 0 or data.is_empty():
		return
	secs = maxi(0, secs - int(delta))
	_update_timer_label()
	if secs <= 0:
		call_deferred("_refresh")

var _timer_label: Label

func _update_timer_label() -> void:
	if _timer_label == null or not is_instance_valid(_timer_label):
		return
	var h := secs / 3600
	var mm := (secs % 3600) / 60
	var ss := secs % 60
	_timer_label.text = Lang.t("Próxima rotação\n%dh %02dm %02ds") % [h, mm, ss]   # [SEM_WEB_EMOJI] sem 🛒
	# P2: faltando menos de 10 min → cor de alerta.
	_timer_label.add_theme_color_override("font_color", UiKit.WARN if secs < 600 else UiKit.TEXT_DIM)

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	# ── Card do mercador ── [LOJA_MERCADOR] retrato num CARD (mais visível) + timer da rotação À DIREITA
	# (antes era retrato/nome/quote/timer empilhados → comprido). Fallback no ícone 'character'.
	var mname := str(data.get("merchantName", "Mercador"))
	var mkey := "merchant_%d" % (abs(mname.hash()) % MERCHANT_ICONS + 1)
	var icon_key := mkey if Icons.tex(mkey) != null else ("character" if Icons.tex("character") != null else "")
	var mres := UiKit.card(UiKit.GOLD_SOFT)
	var mrow := HBoxContainer.new(); mrow.add_theme_constant_override("separation", 12)
	(mres[1] as VBoxContainer).add_child(mrow)
	if icon_key != "":
		var mic := Icons.rect(icon_key, 56); mic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		mrow.add_child(mic)
	var info := VBoxContainer.new(); info.add_theme_constant_override("separation", 2)
	info.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	info.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var name_lbl := Label.new()
	name_lbl.text = mname
	name_lbl.add_theme_font_size_override("font_size", 20)
	name_lbl.add_theme_color_override("font_color", UiKit.GOLD)
	info.add_child(name_lbl)
	var quote := str(data.get("merchantQuote", ""))
	if quote != "":
		info.add_child(UiKit.dim("\"%s\"" % quote))
	mrow.add_child(info)
	_timer_label = Label.new()
	_timer_label.add_theme_font_size_override("font_size", 13)
	_timer_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	_timer_label.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	mrow.add_child(_timer_label)
	if Shell.current != null:   # [ONBOARDING v2] botão de quest do Lojista (se disponível)
		var qb = Shell.current.quest_button_for("Shop")
		if qb != null:
			mrow.add_child(qb)
	content.add_child(mres[0])
	_update_timer_label()
	# ── Itens ──  (P1: comprados afundam pro fim)
	var items: Array = data.get("items", []) if data.get("items") is Array else []
	var sorted_items: Array = []
	for it in items:
		if it is Dictionary and not bool(it.get("purchased", false)):
			sorted_items.append(it)
	for it in items:
		if it is Dictionary and bool(it.get("purchased", false)):
			sorted_items.append(it)
	content.add_child(UiKit.section(Lang.t("Itens (%d)") % items.size()))
	content.add_child(_category_filter_row())   # [LOJA_FILTRO] filtro por tipo de equipamento (igual à Forja)
	content.add_child(UiKit.rarity_filter(rarity_filter, _set_rarity))
	# [PLAYTEST_FIX] botão de ordenar por preço (apagado quando desligado, igual aos chips de filtro)
	var sort_btn := UiKit.small_btn(Lang.t("Ordenar por preço"), _toggle_price_sort)
	if not sort_by_price:
		sort_btn.modulate = Color(1, 1, 1, 0.5)
	content.add_child(sort_btn)
	if items.is_empty():
		content.add_child(UiKit.empty("Sem itens nesta rotação", "Volte após a próxima rotação do mercador"))
	else:
		var shown: Array = []
		for it in sorted_items:
			if not (it is Dictionary): continue
			if category_filter != "all" and _item_category(str(it.get("type", ""))) != category_filter: continue
			if rarity_filter > 0 and int(it.get("rarity", 1)) != rarity_filter: continue
			shown.append(it)
		if sort_by_price:   # [PLAYTEST_FIX] preço asc, mas comprados continuam afundando pro fim
			shown.sort_custom(func(a, b): return (bool(a.get("purchased", false)) == bool(b.get("purchased", false)) and int(a.get("price", 0)) < int(b.get("price", 0))) or (not bool(a.get("purchased", false)) and bool(b.get("purchased", false))))
		if shown.is_empty():
			content.add_child(UiKit.dim("— nada com esse filtro —"))
		else:
			content.add_child(UiKit.grid(self, shown, _shop_card, true, 188.0, 3))   # [LOJA] mesmo padrão do inventário

# [LOJA_FILTRO] mapeia o ItemType (vindo do backend em `type`) p/ a categoria do filtro
func _item_category(t: String) -> String:
	if t == "WEAPON": return "weapon"
	if t == "RING" or t == "NECKLACE": return "accessory"
	return "armor"   # SHIELD/HELMET/ARMOR/PANTS/BOOTS/GLOVES/SHOULDER

# [LOJA_FILTRO] linha de chips de categoria (espelha _category_filter_row da Forge.gd)
func _category_filter_row() -> Control:
	var row := HFlowContainer.new()
	row.add_theme_constant_override("h_separation", 6)
	row.add_theme_constant_override("v_separation", 6)
	for c in SHOP_CATEGORIES:
		var b := UiKit.small_btn(str(c[1]), _set_category.bind(str(c[0])))
		b.custom_minimum_size = Vector2(0, 32)
		b.add_theme_font_size_override("font_size", 12)
		if category_filter != str(c[0]):
			b.modulate = Color(1, 1, 1, 0.5)
		row.add_child(b)
	return row

func _set_category(cat: String) -> void:
	category_filter = cat
	_render()

func _set_rarity(r: int) -> void:
	rarity_filter = r
	_render()

func _toggle_price_sort() -> void:
	sort_by_price = not sort_by_price
	_render()

# [LOJA] Slot ENXUTO no padrão do inventário (ItemTooltipCard): ícone + nome + Nv + preço; detalhe
# completo no HOVER (tooltip rico) e no CLIQUE (dialog com painel + Comprar). Espelha Character._bag_card.
func _shop_card(it) -> Control:
	if not (it is Dictionary):
		return null
	var purchased := bool(it.get("purchased", false))
	var rar := int(it.get("rarity", 1))
	var card := ItemTooltipCard.new()        # [ITEM_TOOLTIP] card com tooltip rico no hover
	card.item = it
	card.player_level = int(warrior.get("level", 0))
	card.tooltip_text = " "                  # != "" senão o tooltip custom nem dispara
	card.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	var res := UiKit.card_styled(card, UiKit.rarity_color(rar), not purchased)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(7)
	box.add_theme_constant_override("separation", 0)
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
	box.add_child(row)
	var ic := UiKit.item_icon_for(it, 28)
	if ic:
		row.add_child(ic)
	var nm := Label.new()
	nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 13)
	nm.add_theme_color_override("font_color", UiKit.rarity_color(rar))
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	nm.clip_text = true
	nm.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	row.add_child(nm)
	# [LOJA] mesmo padrão da Mochila: "Nv X" exigido (vermelho se acima do nível) + seta de comparação
	# (▲/▼/= vs o equipado — UiKit.equipped é mantido fresco pelo Shell a cada nav). Só p/ item à venda.
	if not purchased:
		var ilvl := int(it.get("itemLevel", 1))
		var plvl := int(warrior.get("level", 0))
		var lv := Label.new()
		lv.text = Lang.t("Nv %d") % ilvl
		lv.add_theme_font_size_override("font_size", 11)
		lv.add_theme_color_override("font_color", UiKit.ERR if (plvl > 0 and ilvl > plvl) else UiKit.TEXT_DIM)
		lv.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		lv.mouse_filter = Control.MOUSE_FILTER_PASS
		row.add_child(lv)
		var arrow := UiKit.compare_arrow(it)   # ▲ verde melhor / ▼ vermelho pior / = igual (null se nada p/ comparar)
		if arrow != null:
			arrow.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			row.add_child(arrow)
	# preço (ou ✔ se já comprado) à direita do slot
	var tail: Control
	if purchased:
		var done := Label.new(); done.text = "✔"
		done.add_theme_color_override("font_color", UiKit.OK)
		done.add_theme_font_size_override("font_size", 14)
		tail = done
	else:
		var price := int(it.get("price", 0))
		var total_bronze := int(warrior.get("gold", 0)) * 10000 + int(warrior.get("silver", 0)) * 100 + int(warrior.get("bronze", 0))
		var afford := warrior.is_empty() or total_bronze >= price
		tail = UiKit.coin_box(price, 15, UiKit.TEXT if afford else UiKit.ERR)
	tail.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(tail)
	# [ITEM_TOOLTIP] PASS nos filhos → hover E clique sobem pro card (que mostra o tooltip e trata o clique)
	for n in [box, row, nm, ic, tail]:
		if n != null and n is Control:
			(n as Control).mouse_filter = Control.MOUSE_FILTER_PASS
	card.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
			_open_shop_item(it))
	return pc

# [LOJA] Clique no slot → dialog com o card RICO do item (stats/lore/comparação) + preço + Comprar.
# Espelha Character._open_item_actions (dim + clique-fora fecha).
func _open_shop_item(it: Dictionary) -> void:
	var id := int(it.get("id", 0))
	var purchased := bool(it.get("purchased", false))
	var dim_rect := ColorRect.new()
	dim_rect.color = Color(0, 0, 0, 0.62)
	dim_rect.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	dim_rect.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(dim_rect)
	dim_rect.gui_input.connect(func(e: InputEvent) -> void:   # clique fora do card fecha
		if e is InputEventMouseButton and e.pressed:
			dim_rect.queue_free())
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim_rect.add_child(center)
	var col := VBoxContainer.new(); col.add_theme_constant_override("separation", 8)
	center.add_child(col)
	col.add_child(UiKit.item_tooltip_panel(it, {"equipped": false, "player_level": int(warrior.get("level", 0))}))
	# preço + Comprar (ou "Comprado")
	var price := int(it.get("price", 0))
	var total_bronze := int(warrior.get("gold", 0)) * 10000 + int(warrior.get("silver", 0)) * 100 + int(warrior.get("bronze", 0))
	var afford := warrior.is_empty() or total_bronze >= price
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 10); row.alignment = BoxContainer.ALIGNMENT_CENTER
	col.add_child(row)
	row.add_child(UiKit.coin_box(price, 18, UiKit.TEXT if (afford or purchased) else UiKit.ERR))
	if purchased:
		var done := Label.new(); done.text = Lang.t("✔ Comprado")
		done.add_theme_color_override("font_color", UiKit.OK)
		done.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(done)
	else:
		var buy := UiKit.small_btn(Lang.t("Comprar"), func() -> void:
			dim_rect.queue_free()
			await _buy(id))
		buy.disabled = not afford
		row.add_child(buy)

# Compra: 1 chamada. Em sucesso marco o item como comprado em memória + re-render;
# em falha não mexo no estado local e mostro o erro.
func _buy(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.shop_buy(id)
	if r.get("ok") and r.get("json") is Dictionary:
		var items: Array = data.get("items", []) if data.get("items") is Array else []
		for it in items:
			if it is Dictionary and int(it.get("id", -1)) == id:
				it["purchased"] = true
		# [AUDIT] A compra muda só (a) o item comprado → já marcado em memória acima, e (b) a carteira
		# (spendGold). Os outros slots da loja são determinísticos por rotação → não mudam. Então em vez
		# do _refresh (que re-puxa /api/shop inteiro) re-busco SÓ /api/warrior p/ a affordability/preços.
		var wr = await Api.get_warrior()
		if wr.get("ok") and wr.get("json") is Dictionary:
			warrior = wr["json"]
		_render()
		UiKit.flash(status, str(r["json"].get("message", Lang.t("Comprado!"))), 1)
	else:
		UiKit.show_error(status, r)
	busy = false
