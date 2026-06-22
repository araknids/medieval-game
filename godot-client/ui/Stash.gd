extends Control
# ── Tela BAÚ (Stash) ──────────────────────────────────────────────────────────────
# Espelha o openStash() do app.js (~1754). Dois lados:
#   • Mochila (bag): itens não-equipados (GET /api/inventory) + recursos (GET /api/gathering/resources)
#   • Baú (stash):  itens + recursos guardados (GET /api/stash → items/resources/used/max/fee/bagUsed/bagMax)
# Ações: depositar/sacar item (POST /api/stash/{deposit|withdraw}/item/{id}) e recurso
#         (POST /api/stash/{deposit|withdraw}/resource/{type} {quantity}). Cada move cobra `fee` bronze.
# Recursos: sem prompt nativo no Godot → move a quantidade TODA da pilha. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

const Icons := preload("res://ui/Icons.gd")   # [INV_COMPACTO] ícone do item + GIF de recurso no hover

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var stash: Dictionary = {}      # GET /api/stash
var warrior: Dictionary = {}    # /api/warrior (carteira do header)
var bag_items: Array = []       # itens da mochila (não equipados)
var bag_res: Array = []         # recursos da mochila (quantity > 0)
var rarity_filter := 0          # filtro de raridade (só ITENS; recursos não têm raridade)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "📦 Baú", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	# stash + inventário + recursos + warrior em PARALELO (independentes)
	var batch = await Api.batch_get(["/api/stash", "/api/inventory", "/api/gathering/resources", "/api/warrior"])
	var rs = batch[0]
	if not (rs.get("ok") and rs.get("json") is Dictionary):
		UiKit.show_error(status, rs)
		return
	stash = rs["json"]
	var ri = batch[1]
	bag_items = []
	if ri.get("ok") and ri.get("json") is Array:
		for it in ri["json"]:
			if it is Dictionary and not bool(it.get("equipped", false)):
				bag_items.append(it)
	var rr = batch[2]
	bag_res = []
	if rr.get("ok") and rr.get("json") is Array:
		for r in rr["json"]:
			if r is Dictionary and int(r.get("quantity", 0)) > 0:
				bag_res.append(r)
	var wr = batch[3]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	var fee := int(stash.get("fee", 0))
	var fee_lbl := Label.new()
	fee_lbl.text = Lang.t("Taxa: %d bronze por movimento (depositar/sacar)") % fee
	fee_lbl.add_theme_font_size_override("font_size", 12)
	fee_lbl.add_theme_color_override("font_color", UiKit.WARN)
	content.add_child(fee_lbl)
	# filtro de raridade — aplica só aos ITENS (mochila + baú); recursos nunca filtram.
	content.add_child(UiKit.rarity_filter(rarity_filter, _set_rarity))
	# ── Mochila (bag) ──  used/max vem de bagUsed/bagMax
	var bag_used := int(stash.get("bagUsed", 0))
	var bag_max := int(stash.get("bagMax", 0))
	content.add_child(UiKit.section(Lang.t("Mochila (%d/%d)") % [bag_used, bag_max]))
	if bag_items.is_empty() and bag_res.is_empty():
		content.add_child(UiKit.empty("Mochila vazia", "Colete recursos e equipamentos para guardar aqui"))
	var bag_items_shown := _filter_rarity(bag_items)
	if not bag_items_shown.is_empty():
		content.add_child(UiKit.grid(self, bag_items_shown, func(it): return _item_row(it, true) if it is Dictionary else null))
	elif not bag_items.is_empty():
		content.add_child(UiKit.dim("— nenhum item nessa raridade —"))
	if not bag_res.is_empty():
		content.add_child(UiKit.grid(self, bag_res, func(r): return _res_row(r, true) if r is Dictionary else null, true))
	# ── Baú (stash) ──  used/max(-1=∞)
	var st_items: Array = stash.get("items", []) if stash.get("items") is Array else []
	var st_res: Array = stash.get("resources", []) if stash.get("resources") is Array else []
	var used := int(stash.get("used", 0))
	var smax := int(stash.get("max", -1))
	var cap := "∞" if smax < 0 else str(smax)
	content.add_child(UiKit.section(Lang.t("Baú (%d/%s)") % [used, cap]))
	if st_items.is_empty() and st_res.is_empty():
		content.add_child(UiKit.empty("Baú vazio", "Deposite itens da mochila para protegê-los"))
	var st_items_shown := _filter_rarity(st_items)
	if not st_items_shown.is_empty():
		content.add_child(UiKit.grid(self, st_items_shown, func(it): return _item_row(it, false) if it is Dictionary else null))
	elif not st_items.is_empty():
		content.add_child(UiKit.dim("— nenhum item nessa raridade —"))
	if not st_res.is_empty():
		content.add_child(UiKit.grid(self, st_res, func(r): return _res_row(r, false) if r is Dictionary else null, true))

func _set_rarity(r: int) -> void:
	rarity_filter = r
	_render()

# Filtra itens pela raridade ativa (0=Todas → devolve a lista intacta).
func _filter_rarity(arr: Array) -> Array:
	if rarity_filter <= 0:
		return arr
	var out: Array = []
	for it in arr:
		if it is Dictionary and int(it.get("rarity", 1)) == rarity_filter:
			out.append(it)
	return out

# [INV_COMPACTO] Slot compacto (igual à Mochila da Ficha): ícone + nome + Nv (vermelho se exige acima)
# + botão de mover. Detalhe completo no HOVER (tooltip rico). in_bag=true → "→ Baú", false → "→ Mochila".
func _item_row(it: Dictionary, in_bag: bool) -> PanelContainer:
	var rar := int(it.get("rarity", 1))
	var card := ItemTooltipCard.new()
	card.item = it
	card.player_level = int(warrior.get("level", 0))
	card.tooltip_text = " "                  # != "" senão o _make_custom_tooltip nem dispara
	var res := UiKit.card_styled(card, UiKit.rarity_color(rar))
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(7)
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
	box.add_child(row)
	var ic := UiKit.item_icon_for(it, 28)
	if ic: row.add_child(ic)
	var nm := Label.new()
	nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 13)
	nm.add_theme_color_override("font_color", UiKit.rarity_color(rar))
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	nm.clip_text = true
	nm.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	row.add_child(nm)
	var ilvl := int(it.get("itemLevel", 1))
	var plvl := int(warrior.get("level", 0))
	var lv := Label.new()
	lv.text = Lang.t("Nv %d") % ilvl
	lv.add_theme_font_size_override("font_size", 11)
	lv.add_theme_color_override("font_color", UiKit.ERR if (plvl > 0 and ilvl > plvl) else UiKit.TEXT_DIM)
	lv.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(lv)
	var id := int(it.get("id", 0))
	if in_bag:
		row.add_child(UiKit.small_btn("→ Baú", _deposit_item.bind(id)))
	else:
		row.add_child(UiKit.small_btn("→ Mochila", _withdraw_item.bind(id)))
	# filhos PASS → o hover sobe pro card (tooltip rico); o botão de mover fica STOP (clica)
	for n in [box, row, nm, ic, lv]:
		if n != null and n is Control:
			(n as Control).mouse_filter = Control.MOUSE_FILTER_PASS
	return pc

func _res_row(r: Dictionary, in_bag: bool) -> PanelContainer:
	var rtype := str(r.get("type", ""))
	var pc := PanelContainer.new()
	pc.tooltip_text = str(r.get("displayName", rtype))
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.08, 0.07, 0.09, 0.95)
	sb.set_border_width_all(1); sb.border_color = UiKit.BRONZE
	sb.set_corner_radius_all(4); sb.set_content_margin_all(6)
	pc.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 6)
	hb.mouse_filter = Control.MOUSE_FILTER_IGNORE   # hover cai no pc (tooltip + anima a GIF); botão ainda clica
	pc.add_child(hb)
	var qty := int(r.get("quantity", 0))
	# [RECURSOS_GIF] ícone próprio que anima no hover (res_<tipo>); senão cai no genérico `package`
	var key := "res_" + rtype.to_lower()
	var icon := _res_icon(key)
	if icon != null:
		Icons.anim_rect(pc, icon, key)
		hb.add_child(icon)
	var name_lbl := Label.new()
	name_lbl.text = "%s ×%d" % [str(r.get("displayName", rtype)), qty]
	name_lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	name_lbl.add_theme_font_size_override("font_size", 13)
	name_lbl.add_theme_color_override("font_color", UiKit.TEXT)
	name_lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	hb.add_child(name_lbl)
	if in_bag:
		hb.add_child(UiKit.small_btn("→ Baú", _deposit_resource.bind(rtype, qty)))
	else:
		hb.add_child(UiKit.small_btn("→ Mochila", _withdraw_resource.bind(rtype, qty)))
	return pc

# [RECURSOS_GIF] TextureRect do recurso: ícone próprio (res_<tipo>) se importado, senão o genérico de
# pacote. mouse IGNORE → o hover (e a anim) ficam no chip-pai. null se nem o pacote existir.
func _res_icon(key: String) -> TextureRect:
	var tex := Icons.tex(key)
	if tex == null:
		tex = Icons.tex("package")
	if tex == null:
		return null
	var tr := TextureRect.new()
	tr.texture = tex
	tr.custom_minimum_size = Vector2(24, 24)
	tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	tr.mouse_filter = Control.MOUSE_FILTER_IGNORE
	return tr

# ── Ações async (cada move re-baixa o estado: a taxa muda moeda e o lado afetado) ──
# [AUDIT] mover ITEM mexe em InventoryItem + bronze (StashService.deposit/withdrawItem) → NÃO toca
# recurso; mover RECURSO mexe em ResourceInventory + bronze → NÃO toca o inventário de itens. Os dois
# mudam o stash + a carteira. Então cada handler só re-baixa o lado que pode mudar (dropa o irrelevante).
func _deposit_item(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_deposit_item(id)
	await _after(r, true)
	busy = false

func _withdraw_item(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_withdraw_item(id)
	await _after(r, true)
	busy = false

func _deposit_resource(rtype: String, qty: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_deposit_resource(rtype, qty)
	await _after(r, false)
	busy = false

func _withdraw_resource(rtype: String, qty: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_withdraw_resource(rtype, qty)
	await _after(r, false)
	busy = false

# is_item=true → re-baixa stash + inventário + warrior (move de item); false → stash + recursos + warrior.
# Erro → só mostra o erro (igual antes, sem re-baixar). Sucesso → batch enxuto preenchendo os MESMOS membros do _refresh.
func _after(r, is_item: bool) -> void:
	if not (r is Dictionary and r.get("ok")):
		UiKit.show_error(status, r)
		return
	if is_item:
		await _refresh_item_move()
	else:
		await _refresh_resource_move()
	if r.get("json") is Dictionary:
		UiKit.flash(status, str(r["json"].get("message", "")), 1)

# Move de ITEM: stash (items/used/bag*) + inventário (bag de itens) + warrior (carteira). Recursos inalterados.
func _refresh_item_move() -> void:
	var batch = await Api.batch_get(["/api/stash", "/api/inventory", "/api/warrior"])
	var rs = batch[0]
	if rs.get("ok") and rs.get("json") is Dictionary:
		stash = rs["json"]
	var ri = batch[1]
	if ri.get("ok") and ri.get("json") is Array:
		bag_items = []
		for it in ri["json"]:
			if it is Dictionary and not bool(it.get("equipped", false)):
				bag_items.append(it)
	var wr = batch[2]
	if wr.get("ok") and wr.get("json") is Dictionary:
		warrior = wr["json"]
	_render()

# Move de RECURSO: stash (resources/used/bag*) + recursos (bag de recursos) + warrior (carteira). Itens inalterados.
func _refresh_resource_move() -> void:
	var batch = await Api.batch_get(["/api/stash", "/api/gathering/resources", "/api/warrior"])
	var rs = batch[0]
	if rs.get("ok") and rs.get("json") is Dictionary:
		stash = rs["json"]
	var rr = batch[1]
	if rr.get("ok") and rr.get("json") is Array:
		bag_res = []
		for r in rr["json"]:
			if r is Dictionary and int(r.get("quantity", 0)) > 0:
				bag_res.append(r)
	var wr = batch[2]
	if wr.get("ok") and wr.get("json") is Dictionary:
		warrior = wr["json"]
	_render()
