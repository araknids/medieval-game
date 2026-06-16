extends Control
# ── Tela FORJA (Smithing) ─────────────────────────────────────────────────────────
# Espelha renderSmithing() do app.js: nível de Forja, resumo de materiais,
# refino (minério→barra), craft de equipamento (com % de sucesso), criar joias
# (3 fragmentos→1 joia) e manutenção (reparar/reforjar itens). Padrão visual: UiKit [PADRAO_UI_GODOT].
# Endpoints: GET /api/smithing/recipes, GET /api/gathering/resources, GET /api/inventory,
# GET /api/warrior, POST /api/smithing/{refine|craft|gem|repair/{id}|reforge/{id}}. [MIGRACAO_GODOT]

signal go_back

var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false

var recipes: Dictionary = {}      # {refine:[], craft:[], gems:[]}
var resources: Array = []         # GET /api/gathering/resources
var inventory: Array = []         # GET /api/inventory
var warrior: Dictionary = {}      # /api/warrior (carteira do header)
var refine_qty: Dictionary = {}   # ore name → quantidade escolhida (SpinBox)
var craft_filter := 0             # filtro de raridade da seção Craftar (0=todas, 1-5)

const RARITY_NAMES := ["Comum", "Incomum", "Raro", "Épico", "Lendário"]

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🔨 Forja", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	# recipes + resources + inventory + warrior em PARALELO (independentes)
	var rs = await Api.batch_get(["/api/smithing/recipes", "/api/gathering/resources", "/api/inventory", "/api/warrior"])
	var rr = rs[0]
	if not (rr.get("ok") and rr.get("json") is Dictionary):
		UiKit.show_error(status, rr)
		return
	recipes = rr["json"]
	var res = rs[1]
	resources = res["json"] if (res.get("ok") and res.get("json") is Array) else []
	var inv = rs[2]
	inventory = inv["json"] if (inv.get("ok") and inv.get("json") is Array) else []
	var wr = rs[3]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	# ── Seus materiais ──
	content.add_child(UiKit.section("📦 Seus materiais"))
	var mats := _materials_text()
	if mats == "":
		content.add_child(UiKit.dim("— sem materiais (minere/colete pra forjar) —"))
	else:
		content.add_child(UiKit.body(mats))
	# ── Refinar ── (cards em grid p/ encurtar a tela)
	content.add_child(UiKit.section("Refinar Minérios → Barras"))
	var refine: Array = recipes.get("refine", [])
	if refine.is_empty():
		content.add_child(UiKit.dim("— sem receitas —"))
	else:
		_grid_section(refine, _refine_card)
	# ── Craftar equipamento (com filtro de raridade) ──
	content.add_child(UiKit.section("Craftar Equipamento"))
	var craft: Array = recipes.get("craft", [])
	if craft.is_empty():
		content.add_child(UiKit.dim("— sem receitas —"))
	else:
		content.add_child(_rarity_filter_row())
		var filtered: Array = craft
		if craft_filter > 0:
			filtered = []
			for r in craft:
				if r is Dictionary and int(r.get("rarity", 1)) == craft_filter:
					filtered.append(r)
		if filtered.is_empty():
			content.add_child(UiKit.dim(Lang.t("— nenhuma receita de %s —") % Lang.t(_rarity_name(craft_filter))))
		else:
			_grid_section(filtered, _craft_card)
	# ── Joias ── (cards compactos → mais colunas)
	content.add_child(UiKit.section("Criar Joias"))
	var gems: Array = recipes.get("gems", [])
	if gems.is_empty():
		content.add_child(UiKit.dim("— sem fragmentos —"))
	else:
		_grid_section(gems, _gem_card, true)
	# ── Manutenção ──
	content.add_child(UiKit.section("🔧 Manutenção (Reparar / Reforjar)"))
	if inventory.is_empty():
		content.add_child(UiKit.empty("Sem itens", "Equipamentos da mochila aparecem aqui p/ reparo/reforja"))
	else:
		_grid_section(inventory, _maint_card)

# Monta o grid de cards via UiKit.grid (responsivo — colunas pela largura real, não da janela).
# builder = func(Dictionary) -> Control; filtra não-dicionários antes (o builder assume Dictionary).
func _grid_section(items: Array, builder: Callable, compact := false) -> void:
	var rows: Array = []
	for it in items:
		if it is Dictionary:
			rows.append(it)
	content.add_child(UiKit.grid(self, rows, builder, compact))

# ── Filtro de raridade (seção Craftar) ──────────────────────────────────────────────
# Linha de chips (HFlow → quebra em telas estreitas): Todas + as 5 raridades. O ativo fica
# aceso, os outros apagados. Clicar re-renderiza com os dados em cache (sem chamada de rede).
func _rarity_filter_row() -> Control:
	var row := HFlowContainer.new()
	row.add_theme_constant_override("h_separation", 6)
	row.add_theme_constant_override("v_separation", 6)
	row.add_child(_rarity_chip("Todas", 0))
	for rar in range(1, 6):
		row.add_child(_rarity_chip(_rarity_name(rar), rar))
	return row

func _rarity_chip(label: String, rar: int) -> Button:
	var b := UiKit.small_btn(label, _set_filter.bind(rar))
	b.custom_minimum_size = Vector2(0, 32)
	b.add_theme_font_size_override("font_size", 12)
	b.add_theme_color_override("font_color", UiKit.GOLD if rar == 0 else UiKit.rarity_color(rar))
	if craft_filter != rar:
		b.modulate = Color(1, 1, 1, 0.5)   # inativo = apagado
	return b

func _rarity_name(rar: int) -> String:
	return RARITY_NAMES[clampi(rar - 1, 0, 4)]

func _set_filter(rar: int) -> void:
	craft_filter = rar
	_render()

# ── Cards ─────────────────────────────────────────────────────────────────────────
func _refine_card(r: Dictionary) -> PanelContainer:
	var ore := str(r.get("ore", ""))
	var ore_qty := maxi(1, int(r.get("oreQty", 1)))
	var have := _resource_qty(ore)                       # quanto minério o jogador tem
	var level_ok := bool(r.get("canCraft", false))       # canCraft = só o nível da Forja
	var enough := have >= ore_qty
	var can := level_ok and enough
	var res := UiKit.card(UiKit.BRONZE, can)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	# [MOEDA] custo de bronze em ícone pixel-art no meio da receita
	var rcp := HBoxContainer.new(); rcp.add_theme_constant_override("separation", 4)
	var rcp_a := Label.new(); rcp_a.text = "%s ×%d +" % [str(r.get("oreName", ore)), ore_qty]
	rcp_a.add_theme_font_size_override("font_size", 14); rcp_a.add_theme_color_override("font_color", UiKit.TEXT)
	rcp_a.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rcp.add_child(rcp_a)
	rcp.add_child(UiKit.coin_box(int(r.get("bronzeCost", 0)), 16))
	var rcp_b := Label.new(); rcp_b.text = "→ %s" % str(r.get("barName", ""))
	rcp_b.add_theme_font_size_override("font_size", 14); rcp_b.add_theme_color_override("font_color", UiKit.TEXT)
	rcp_b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rcp.add_child(rcp_b)
	vb.add_child(rcp)
	# tem/precisa colorido + requisito de nível (labels SEM autowrap → não quebram vertical no grid)
	var info := HBoxContainer.new(); info.add_theme_constant_override("separation", 12)
	var hv := Label.new()
	hv.text = Lang.t("Você tem: %d") % have
	hv.add_theme_font_size_override("font_size", 12)
	hv.add_theme_color_override("font_color", UiKit.OK if enough else UiKit.ERR)
	info.add_child(hv)
	var lv := Label.new()
	lv.text = Lang.t("Forja Lv.%d %s") % [int(r.get("levelRequired", 1)), "" if level_ok else "🔒"]
	lv.add_theme_font_size_override("font_size", 12)
	lv.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	info.add_child(lv)
	vb.add_child(info)
	if can:
		var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
		var max_ref := maxi(1, have / ore_qty)           # não deixa pedir mais do que dá
		var qty := SpinBox.new(); qty.min_value = 1; qty.max_value = max_ref
		qty.value = clampi(int(refine_qty.get(ore, 1)), 1, max_ref)
		qty.custom_minimum_size = Vector2(80, 0)
		qty.value_changed.connect(func(v): refine_qty[ore] = int(v))
		row.add_child(qty)
		row.add_child(UiKit.small_btn("Refinar", _refine.bind(ore)))
		vb.add_child(row)
	elif not enough and level_ok:
		var nob := UiKit.small_btn("Sem minério", Callable())
		nob.disabled = true
		vb.add_child(nob)
	return pc

func _craft_card(r: Dictionary) -> PanelContainer:
	var rarity := int(r.get("rarity", 1))
	var col := UiKit.rarity_color(rarity)
	var can := bool(r.get("canCraft", false))
	var res := UiKit.card(col, can)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	var sockets := int(r.get("sockets", 0))
	# nome com ÍCONE do item à esquerda (arma → render do modelo; resto → slot) [SLOT_WEAPON_IMG]
	var nrow := HBoxContainer.new(); nrow.add_theme_constant_override("separation", 10)
	var ic := UiKit.item_icon_for({"type": str(r.get("slot", "")), "name": str(r.get("name", ""))}, 36)
	if ic:
		nrow.add_child(ic)
	var nm := Label.new()
	nm.text = "%s (%d socket%s)" % [str(r.get("name", "?")), sockets, "" if sockets == 1 else "s"]
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", col)
	nm.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nrow.add_child(nm)
	vb.add_child(nrow)
	# ingredientes — P1: cor verde se tem o bastante, vermelho se falta.
	for i in r.get("ingredients", []):
		if i is Dictionary:
			var need := int(i.get("qty", 1))
			var have := _resource_qty(str(i.get("type", "")))
			var ing := Label.new()
			ing.text = "%s  %d/%d" % [str(i.get("name", "?")), have, need]
			ing.add_theme_font_size_override("font_size", 12)
			ing.add_theme_color_override("font_color", UiKit.OK if have >= need else UiKit.ERR)
			vb.add_child(ing)
	# stats
	var st := _craft_stats(r)
	if st != "":
		var sl := Label.new(); sl.text = st; sl.add_theme_font_size_override("font_size", 12)
		sl.add_theme_color_override("font_color", Color(0.62, 0.75, 0.58))
		vb.add_child(sl)
	# [COMPARA] vs item equipado no mesmo slot (▲Melhor/▼Pior/◆Lateral + deltas)
	var cmp := UiKit.compare_line_raw(str(r.get("slot", "")), int(r.get("atk", 0)), int(r.get("def", 0)), int(r.get("hp", 0)), int(r.get("str", 0)), int(r.get("dex", 0)), int(r.get("luk", 0)))
	if cmp != null:
		vb.add_child(cmp)
	vb.add_child(UiKit.dim(Lang.t("Forja Lv.%d %s") % [int(r.get("levelRequired", 1)), "" if can else "🔒"]))
	if can:
		var pct := int(r.get("successPct", 0))
		var pct_col := UiKit.OK if pct >= 80 else (UiKit.WARN if pct >= 50 else UiKit.ERR)
		# [MOEDA] taxa de refino em ícone pixel-art
		var info := HBoxContainer.new(); info.add_theme_constant_override("separation", 4)
		var info_a := Label.new(); info_a.text = Lang.t("🎲 Sucesso: %d%% · Taxa:") % pct
		info_a.add_theme_font_size_override("font_size", 12); info_a.add_theme_color_override("font_color", pct_col)
		info_a.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		info.add_child(info_a)
		info.add_child(UiKit.coin_box(int(r.get("bronzeCost", 0)), 14))
		vb.add_child(info)
		vb.add_child(UiKit.small_btn("Craftar", _craft.bind(str(r.get("id", "")))))
	return pc

func _gem_card(r: Dictionary) -> PanelContainer:
	var frag := str(r.get("fragment", ""))
	var have := _resource_qty(frag)
	var can := have >= 3
	var res := UiKit.card(UiKit.BRONZE, can)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	vb.add_child(UiKit.body("%s ×3 → %s" % [str(r.get("fragmentName", frag)), str(r.get("gemName", ""))]))
	var hv := Label.new(); hv.text = Lang.t("Você tem: %d fragmentos") % have
	hv.add_theme_font_size_override("font_size", 12)
	hv.add_theme_color_override("font_color", UiKit.OK if can else UiKit.ERR)
	vb.add_child(hv)
	if can:
		vb.add_child(UiKit.small_btn("Criar Joia", _craft_gem.bind(frag)))
	return pc

func _maint_card(it: Dictionary) -> PanelContainer:
	var col := UiKit.rarity_color(int(it.get("rarity", 1)))
	var res := UiKit.card(col)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	var nm := Label.new()
	nm.text = str(it.get("name", "?")) + (Lang.t(" · ⚔ equipado") if it.get("equipped", false) else "")
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", col)
	nm.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(nm)
	var dur := int(it.get("durability", 100))
	var db := Label.new(); db.text = Lang.t("Durabilidade: %d%%") % dur
	db.add_theme_font_size_override("font_size", 12)
	db.add_theme_color_override("font_color", UiKit.WARN if dur < 100 else UiKit.TEXT_DIM)
	vb.add_child(db)
	var id := int(it.get("id", 0))
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 6)
	if dur < 100:
		row.add_child(UiKit.small_btn("🔧 Reparar", _repair.bind(id)))
	row.add_child(UiKit.small_btn("♻ Reforjar", _confirm_reforge.bind(id, str(it.get("name", "este item"))), true))
	vb.add_child(row)
	return pc

# ── Ações async ───────────────────────────────────────────────────────────────────
func _refine(ore: String) -> void:
	if busy: return
	busy = true
	var qty := int(refine_qty.get(ore, 1))
	var r = await Api.smithing_refine(ore, qty)
	await _after_action(r, Lang.t("Refinado!"))

func _craft(recipe_id: String) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_craft(recipe_id)
	# craft pode falhar (200 com success=false) — mostra a mensagem do servidor
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		var ok := bool(j.get("success", false))
		busy = false
		await _refresh()
		UiKit.flash(status, str(j.get("message", "")), 1 if ok else 2)
	else:
		UiKit.show_error(status, r); busy = false

func _craft_gem(frag: String) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_gem(frag)
	await _after_action(r, Lang.t("Joia criada!"))

func _repair(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_repair(id)
	await _after_action(r, Lang.t("Reparado!"))

# P0: reforja é irreversível → confirma antes.
func _confirm_reforge(id: int, item_name: String) -> void:
	UiKit.confirm(self, Lang.t("Reforjar \"%s\"? Os stats serão re-rolados — isso é irreversível.") % item_name, "Reforjar", func() -> void: await _reforge(id), true)

func _reforge(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_reforge(id)
	await _after_action(r, Lang.t("Reforjado!"))

# Resultado padrão (sucesso = mensagem + re-refresh; falha = erro).
func _after_action(r, fallback: String) -> void:
	if r.get("ok") and r.get("json") is Dictionary:
		busy = false
		await _refresh()
		UiKit.flash(status, str(r["json"].get("message", fallback)), 1)
	else:
		UiKit.show_error(status, r); busy = false

# ── helpers de dados ──────────────────────────────────────────────────────────────
func _resource_qty(type_name: String) -> int:
	for r in resources:
		if r is Dictionary and str(r.get("type", "")) == type_name:
			return int(r.get("quantity", 0))
	return 0

func _materials_text() -> String:
	var cats := ["ORE", "BAR", "FRAGMENT", "GEM", "ESSENCE", "MATERIAL"]
	var parts: Array = []
	for cat in cats:
		for r in resources:
			if r is Dictionary and str(r.get("category", "")) == cat and int(r.get("quantity", 0)) > 0:
				parts.append("%s ×%d" % [str(r.get("displayName", r.get("type", "?"))), int(r.get("quantity", 0))])
	return "    ".join(parts)

func _craft_stats(r: Dictionary) -> String:
	var parts: Array = []
	for pair in [["atk", "ATK"], ["def", "DEF"], ["hp", "HP"], ["str", "STR"], ["dex", "DEX"], ["luk", "LUK"]]:
		var v := int(r.get(pair[0], 0))
		if v > 0:
			parts.append("+%d %s" % [v, pair[1]])
	return "   ".join(parts)
