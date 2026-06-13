extends Control
# ── Tela CASA DE LEILÃO (Auction House) — preço fixo / buyout. [LEILAO][MIGRACAO_GODOT] ─────
# 3 seções: 🛒 Browse (comprar), 📋 Minhas listagens (cancelar), ➕ Listar item (da mochila).
# Endpoints: GET /api/auction, GET /api/auction/mine, GET /api/inventory, GET /api/warrior,
#            POST /api/auction/buy/{id}, POST /api/auction/cancel/{id}, POST /api/auction/list {itemId, price}.
# Taxa: 5% adiantada (queima) + 15% na venda → vendedor recebe ~80%. Listagens duram 2 dias, máx 10.
# Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var listings: Array = []   # browse (de todos)
var mine: Array = []       # minhas listagens ativas
var bag: Array = []        # itens da mochila p/ listar (não-equipados)
var warrior: Dictionary = {}  # /api/warrior (carteira do header)
var price_inputs := {}     # itemId(int) → LineEdit (preço digitado na seção de listagem)
var rarity_filter := 0     # filtro de raridade da seção Comprar (0=Todas, 1-5)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "💰 Leilão", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	# browse + minhas listagens + inventário + warrior em PARALELO (independentes)
	var rs = await Api.batch_get(["/api/auction", "/api/auction/mine", "/api/inventory", "/api/warrior"])
	var rl = rs[0]
	var rm = rs[1]
	var ri = rs[2]
	if not (rl.get("ok") and rl.get("json") is Array):
		UiKit.show_error(status, rl)
		return
	listings = rl["json"]
	mine = rm["json"] if (rm.get("ok") and rm.get("json") is Array) else []
	bag = []
	if ri.get("ok") and ri.get("json") is Array:
		for it in ri["json"]:
			if it is Dictionary and not bool(it.get("equipped", false)):
				bag.append(it)
	var wr = rs[3]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	price_inputs.clear()
	# nota da taxa
	content.add_child(UiKit.dim("Mercado de preço fixo. Taxa: 5% adiantada (queima) + 15% na venda → você recebe 80%. Duram 2 dias, máx 10."))
	# ── 🛒 Browse ──
	content.add_child(UiKit.section("🛒 Comprar (%d)" % listings.size()))
	content.add_child(UiKit.rarity_filter(rarity_filter, _set_rarity))
	if listings.is_empty():
		content.add_child(UiKit.empty("Nenhum item à venda agora", "Volte mais tarde ou liste algo abaixo"))
	else:
		var shown: Array = listings
		if rarity_filter > 0:
			shown = []
			for a in listings:
				if a is Dictionary and int(a.get("rarity", 1)) == rarity_filter:
					shown.append(a)
		if shown.is_empty():
			content.add_child(UiKit.dim("— nada nessa raridade —"))
		else:
			content.add_child(UiKit.grid(self, shown, func(a): return _listing_row(a, false) if a is Dictionary else null))
	# ── 📋 Minhas listagens ──
	content.add_child(UiKit.section("📋 Minhas listagens (%d/10)" % mine.size()))
	if mine.is_empty():
		content.add_child(UiKit.dim("— nenhuma listagem ativa —"))
	else:
		content.add_child(UiKit.grid(self, mine, func(a): return _listing_row(a, true) if a is Dictionary else null))
	# ── ➕ Listar item ──
	content.add_child(UiKit.section("➕ Listar um item (%d)" % bag.size()))
	if bag.is_empty():
		content.add_child(UiKit.dim("— nada na mochila p/ listar —"))
	else:
		content.add_child(UiKit.grid(self, bag, func(it): return _picker_row(it) if it is Dictionary else null))

func _set_rarity(r: int) -> void:
	rarity_filter = r
	_render()

# Card de uma listagem (browse ou minha). is_mine_section → botão Cancelar; senão Comprar.
func _listing_row(a: Dictionary, is_mine_section: bool) -> PanelContainer:
	var rar := int(a.get("rarity", 1))
	var col := UiKit.rarity_color(rar)
	var res := UiKit.card(col)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	if rar >= 4:
		var sbpc: StyleBoxFlat = pc.get_theme_stylebox("panel")
		sbpc.set_border_width_all(2)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 12)
	box.add_child(hb)
	# esquerda: nome + sub + stats + vendedor
	var left := VBoxContainer.new(); left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	var name_txt := str(a.get("name", "?"))
	if int(a.get("sockets", 0)) > 0:
		name_txt += "  ◇%d" % int(a.get("sockets", 0))
	nm.text = name_txt; nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", col)
	left.add_child(nm)
	left.add_child(UiKit.dim("%s · 🔧%d%% · ⏳ %s" % [str(a.get("typeDisplay", a.get("type", ""))), int(a.get("durability", 100)), _time_left(int(a.get("secondsLeft", 0)))]))
	var stats := _stats_line(a)
	if stats != "":
		var sl := Label.new(); sl.text = stats; sl.add_theme_font_size_override("font_size", 12)
		sl.add_theme_color_override("font_color", Color(0.62, 0.75, 0.58))
		left.add_child(sl)
	var affs = a.get("affixes", [])
	if affs is Array and not affs.is_empty():
		var al := Label.new(); al.text = " · ".join(affs); al.add_theme_font_size_override("font_size", 11)
		al.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
		left.add_child(al)
	var seller_txt := "Vendedor: %s" % str(a.get("sellerName", "?"))
	if is_mine_section:
		seller_txt += " · você recebe %d na venda" % int(a.get("sellerPayout", 0))
	left.add_child(UiKit.dim(seller_txt))
	hb.add_child(left)
	# direita: preço + ação
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 6)
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var price := Label.new(); price.text = "💰 %d" % int(a.get("price", 0))
	price.add_theme_color_override("font_color", UiKit.GOLD)
	price.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	right.add_child(price)
	var lid := int(a.get("listingId", 0))
	if is_mine_section:
		right.add_child(UiKit.small_btn("Cancelar", _confirm_cancel.bind(lid, str(a.get("name", "esta listagem"))), true))
	else:
		if bool(a.get("isMine", false)):
			var mine_btn := UiKit.small_btn("(sua)", Callable())
			mine_btn.disabled = true
			right.add_child(mine_btn)
		else:
			right.add_child(UiKit.small_btn("Comprar", _buy.bind(lid)))
	hb.add_child(right)
	return pc

# Card da seção "Listar": item da mochila + campo de preço + botão Listar.
func _picker_row(it: Dictionary) -> PanelContainer:
	var col := UiKit.rarity_color(int(it.get("rarity", 1)))
	var res := UiKit.card(col)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 12)
	box.add_child(hb)
	var left := VBoxContainer.new(); left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new(); nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", col)
	left.add_child(nm)
	left.add_child(UiKit.dim("%s · Nv %d · %s" % [str(it.get("typeDisplay", it.get("type", ""))), int(it.get("itemLevel", 1)), str(it.get("rarityName", ""))]))
	var stats := _stats_line(it)
	if stats != "":
		var sl := Label.new(); sl.text = stats; sl.add_theme_font_size_override("font_size", 12)
		sl.add_theme_color_override("font_color", Color(0.62, 0.75, 0.58))
		left.add_child(sl)
	hb.add_child(left)
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 6)
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var itid := int(it.get("id", 0))
	var price := UiKit.input("💰 preço")
	price.custom_minimum_size = Vector2(120, 0)
	price_inputs[itid] = price
	right.add_child(price)
	right.add_child(UiKit.small_btn("Listar", _list.bind(itid)))
	hb.add_child(right)
	return pc

# ── Ações (1 chamada → re-sincroniza com _refresh) ─────────────────────────────────
func _buy(listing_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.auction_buy(listing_id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		await _refresh()
		UiKit.flash(status, str(r["json"].get("message", "Comprado!")), 1)
	else:
		UiKit.show_error(status, r)

# Cancelar = irreversível (queima a taxa de 5%) → confirma antes.
func _confirm_cancel(listing_id: int, item_name: String) -> void:
	UiKit.confirm(self, "Cancelar a listagem de \"%s\"? O item volta pra mochila (a taxa de 5% não é devolvida)." % item_name, "Cancelar listagem", func() -> void: await _cancel(listing_id), true)

func _cancel(listing_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.auction_cancel(listing_id)
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, "Listagem cancelada — item de volta na mochila.", 1)
	else:
		UiKit.show_error(status, r)

func _list(item_id: int) -> void:
	if busy: return
	var le: LineEdit = price_inputs.get(item_id)
	var price := int(le.text) if le != null else 0
	if price < 1:
		UiKit.flash(status, "Informe um preço válido.", 2)
		return
	busy = true
	var r = await Api.auction_list(item_id, price)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		await _refresh()
		UiKit.flash(status, "Listado! (taxa de 5% cobrada)", 1)
	else:
		UiKit.show_error(status, r)

# ── helpers ────────────────────────────────────────────────────────────────────────
func _stats_line(it: Dictionary) -> String:
	var parts: Array = []
	for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]:
		var v := int(it.get(pair[0], 0))
		if v != 0:
			parts.append("%s %+d" % [pair[1], v])
	return "   ".join(parts)

func _time_left(s: int) -> String:
	var d := s / 86400
	var h := (s % 86400) / 3600
	if d > 0:
		return "%dd %dh" % [d, h]
	var mn := (s % 3600) / 60
	return "%dh %dm" % [h, mn]
