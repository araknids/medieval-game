extends Control
# ── Tela BAÚ (Stash) ──────────────────────────────────────────────────────────────
# Espelha o openStash() do app.js (~1754). Dois lados:
#   • Mochila (bag): itens não-equipados (GET /api/inventory) + recursos (GET /api/gathering/resources)
#   • Baú (stash):  itens + recursos guardados (GET /api/stash → items/resources/used/max/fee/bagUsed/bagMax)
# Ações: depositar/sacar item (POST /api/stash/{deposit|withdraw}/item/{id}) e recurso
#         (POST /api/stash/{deposit|withdraw}/resource/{type} {quantity}). Cada move cobra `fee` bronze.
# Recursos: sem prompt nativo no Godot → move a quantidade TODA da pilha. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

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

# in_bag=true → botão "→ Baú" (depositar); false → "→ Mochila" (sacar)
func _item_row(it: Dictionary, in_bag: bool) -> PanelContainer:
	var name_text := str(it.get("name", "?"))
	var sub := Lang.t("Nv %d") % int(it.get("itemLevel", 1))
	var stats := _stats_line(it)
	var id := int(it.get("id", 0))
	var action: Array
	if in_bag:
		action = [["→ Baú", _deposit_item.bind(id)]]
	else:
		action = [["→ Mochila", _withdraw_item.bind(id)]]
	return UiKit.item_row(it, name_text, sub, stats, action, int(warrior.get("level", 0)))   # [REQ_LEVEL] Nv vermelho se exige nível acima

func _res_row(r: Dictionary, in_bag: bool) -> PanelContainer:
	var res := UiKit.card(UiKit.BRONZE)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 10)
	box.add_child(hb)
	var qty := int(r.get("quantity", 0))
	var name_lbl := Label.new()
	name_lbl.text = "📦 %s ×%d" % [str(r.get("displayName", r.get("type", "?"))), qty]
	name_lbl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	name_lbl.add_theme_font_size_override("font_size", 15)
	name_lbl.add_theme_color_override("font_color", UiKit.TEXT)
	name_lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	hb.add_child(name_lbl)
	var rtype := str(r.get("type", ""))
	if in_bag:
		hb.add_child(UiKit.small_btn("→ Baú", _deposit_resource.bind(rtype, qty)))
	else:
		hb.add_child(UiKit.small_btn("→ Mochila", _withdraw_resource.bind(rtype, qty)))
	return pc

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

func _stats_line(it: Dictionary) -> String:
	var parts: Array = []
	for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]:
		var v := int(it.get(pair[0], 0))
		if v != 0:
			parts.append("%s %+d" % [pair[1], v])
	return "  ".join(parts)
