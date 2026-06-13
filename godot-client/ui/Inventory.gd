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
	var equipped: Array = []
	var bag: Array = []
	for it in items:
		if it is Dictionary:
			(equipped if it.get("equipped", false) else bag).append(it)
	content.add_child(UiKit.section("Equipado (%d)" % equipped.size()))
	if equipped.is_empty():
		content.add_child(UiKit.dim("— nada equipado —"))
	for it in equipped:
		content.add_child(_item_row(it))
	content.add_child(UiKit.section("Mochila (%d)" % bag.size()))
	if bag.is_empty():
		content.add_child(UiKit.empty("Mochila vazia", "Vença missões no 🌍 Mundo para conseguir itens"))
	for it in bag:
		content.add_child(_item_row(it))

func _item_row(it: Dictionary) -> PanelContainer:
	var id := int(it.get("id", 0))
	var name_text := str(it.get("name", "?"))
	var sub_text := "%s · Nv %d · %s" % [str(it.get("typeDisplay", it.get("type", ""))), int(it.get("itemLevel", 1)), str(it.get("rarityName", ""))]
	var stats_text := _stats_line(it)
	var actions: Array = []
	if it.get("equipped", false):
		actions.append(["Desequipar", _unequip.bind(id)])
	else:
		actions.append(["Equipar", _equip.bind(id)])
		# bug fix: venda paga BRONZE, não ouro → ícone 🥉 (era 🥇)
		actions.append(["Vender (%d🥉)" % int(it.get("sellPrice", 0)), _ask_sell.bind(id, name_text, int(it.get("rarity", 1)))])
	return UiKit.item_row(it, name_text, sub_text, stats_text, actions)

# Itens raros (raridade ≥ 3) pedem confirmação antes de vender; o resto é 1-clique.
func _ask_sell(id: int, name_text: String, rarity: int) -> void:
	if rarity >= 3:
		UiKit.confirm(self, "Vender %s?" % name_text, "Vender", func() -> void: await _sell(id))
	else:
		await _sell(id)

# Ações: 1 chamada só (sem re-baixar a lista). Em sucesso atualizo `items` em memória e re-renderizo;
# em falha não mexo no estado local (nada mudou no servidor) e mostro o erro.
func _equip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.equip_item(id)
	if r.get("ok") and r.get("json") is Dictionary:
		var updated: Dictionary = r["json"]
		for it in items:   # auto-desequipa o item antigo do MESMO slot (tipo)
			if it is Dictionary and str(it.get("type")) == str(updated.get("type")) and int(it.get("id", -1)) != int(updated.get("id", -2)):
				it["equipped"] = false
		_replace_item(updated)
		_render()
	else:
		UiKit.show_error(status, r)
	busy = false

func _unequip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.unequip_item(id)
	if r.get("ok") and r.get("json") is Dictionary:
		_replace_item(r["json"])   # equipped=false agora
		_render()
	else:
		UiKit.show_error(status, r)
	busy = false

func _sell(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.sell_item(id)
	if r.get("ok") and r.get("json") is Dictionary:
		items = items.filter(func(it): return not (it is Dictionary) or int(it.get("id", -1)) != id)   # some da lista
		_render()
		UiKit.flash(status, str(r["json"].get("message", "Vendido!")), 1)
	else:
		UiKit.show_error(status, r)
	busy = false

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
