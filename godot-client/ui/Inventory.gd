extends Control
# ── Tela INVENTÁRIO / EQUIPAR ─────────────────────────────────────────────────────
# Lista GET /api/inventory (equipados + mochila), equipa/desequipa/vende. Nome colorido
# pela raridade. Volta pro Personagem (sinal go_back). Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var items: Array = []      # cache local → ações atualizam em memória (sem re-baixar a lista)
var warrior: Dictionary = {}   # /api/warrior (carteira do header)
var rarity_filter := 0     # filtro de raridade da mochila (0=Todas, 1-5)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🎒 Inventário", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_DEFAULT)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/inventory", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Array):
		UiKit.show_error(status, r)
		return
	items = r["json"]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	UiKit.set_equipped(items)   # mantém a comparação "vs equipado" fresca após equipar/desequipar
	var equipped: Array = []
	var bag: Array = []
	for it in items:
		if it is Dictionary:
			(equipped if it.get("equipped", false) else bag).append(it)
	content.add_child(UiKit.section(Lang.t("Equipado (%d)") % equipped.size()))
	if equipped.is_empty():
		content.add_child(UiKit.dim("— nada equipado —"))
	else:
		content.add_child(UiKit.grid(self, equipped, _item_row))
	content.add_child(UiKit.section(Lang.t("Mochila (%d)") % bag.size()))
	content.add_child(UiKit.rarity_filter(rarity_filter, _set_rarity))
	if bag.is_empty():
		content.add_child(UiKit.empty("Mochila vazia", "Vença missões no 🌍 Mundo para conseguir itens"))
	else:
		var shown: Array = bag
		if rarity_filter > 0:
			shown = []
			for it in bag:
				if it is Dictionary and int(it.get("rarity", 1)) == rarity_filter:
					shown.append(it)
		if shown.is_empty():
			content.add_child(UiKit.dim("— nada nessa raridade —"))
		else:
			content.add_child(UiKit.grid(self, shown, _item_row))

func _set_rarity(r: int) -> void:
	rarity_filter = r
	_render()

func _item_row(it: Dictionary) -> PanelContainer:
	var id := int(it.get("id", 0))
	var name_text := str(it.get("name", "?"))
	var sub_text := Lang.t("%s · Nv %d · %s") % [Lang.t(str(it.get("typeDisplay", it.get("type", "")))), int(it.get("itemLevel", 1)), Lang.t(str(it.get("rarityName", "")))]
	var stats_text := _stats_line(it)
	var actions: Array = []
	if it.get("equipped", false):
		actions.append(["Desequipar", _unequip.bind(id)])
	else:
		actions.append(["Equipar", _equip.bind(id)])
		if bool(it.get("pvpLocked", false)):   # travado no PvP → não dá pra vender enquanto exposto (backend recusa)
			actions.append(["🔒 PvP", func() -> void: UiKit.flash(status, Lang.t("Item travado no PvP — não dá pra vender enquanto exposto."), 2)])
		else:
			# [MOEDA] venda paga BRONZE (base) → formata em ouro/prata/bronze
			actions.append([Lang.t("Vender (%s)") % UiKit.coin_str(int(it.get("sellPrice", 0))), _ask_sell.bind(id, name_text, int(it.get("rarity", 1)))])
	return UiKit.item_row(it, name_text, sub_text, stats_text, actions)

# Itens raros (raridade ≥ 3) pedem confirmação antes de vender; o resto é 1-clique.
func _ask_sell(id: int, name_text: String, rarity: int) -> void:
	if rarity >= 3:
		UiKit.confirm(self, Lang.t("Vender %s?") % name_text, Lang.t("Vender"), func() -> void: await _sell(id))
	else:
		await _sell(id)

# Ações: 1 chamada só (sem re-baixar a lista). Em sucesso atualizo `items` em memória e re-renderizo;
# em falha não mexo no estado local (nada mudou no servidor) e mostro o erro.
func _equip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.equip_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var updated: Dictionary = r["json"]
		for it in items:   # auto-desequipa o item antigo do MESMO slot (tipo)
			if it is Dictionary and str(it.get("type")) == str(updated.get("type")) and int(it.get("id", -1)) != int(updated.get("id", -2)):
				it["equipped"] = false
		_replace_item(updated)
		_render()
		if UiKit.equip_changed_sink.is_valid():
			UiKit.equip_changed_sink.call(items)   # re-veste o busto 3D (usa o inventário local, sem fetch)
	else:
		UiKit.show_error(status, r)
		await _refresh()   # resync: cache local pode estar velho (item mudou no servidor)

func _unequip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.unequip_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		_replace_item(r["json"])   # equipped=false agora
		_render()
		if UiKit.equip_changed_sink.is_valid():
			UiKit.equip_changed_sink.call(items)   # re-veste o busto 3D (sem fetch)
	else:
		UiKit.show_error(status, r)
		await _refresh()

func _sell(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.sell_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		items = items.filter(func(it): return not (it is Dictionary) or int(it.get("id", -1)) != id)   # some da lista
		_render()
		UiKit.flash(status, str(r["json"].get("message", Lang.t("Vendido!"))), 1)
	else:
		UiKit.show_error(status, r)
		await _refresh()   # resync: se o item virou listado/sumiu no servidor, a lista se corrige (e o item some)

func _replace_item(updated: Dictionary) -> void:
	var uid := int(updated.get("id", -1))
	for i in items.size():
		if items[i] is Dictionary and int(items[i].get("id", -2)) == uid:
			items[i] = updated
			return

func _stats_line(it: Dictionary) -> String:
	var parts: Array = []
	for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]:
		var v := int(it.get(pair[0], 0))
		if v != 0:
			parts.append("%s %+d" % [pair[1], v])
	return "   ".join(parts)
