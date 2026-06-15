extends Control
# ── Tela LOJA ─────────────────────────────────────────────────────────────────────
# Lista GET /api/shop (mercador + itens em rotação de 6h) + /api/warrior (carteira/preço)
# e compra item único (POST /api/shop/buy/{id}). Item comprado fica marcado "✔ Comprado" e
# afunda pro fim da lista. Nome colorido pela raridade. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var data: Dictionary = {}        # cache do GET /api/shop (items + mercador + timer)
var warrior: Dictionary = {}     # /api/warrior (carteira + bronze p/ affordability)
var secs := 0                    # segundos até a próxima rotação (decai por _process)
var rarity_filter := 0           # filtro de raridade dos itens à venda (0=Todas, 1-5)

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🛒 Loja", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
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
	_timer_label.text = Lang.t("🛒 Próxima rotação em %dh %02dm %02ds") % [h, mm, ss]
	# P2: faltando menos de 10 min → cor de alerta.
	_timer_label.add_theme_color_override("font_color", UiKit.WARN if secs < 600 else UiKit.TEXT_DIM)

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	# ── Cabeçalho do mercador ──
	var name_lbl := Label.new()
	name_lbl.text = "🧙 %s" % str(data.get("merchantName", "Mercador"))
	name_lbl.add_theme_font_size_override("font_size", 22)
	name_lbl.add_theme_color_override("font_color", UiKit.GOLD)
	content.add_child(name_lbl)
	var quote := str(data.get("merchantQuote", ""))
	if quote != "":
		content.add_child(UiKit.dim("\"%s\"" % quote))
	_timer_label = Label.new()
	_timer_label.add_theme_font_size_override("font_size", 13)
	content.add_child(_timer_label)
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
	content.add_child(UiKit.rarity_filter(rarity_filter, _set_rarity))
	if items.is_empty():
		content.add_child(UiKit.empty("Sem itens nesta rotação", "Volte após a próxima rotação do mercador"))
	else:
		var shown: Array = sorted_items
		if rarity_filter > 0:
			shown = []
			for it in sorted_items:
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
	var purchased := bool(it.get("purchased", false))
	var rar := int(it.get("rarity", 1))
	var res := UiKit.card(UiKit.rarity_color(rar), not purchased)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	if rar >= 4 and not purchased:
		var sbpc: StyleBoxFlat = pc.get_theme_stylebox("panel")
		sbpc.set_border_width_all(2)
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 12)
	box.add_child(hb)
	# esquerda: nome + sub + stats + preço
	var left := VBoxContainer.new(); left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new(); nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", UiKit.rarity_color(rar))
	left.add_child(nm)
	left.add_child(UiKit.dim(Lang.t("%s · Nv %d · %s") % [Lang.t(str(it.get("typeDisplay", it.get("type", "")))), int(it.get("itemLevel", 1)), Lang.t(str(it.get("rarityName", "")))]))
	var stats := _stats_line(it)
	if stats != "":
		var sl := Label.new(); sl.text = stats; sl.add_theme_font_size_override("font_size", 12)
		sl.add_theme_color_override("font_color", Color(0.62, 0.75, 0.58))
		left.add_child(sl)
	# [COMPARA] vs item equipado no mesmo slot (▲Melhor/▼Pior/◆Lateral + deltas)
	var cmp := UiKit.compare_line(it)
	if cmp != null:
		left.add_child(cmp)
	# preço — P0: vermelho se não dá pra pagar. [MOEDA] preço é em BRONZE (base);
	# affordability compara o TOTAL (ouro*10000 + prata*100 + bronze), não o resto 0-99.
	var price := int(it.get("price", 0))
	var total_bronze := int(warrior.get("gold", 0)) * 10000 + int(warrior.get("silver", 0)) * 100 + int(warrior.get("bronze", 0))
	var afford := warrior.is_empty() or total_bronze >= price
	# [MOEDA] ícones pixel-art (não emoji); o número fica vermelho quando não dá pra pagar
	var price_box := UiKit.coin_box(price, 18, UiKit.TEXT if (afford or purchased) else UiKit.ERR)
	left.add_child(price_box)
	hb.add_child(left)
	# direita: ação comprar / comprado
	var right := VBoxContainer.new(); right.add_theme_constant_override("separation", 4)
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var id := int(it.get("id", 0))
	if purchased:
		var done := Label.new(); done.text = "✔ Comprado"
		done.add_theme_color_override("font_color", UiKit.OK)
		right.add_child(done)
	else:
		right.add_child(UiKit.small_btn("Comprar", _buy.bind(id)))
	hb.add_child(right)
	return pc

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
		# re-sincroniza a carteira p/ refletir o gasto na affordability/preços.
		await _refresh()
		UiKit.flash(status, str(r["json"].get("message", Lang.t("Comprado!"))), 1)
	else:
		UiKit.show_error(status, r)
	busy = false

func _stats_line(it: Dictionary) -> String:
	var parts: Array = []
	for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]:
		var v := int(it.get(pair[0], 0))
		if v != 0:
			parts.append("%s %+d" % [pair[1], v])
	return "   ".join(parts)
