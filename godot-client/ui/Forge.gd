extends Control
# ── Tela FORJA (Smithing) ─────────────────────────────────────────────────────────
# Espelha renderSmithing() do app.js: nível de Forja, resumo de materiais,
# refino (minério→barra), craft de equipamento (com % de sucesso), criar joias
# (3 fragmentos→1 joia) e manutenção (reparar/reforjar itens). Padrão visual: UiKit [PADRAO_UI_GODOT].
# Endpoints: GET /api/smithing/recipes, GET /api/gathering/resources, GET /api/inventory,
# GET /api/warrior, POST /api/smithing/{refine|craft|gem|repair/{id}|reforge/{id}}. [MIGRACAO_GODOT]

const Icons := preload("res://ui/Icons.gd")   # [FORJA_COMPACTO] ícone do resultado (barra/fragmento) no card

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
var craft_category := "all"       # [FORJA_FILTRO] categoria: all|weapon|armor|accessory (o backend manda `category`)
var craft_page := 0               # [PAGINACAO] página da seção Craftar Equipamento

const RARITY_NAMES := ["Comum", "Incomum", "Raro", "Épico", "Lendário"]
const CRAFT_PER_PAGE := 6         # [PAGINACAO] receitas de craft por página (grid 2-col → ~3 linhas)
const REPAIR_FLOOR := 50          # [DESGASTE] abaixo disso o item não repara mais (só desmontar) — casa com o backend
# [FORJA_FILTRO] chips de categoria (armas vinham primeiro por nível → armadura "sumia" nas páginas)
const CRAFT_CATEGORIES := [["all", "Todas"], ["weapon", "⚔ Armas"], ["armor", "🛡 Armadura"], ["accessory", "💍 Acessórios"]]

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🔨 Forja", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_COMMERCE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
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

# [AUDIT] Refresh PÓS-AÇÃO enxuto: refinar/craftar/joia/reparar/reforjar mudam recursos,
# inventário e carteira — NUNCA a LISTA de receitas (filtrada por classe+nível de Forja, que
# essas ações não alteram; a affordability é recalculada no _render a partir dos recursos novos).
# Mantém o `recipes` em cache → dropa o /api/smithing/recipes (payload pesado). O _refresh inicial
# (e o botão de recarregar do scaffold) seguem puxando recipes.
func _refresh_after_action() -> void:
	var rs = await Api.batch_get(["/api/gathering/resources", "/api/inventory", "/api/warrior"])
	var res = rs[0]
	if res.get("ok") and res.get("json") is Array:
		resources = res["json"]
	var inv = rs[1]
	if inv.get("ok") and inv.get("json") is Array:
		inventory = inv["json"]
	var wr = rs[2]
	if wr.get("ok") and wr.get("json") is Dictionary:
		warrior = wr["json"]
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	UiKit.set_equipped(inventory)   # [FORJA_HOVER] alimenta o compare (triângulo + tooltip rico) com o equipado
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
		_grid_section(refine, _refine_card, true, 190.0, 3)   # [FORJA_COMPACTO] grid denso (3 col), card estilo mochila
	# ── Craftar equipamento (filtro de CATEGORIA + raridade + PAGINAÇÃO no cabeçalho) ──
	var craft: Array = recipes.get("craft", [])
	var filtered: Array = []
	for r in craft:
		if not (r is Dictionary): continue
		if craft_category != "all" and str(r.get("category", "")) != craft_category: continue   # [FORJA_FILTRO] armas/armadura/acessórios
		if craft_filter > 0 and int(r.get("rarity", 1)) != craft_filter: continue
		filtered.append(r)
	if craft_category == "all":
		filtered = _interleave_by_category(filtered)   # [FORJA_FILTRO_TODAS] senão a 1ª página vira "só armas"
	var total_pages := maxi(1, (filtered.size() + CRAFT_PER_PAGE - 1) / CRAFT_PER_PAGE)
	craft_page = clampi(craft_page, 0, total_pages - 1)
	var has_next := (craft_page + 1) * CRAFT_PER_PAGE < filtered.size()
	content.add_child(UiKit.section_paged("Craftar Equipamento", craft_page, has_next, _craft_prev, _craft_next))
	if craft.is_empty():
		content.add_child(UiKit.dim("— sem receitas —"))
	else:
		content.add_child(_category_filter_row())
		content.add_child(_rarity_filter_row())
		if filtered.is_empty():
			content.add_child(UiKit.dim("— nenhuma receita com esse filtro —"))
		else:
			var slice := filtered.slice(craft_page * CRAFT_PER_PAGE, mini(filtered.size(), (craft_page + 1) * CRAFT_PER_PAGE))
			_grid_section(slice, _craft_card, true, 190.0, 3)   # [FORJA_COMPACTO] grid denso (3 col)
	# ── Joias ── (cards compactos → mais colunas)
	content.add_child(UiKit.section("Criar Joias"))
	var gems: Array = recipes.get("gems", [])
	if gems.is_empty():
		content.add_child(UiKit.dim("— sem fragmentos —"))
	else:
		_grid_section(gems, _gem_card, true, 190.0, 3)   # [FORJA_COMPACTO] grid denso (3 col)
	# ── Manutenção (ordenada pelo item mais QUEBRADO primeiro: menor durabilidade no topo) ──
	content.add_child(UiKit.section("🔧 Manutenção (Reparar / Reforjar)"))
	if inventory.is_empty():
		content.add_child(UiKit.empty("Sem itens", "Equipamentos da mochila aparecem aqui p/ reparo/reforja"))
	else:
		var maint := inventory.duplicate()
		maint.sort_custom(func(a, b): return int(a.get("durability", 100)) < int(b.get("durability", 100)))
		_grid_section(maint, _maint_card, true, 190.0, 3)   # [FORJA_COMPACTO] grid denso (3 col)

# Monta o grid de cards via UiKit.grid (responsivo — colunas pela largura real, não da janela).
# builder = func(Dictionary) -> Control; filtra não-dicionários antes (o builder assume Dictionary).
func _grid_section(items: Array, builder: Callable, compact := false, cell_w := 0.0, cols_cap := 0) -> void:
	var rows: Array = []
	for it in items:
		if it is Dictionary:
			rows.append(it)
	content.add_child(UiKit.grid(self, rows, builder, compact, cell_w, cols_cap))

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

# [FORJA_FILTRO] Chips de categoria (Todas / ⚔ Armas / 🛡 Armadura / 💍 Acessórios). O ativo aceso.
func _category_filter_row() -> Control:
	var row := HFlowContainer.new()
	row.add_theme_constant_override("h_separation", 6)
	row.add_theme_constant_override("v_separation", 6)
	for c in CRAFT_CATEGORIES:
		var b := UiKit.small_btn(str(c[1]), _set_category.bind(str(c[0])))
		b.custom_minimum_size = Vector2(0, 32)
		b.add_theme_font_size_override("font_size", 12)
		if craft_category != str(c[0]):
			b.modulate = Color(1, 1, 1, 0.5)   # inativo = apagado
		row.add_child(b)
	return row

func _set_category(cat: String) -> void:
	craft_category = cat
	craft_page = 0   # troca de categoria volta pra 1ª página
	_render()

# [FORJA_FILTRO_TODAS] As receitas vêm ordenadas por nível e as ARMAS (nível-base do tier) ficam todas
# na frente das de armadura (+5) → em "Todas" a 1ª página parecia "só armas". Intercala em rodízio
# arma → armadura → acessório (cada bucket já em ordem de nível) p/ cada página mostrar um mix.
func _interleave_by_category(rows: Array) -> Array:
	var weapon: Array = []
	var armor: Array = []
	var accessory: Array = []
	for r in rows:
		match str(r.get("category", "armor")):
			"weapon": weapon.append(r)
			"accessory": accessory.append(r)
			_: armor.append(r)
	var out: Array = []
	var i := 0
	while i < weapon.size() or i < armor.size() or i < accessory.size():
		if i < weapon.size(): out.append(weapon[i])
		if i < armor.size(): out.append(armor[i])
		if i < accessory.size(): out.append(accessory[i])
		i += 1
	return out

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
	craft_page = 0   # [PAGINACAO] troca de filtro volta pra 1ª página
	_render()

# [PAGINACAO] navegação da seção Craftar (o clamp em _render segura o limite)
func _craft_prev() -> void:
	if craft_page > 0:
		craft_page -= 1
		_render()

func _craft_next() -> void:
	craft_page += 1
	_render()

# ── Cards ─────────────────────────────────────────────────────────────────────────
# [FORJA_COMPACTO] Card no estilo do Inventário: ícone + nome + selo numa linha, DETALHE no tooltip
# (hover), ação compacta embaixo só quando dá. Encurta a Forja (era card alto com tudo inline).
func _compact_card(col: Color, can: bool, icon: Control, title: String, badge: String, badge_col: Color, tip: String, on_click := Callable()) -> PanelContainer:
	var res := UiKit.card(col, can)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(7)   # apertado, igual à mochila
	vb.add_theme_constant_override("separation", 4)
	if tip != "":
		pc.tooltip_text = tip
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
	if icon != null:
		row.add_child(icon)
	var nm := Label.new(); nm.text = title
	nm.add_theme_font_size_override("font_size", 13); nm.add_theme_color_override("font_color", col)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	nm.clip_text = true; nm.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	row.add_child(nm)
	if badge != "":
		var bl := Label.new(); bl.text = badge
		bl.add_theme_font_size_override("font_size", 11); bl.add_theme_color_override("font_color", badge_col)
		bl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		bl.mouse_filter = Control.MOUSE_FILTER_PASS
		row.add_child(bl)
	vb.add_child(row)
	# hover na faixa de info (ícone+nome) sobe pro card → mostra o tooltip; a ação (botão/spin) fica clicável.
	# vb tb PASS senão ele intercepta o hover antes do pc (igual ao _bag_card da mochila).
	for n in [vb, row, nm, icon]:
		if n != null and n is Control:
			(n as Control).mouse_filter = Control.MOUSE_FILTER_PASS
	# [FORJA_COMPACTO] clique no card (quando dá p/ fabricar) → abre o DIALOG com as opções (qtd + botão),
	# em vez de inflar o card com a SpinBox/botão inline. Mantém todos os cards na mesma altura (1 linha).
	if can and on_click.is_valid():
		pc.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
		pc.gui_input.connect(func(e: InputEvent) -> void:
			if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
				on_click.call())
	return pc

# [FORJA_GIF] Botão de AÇÃO = ícone que ANIMA no hover (set_icon), SEM texto; tooltip diz a ação.
# Substitui os botões de texto (Craftar/Refinar/Criar Joia/Reparar/Reforjar) por um gif (estilo da nav).
func _gif_btn(icon_key: String, tip: String, on_click: Callable, px := 30) -> Button:
	var b := Button.new()
	b.flat = true
	b.focus_mode = Control.FOCUS_NONE
	b.custom_minimum_size = Vector2(px + 10, px + 10)
	b.tooltip_text = tip
	b.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	var empty := StyleBoxEmpty.new()
	for s in ["normal", "hover", "pressed", "focus"]:
		b.add_theme_stylebox_override(s, empty)
	if Icons.set_icon(b, icon_key):
		b.expand_icon = true
		b.add_theme_constant_override("icon_max_width", px)
	else:
		b.text = tip
	b.pressed.connect(on_click)
	return b

# Fecha o dialog de manutenção ANTES de disparar a ação.
func _do_repair(id: int, dim: Control) -> void:
	if is_instance_valid(dim):
		dim.queue_free()
	_repair(id)

func _do_reforge(id: int, item_name: String, dim: Control) -> void:
	if is_instance_valid(dim):
		dim.queue_free()
	_confirm_reforge(id, item_name)

# [FORJA_DIALOG] Dialog central (dim + card) reaproveitável p/ refino/joia → retorna [dim, vbox] p/ preencher.
func _open_dialog(border := UiKit.BRONZE) -> Array:
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.62)
	dim.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	dim.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(dim)
	dim.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed:
			dim.queue_free())
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim.add_child(center)
	var res := UiKit.card(border)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	pc.mouse_filter = Control.MOUSE_FILTER_STOP
	pc.custom_minimum_size = Vector2(280, 0)
	vb.add_theme_constant_override("separation", 8)
	center.add_child(pc)
	return [dim, vb]

# Cabeçalho (ícone + nome + sub) dos dialogs de refino/joia.
func _dialog_head(vb: VBoxContainer, icon: Control, title: String, sub: String) -> void:
	var head := HBoxContainer.new(); head.add_theme_constant_override("separation", 8); head.alignment = BoxContainer.ALIGNMENT_CENTER
	if icon != null:
		head.add_child(icon)
	var t := Label.new(); t.text = title; t.add_theme_font_size_override("font_size", 16); t.add_theme_color_override("font_color", UiKit.GOLD)
	head.add_child(t)
	vb.add_child(head)
	var s := Label.new(); s.text = sub; s.add_theme_font_size_override("font_size", 12); s.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	s.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER; s.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(s)

# Linha de quantidade (SpinBox 1..max) + botão gif. on_confirm(qtd) dispara a ação e o dim fecha.
func _dialog_qty_row(vb: VBoxContainer, max_n: int, label: String, dim: ColorRect, on_confirm: Callable) -> void:
	var qrow := HBoxContainer.new(); qrow.add_theme_constant_override("separation", 10); qrow.alignment = BoxContainer.ALIGNMENT_CENTER
	qrow.add_child(UiKit.dim(Lang.t("Qtd (máx %d):") % max_n))
	var qty := SpinBox.new(); qty.min_value = 1; qty.max_value = maxi(1, max_n); qty.value = 1
	qty.custom_minimum_size = Vector2(80, 0)
	qrow.add_child(qty)
	var btn := _gif_btn("forge", label, func() -> void:
		var n := int(qty.value)
		if is_instance_valid(dim):
			dim.queue_free()
		on_confirm.call(n))
	btn.add_theme_constant_override("icon_max_width", 40); btn.custom_minimum_size = Vector2(50, 50)
	qrow.add_child(btn)
	vb.add_child(qrow)

# [FORJA_COMPACTO] Clique no card de minério → dialog com a quantidade + Refinar.
func _refine_dialog(r: Dictionary) -> void:
	var ore := str(r.get("ore", ""))
	var ore_qty := maxi(1, int(r.get("oreQty", 1)))
	var have := _resource_qty(ore)
	var max_ref := maxi(1, have / ore_qty)
	var d := _open_dialog()
	var dim: ColorRect = d[0]
	var vb: VBoxContainer = d[1]
	_dialog_head(vb, Icons.rect("res_" + str(r.get("bar", "")).to_lower(), 30), str(r.get("barName", "?")),
		Lang.t("%s ×%d + %d bronze · você tem %d") % [str(r.get("oreName", ore)), ore_qty, int(r.get("bronzeCost", 0)), have])
	_dialog_qty_row(vb, max_ref, Lang.t("Refinar"), dim, func(n: int) -> void:
		refine_qty[ore] = n
		_refine(ore))

# [FORJA_COMPACTO] Clique no card de fragmento → dialog com a quantidade + Criar Joia.
func _gem_dialog(r: Dictionary) -> void:
	var frag := str(r.get("fragment", ""))
	var have := _resource_qty(frag)
	var max_g := maxi(1, have / 3)
	var d := _open_dialog()
	var dim: ColorRect = d[0]
	var vb: VBoxContainer = d[1]
	_dialog_head(vb, Icons.rect("res_" + frag.to_lower(), 30), str(r.get("gemName", "?")),
		Lang.t("%s ×3 · você tem %d fragmentos") % [str(r.get("fragmentName", frag)), have])
	_dialog_qty_row(vb, max_g, Lang.t("Criar Joia"), dim, func(n: int) -> void:
		_craft_gem_n(frag, n))

# Cria N joias (o backend é 1 por chamada) — para no 1º erro.
func _craft_gem_n(frag: String, n: int) -> void:
	if busy:
		return
	busy = true
	var made := 0
	for i in n:
		var r = await Api.smithing_gem(frag)
		if not (r.get("ok") and r.get("json") is Dictionary):
			busy = false
			if made > 0:
				await _refresh_after_action()
			UiKit.show_error(status, r)
			return
		made += 1
	busy = false
	await _refresh_after_action()
	UiKit.flash(status, Lang.t("%d joia(s) criada(s)") % made, 1)

func _refine_card(r: Dictionary) -> PanelContainer:
	var ore := str(r.get("ore", ""))
	var ore_qty := maxi(1, int(r.get("oreQty", 1)))
	var have := _resource_qty(ore)                       # quanto minério o jogador tem
	var level_ok := bool(r.get("canCraft", false))       # canCraft = só o nível da Forja
	var enough := have >= ore_qty
	var can := level_ok and enough
	var icon := Icons.rect("res_" + str(r.get("bar", "")).to_lower(), 28)   # ícone da barra resultante
	var tip := Lang.t("%s ×%d + %d bronze → %s\nForja Lv.%d%s · Você tem: %d") % [str(r.get("oreName", ore)), ore_qty, int(r.get("bronzeCost", 0)), str(r.get("barName", "")), int(r.get("levelRequired", 1)), "" if level_ok else Lang.t(" (trava de nível)"), have]
	var on_click := Callable()
	if can:
		on_click = func() -> void: _refine_dialog(r)
	return _compact_card(UiKit.BRONZE, can, icon, str(r.get("barName", "?")), "%d/%d" % [have, ore_qty], UiKit.OK if enough else UiKit.ERR, tip, on_click)

func _craft_card(r: Dictionary) -> Control:
	var rarity := int(r.get("rarity", 1))
	var col := UiKit.rarity_color(rarity)
	var can := bool(r.get("canCraft", false))
	var sockets := int(r.get("sockets", 0))
	# [FORJA_HOVER] item SINTÉTICO com os stats da receita → tooltip RICO + comparação IGUAIS à Mochila
	# (mesmo ItemTooltipCard). A receita (ingredientes/sucesso/custo) entra como "description" (vira a lore).
	var ing: Array = []
	for i in r.get("ingredients", []):
		if i is Dictionary:
			var need := int(i.get("qty", 1))
			var hv := _resource_qty(str(i.get("type", "")))
			ing.append("%s %d/%d" % [str(i.get("name", "?")), hv, need])
	var desc := Lang.t("Receita: %s · Forja Lv.%d · Sucesso %d%% · Custo %d bronze") % ["  ".join(ing), int(r.get("levelRequired", 1)), int(r.get("successPct", 0)), int(r.get("bronzeCost", 0))]
	if int(r.get("scrapCost", 0)) > 0:   # [DESMONTAGEM] arma também custa Peças
		desc += Lang.t(" + %d Peças") % int(r.get("scrapCost", 0))
	var item := {
		"type": str(r.get("slot", "")), "name": str(r.get("name", "")), "rarity": rarity,
		"attackBonus": int(r.get("atk", 0)), "defenseBonus": int(r.get("def", 0)), "healthBonus": int(r.get("hp", 0)),
		"strBonus": int(r.get("str", 0)), "dexBonus": int(r.get("dex", 0)), "lukBonus": int(r.get("luk", 0)),
		"sockets": sockets, "itemLevel": int(r.get("levelRequired", 1)),
		"outfitTheme": str(r.get("outfitTheme", "")), "description": desc,
	}
	var card := ItemTooltipCard.new()   # mesmo card do Inventário → hover idêntico
	card.item = item
	card.player_level = int(warrior.get("level", 0))
	card.tooltip_text = " "
	var res := UiKit.card_styled(card, col, can)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(7)
	vb.add_theme_constant_override("separation", 4)
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
	var ic := UiKit.item_icon_for(item, 28)
	if ic:
		row.add_child(ic)
	var nm := Label.new(); nm.text = str(r.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 13); nm.add_theme_color_override("font_color", col)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	nm.clip_text = true; nm.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	row.add_child(nm)
	var lvl := Label.new(); lvl.text = Lang.t("Lv.%d") % int(r.get("levelRequired", 1))   # igual ao "Nv X" da mochila
	lvl.add_theme_font_size_override("font_size", 11)
	lvl.add_theme_color_override("font_color", UiKit.TEXT_DIM if can else UiKit.ERR)   # vermelho se nível trava
	lvl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	lvl.mouse_filter = Control.MOUSE_FILTER_PASS
	row.add_child(lvl)
	var arrow := UiKit.compare_arrow(item)   # ▲/▼/= vs equipado (o triângulo verde/vermelho) [FORJA_HOVER]
	if arrow != null:
		arrow.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(arrow)
	vb.add_child(row)
	for n in [vb, row, nm, ic]:   # hover na info sobe pro card → tooltip rico (igual à mochila)
		if n != null and n is Control:
			(n as Control).mouse_filter = Control.MOUSE_FILTER_PASS
	# clique → dialog de craft (igual ao item da mochila: clica e abre as opções) [FORJA_CRAFT_DIALOG]
	card.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	card.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
			_craft_dialog(r))
	return pc

# [FORJA_CRAFT_DIALOG] Clique no item de craft → dialog (1 card só) com o painel RICO + ingredientes +
# QUANTIDADE pelos insumos do player + o GIF de craftar (anima no hover). Backend crafta 1 por vez → loop.
func _craft_dialog(r: Dictionary) -> void:
	var rarity := int(r.get("rarity", 1))
	var can := bool(r.get("canCraft", false))
	var item := {
		"type": str(r.get("slot", "")), "name": str(r.get("name", "")), "rarity": rarity,
		"attackBonus": int(r.get("atk", 0)), "defenseBonus": int(r.get("def", 0)), "healthBonus": int(r.get("hp", 0)),
		"strBonus": int(r.get("str", 0)), "dexBonus": int(r.get("dex", 0)), "lukBonus": int(r.get("luk", 0)),
		"sockets": int(r.get("sockets", 0)), "itemLevel": int(r.get("levelRequired", 1)),
		"outfitTheme": str(r.get("outfitTheme", "")),
	}
	# quantos dá p/ craftar pelos INSUMOS (mín entre os ingredientes); cap p/ não fazer loop gigante
	var maxc := 99
	for i in r.get("ingredients", []):
		if i is Dictionary:
			var need := maxi(1, int(i.get("qty", 1)))
			maxc = mini(maxc, _resource_qty(str(i.get("type", ""))) / need)
	maxc = clampi(maxc, 0, 50)
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.62)
	dim.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	dim.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(dim)
	dim.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed:
			dim.queue_free())
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim.add_child(center)
	var res := UiKit.card(UiKit.rarity_color(rarity))
	var card_pc: PanelContainer = res[0]
	var dvb: VBoxContainer = res[1]
	card_pc.mouse_filter = Control.MOUSE_FILTER_STOP
	dvb.add_theme_constant_override("separation", 8)
	center.add_child(card_pc)
	var panel := UiKit.item_tooltip_panel(item, {"equipped": false, "player_level": int(warrior.get("level", 0))})
	panel.add_theme_stylebox_override("panel", StyleBoxEmpty.new())   # sem borda interna → parte do dialog
	dvb.add_child(panel)
	for i in r.get("ingredients", []):   # ingredientes (verde/vermelho)
		if i is Dictionary:
			var need := int(i.get("qty", 1))
			var hv := _resource_qty(str(i.get("type", "")))
			var il := Label.new(); il.text = "%s  %d/%d" % [str(i.get("name", "?")), hv, need]
			il.add_theme_font_size_override("font_size", 12)
			il.add_theme_color_override("font_color", UiKit.OK if hv >= need else UiKit.ERR)
			il.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
			dvb.add_child(il)
	# [DESMONTAGEM] custo em Peças (arma) — vermelho se faltar
	var scrap_need := int(r.get("scrapCost", 0))
	if scrap_need > 0:
		var shv := _resource_qty("SCRAP")
		var sl := Label.new(); sl.text = "%s  %d/%d" % [Lang.t("Peças"), shv, scrap_need]
		sl.add_theme_font_size_override("font_size", 12)
		sl.add_theme_color_override("font_color", UiKit.OK if shv >= scrap_need else UiKit.ERR)
		sl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		dvb.add_child(sl)
	if not can:
		dvb.add_child(UiKit.dim(Lang.t("Forja Lv.%d — nível insuficiente") % int(r.get("levelRequired", 1))))
		return
	if maxc < 1:
		dvb.add_child(UiKit.dim(Lang.t("Sem materiais suficientes.")))
		return
	var info := HBoxContainer.new(); info.add_theme_constant_override("separation", 6)
	info.alignment = BoxContainer.ALIGNMENT_CENTER
	var ia := Label.new(); ia.text = Lang.t("Sucesso %d%% · cada:") % int(r.get("successPct", 0))
	ia.add_theme_font_size_override("font_size", 12); ia.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	ia.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	info.add_child(ia)
	info.add_child(UiKit.coin_box(int(r.get("bronzeCost", 0)), 13))
	dvb.add_child(info)
	var qrow := HBoxContainer.new(); qrow.add_theme_constant_override("separation", 10)
	qrow.alignment = BoxContainer.ALIGNMENT_CENTER
	qrow.add_child(UiKit.dim(Lang.t("Qtd (máx %d):") % maxc))
	var qty := SpinBox.new(); qty.min_value = 1; qty.max_value = maxc; qty.value = 1
	qty.custom_minimum_size = Vector2(80, 0)
	qrow.add_child(qty)
	var cbtn := _gif_btn("forge", Lang.t("Craftar"), func() -> void: _craft_n(str(r.get("id", "")), int(qty.value), dim))
	cbtn.add_theme_constant_override("icon_max_width", 40)   # gif maior no dialog
	cbtn.custom_minimum_size = Vector2(50, 50)
	qrow.add_child(cbtn)
	dvb.add_child(qrow)

# Crafta a receita N vezes (backend é 1 por vez); para num erro (sem material/bag cheia). [FORJA_CRAFT_DIALOG]
func _craft_n(recipe_id: String, n: int, dim: Control) -> void:
	if busy:
		return
	if is_instance_valid(dim):
		dim.queue_free()
	busy = true
	var ok_count := 0
	for i in n:
		var r = await Api.smithing_craft(recipe_id)
		if not (r.get("ok") and r.get("json") is Dictionary):
			break   # erro (sem material / bag cheia) → para o lote
		if bool(r["json"].get("success", false)):
			ok_count += 1
	busy = false
	await _refresh()
	UiKit.toast(self, Lang.t("Craftado: %d de %d") % [ok_count, n], "forge", 1)

func _gem_card(r: Dictionary) -> PanelContainer:
	var frag := str(r.get("fragment", ""))
	var have := _resource_qty(frag)
	var can := have >= 3
	var icon := Icons.rect("res_" + frag.to_lower(), 28)   # ícone do fragmento
	var tip := Lang.t("%s ×3 → %s\nVocê tem: %d fragmentos") % [str(r.get("fragmentName", frag)), str(r.get("gemName", "")), have]
	var on_click := Callable()
	if can:
		on_click = func() -> void: _gem_dialog(r)
	return _compact_card(UiKit.BRONZE, can, icon, str(r.get("gemName", "?")), "%d/3" % have, UiKit.OK if can else UiKit.ERR, tip, on_click)

func _maint_card(it: Dictionary) -> Control:
	var col := UiKit.rarity_color(int(it.get("rarity", 1)))
	var card := ItemTooltipCard.new()   # [FORJA_HOVER] mesmo card da Mochila → hover idêntico
	card.item = it
	card.player_level = int(warrior.get("level", 0))
	card.tooltip_text = " "
	card.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	var res := UiKit.card_styled(card, col)
	var pc: PanelContainer = res[0]
	var vb: VBoxContainer = res[1]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(7)
	vb.add_theme_constant_override("separation", 0)
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
	var ic := UiKit.item_icon_for(it, 28)
	if ic:
		row.add_child(ic)
	var nm := Label.new(); nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 13); nm.add_theme_color_override("font_color", col)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	nm.clip_text = true; nm.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	row.add_child(nm)
	var dur := int(it.get("durability", 100))
	var dl := Label.new(); dl.text = "%d%%" % dur
	dl.add_theme_font_size_override("font_size", 11)
	dl.add_theme_color_override("font_color", UiKit.WARN if dur < 100 else UiKit.TEXT_DIM)
	dl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(dl)
	# [DESGASTE] item gasto demais (Poder < piso) não repara mais → flag vermelho "gasto" (detalhe no hover)
	if int(it.get("powerPct", 100)) < REPAIR_FLOOR:
		var pwl := Label.new(); pwl.text = Lang.t("gasto")
		pwl.add_theme_font_size_override("font_size", 11); pwl.add_theme_color_override("font_color", UiKit.ERR)
		pwl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(pwl)
	vb.add_child(row)
	for n in [vb, row, nm, ic]:
		if n != null and n is Control:
			(n as Control).mouse_filter = Control.MOUSE_FILTER_PASS
	card.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
			_maint_dialog(it))
	return pc

# [FORJA_MANUT] Clique no item → dialog com o card RICO (igual à Mochila) + Reparar/Reforjar como GIF
# único que anima no hover (forge / temple), cada um com o custo embaixo. [FORGE_REPAIR_COST] reparo =
# (100 − durabilidade) × raridade × 5 · reforja = raridade³ × 500.
func _maint_dialog(it: Dictionary) -> void:
	var dur := int(it.get("durability", 100))
	var rarity := int(it.get("rarity", 1))
	var id := int(it.get("id", 0))
	var rep_cost := (100 - dur) * rarity * 5
	var ref_cost := rarity * rarity * rarity * 500
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.62)
	dim.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	dim.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(dim)
	dim.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed:
			dim.queue_free())
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim.add_child(center)
	# UM card de dialog: detalhe do item (painel SEM borda própria) + ações DENTRO dele. [FORJA_MANUT]
	var res := UiKit.card(UiKit.rarity_color(rarity))
	var card_pc: PanelContainer = res[0]
	var dvb: VBoxContainer = res[1]
	card_pc.mouse_filter = Control.MOUSE_FILTER_STOP   # clique no card não fecha
	dvb.add_theme_constant_override("separation", 10)
	center.add_child(card_pc)
	var panel := UiKit.item_tooltip_panel(it, {"equipped": bool(it.get("equipped", false)), "player_level": int(warrior.get("level", 0))})
	panel.add_theme_stylebox_override("panel", StyleBoxEmpty.new())   # tira a borda interna → vira parte do dialog
	dvb.add_child(panel)
	var arow := HBoxContainer.new(); arow.add_theme_constant_override("separation", 24)
	arow.alignment = BoxContainer.ALIGNMENT_CENTER
	arow.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	var pwr := int(it.get("powerPct", 100))
	# [DESGASTE] gasto demais (Poder < piso) → backend bloqueia o reparo; some o botão.
	if dur < 100 and pwr >= REPAIR_FLOOR:
		arow.add_child(_maint_action("forge", Lang.t("Reparar"), rep_cost, _do_repair.bind(id, dim)))
	arow.add_child(_maint_action("temple", Lang.t("Reforjar"), ref_cost, _do_reforge.bind(id, str(it.get("name", "este item")), dim)))
	arow.add_child(_dismantle_action(id, str(it.get("name", "este item")), dim))   # [DESMONTAGEM]
	dvb.add_child(arow)
	if pwr < REPAIR_FLOOR:
		var note := UiKit.dim(Lang.t("Gasto demais pra reparar (Poder %d%%). Desmonte-o por Peças.") % pwr)
		note.add_theme_color_override("font_color", UiKit.ERR)
		note.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		dvb.add_child(note)
	elif dur < 100:
		var rep_scrap := maxi(1, rarity)   # [DESMONTAGEM] espelha repairScrapCost = max(1, raridade)
		var shv := _resource_qty("SCRAP")
		var rnote := UiKit.dim(Lang.t("Reparar: %d Peças (você tem %d) + bronze · gasta um pouco do Poder.") % [rep_scrap, shv])
		rnote.add_theme_color_override("font_color", UiKit.ERR if shv < rep_scrap else UiKit.TEXT_DIM)
		rnote.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		dvb.add_child(rnote)

# Coluna de ação no dialog: GIF que anima no hover (sem botão de texto) + custo + legenda curta.
func _maint_action(icon_key: String, tip: String, cost: int, on_click: Callable) -> VBoxContainer:
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 3)
	box.alignment = BoxContainer.ALIGNMENT_CENTER
	var b := _gif_btn(icon_key, tip, on_click, 44)
	b.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	box.add_child(b)
	var cc := UiKit.coin_box(cost, 13)
	cc.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	box.add_child(cc)
	var lb := Label.new(); lb.text = tip
	lb.add_theme_font_size_override("font_size", 11); lb.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	lb.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(lb)
	return box

# [DESMONTAGEM] Coluna de ação Desmontar: gif + "→ Peças" (ganho) + legenda. Sem custo em bronze.
func _dismantle_action(id: int, item_name: String, dim: Control) -> VBoxContainer:
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 3)
	box.alignment = BoxContainer.ALIGNMENT_CENTER
	var b := _gif_btn("package", Lang.t("Desmontar"), func() -> void: _do_dismantle(id, item_name, dim), 44)
	b.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	box.add_child(b)
	var gain := HBoxContainer.new(); gain.add_theme_constant_override("separation", 4); gain.alignment = BoxContainer.ALIGNMENT_CENTER
	if Icons.tex("res_scrap") != null:
		gain.add_child(Icons.rect("res_scrap", 13))
	var gl := Label.new(); gl.text = Lang.t("→ Peças")
	gl.add_theme_font_size_override("font_size", 11); gl.add_theme_color_override("font_color", UiKit.OK)
	gain.add_child(gl)
	box.add_child(gain)
	var lb := Label.new(); lb.text = Lang.t("Desmontar")
	lb.add_theme_font_size_override("font_size", 11); lb.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	lb.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(lb)
	return box

func _do_dismantle(id: int, item_name: String, dim: Control) -> void:
	dim.queue_free()
	UiKit.confirm(self,
		Lang.t("Desmontar \"%s\"? Vira Peças. Joias encaixadas são PERDIDAS. Não tem volta.") % item_name,
		Lang.t("Desmontar"), func() -> void: await _dismantle(id), true)

func _dismantle(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_dismantle(id)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r); return
	UiKit.flash(status, str(r["json"].get("message", Lang.t("Desmontado!"))), 1)
	await _refresh_after_action()

# ── Ações async ───────────────────────────────────────────────────────────────────
func _refine(ore: String) -> void:
	if busy: return
	busy = true
	var qty := int(refine_qty.get(ore, 1))
	var r = await Api.smithing_refine(ore, qty)
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r); busy = false
		return
	# [REFINE_FEEDBACK] XP/level-up de Forja agora aparecem (antes o backend dava XP mas a UI não mostrava).
	var j: Dictionary = r["json"]
	var xp := int(j.get("xpGained", 0))
	var leveled := bool(j.get("leveledUp", false))
	var icon := "res_" + str(j.get("barType", "")).to_lower()   # ícone da barra feita (res_<tipo>)
	var msg := str(j.get("message", Lang.t("Refinado!")))
	if xp > 0:
		msg += "  +%d XP" % xp
	if leveled:
		msg += "  ·  " + (Lang.t("Forja Nv %d!") % int(j.get("smithingLevel", 0)))
	busy = false
	if leveled:
		await _refresh()                # level-up pode liberar novas receitas → re-busca recipes
	else:
		await _refresh_after_action()
	UiKit.toast(self, msg, icon, 1)     # modal central com o ícone da barra + XP, fecha sozinho

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

# Resultado padrão (sucesso = mensagem + re-refresh enxuto; falha = erro).
func _after_action(r, fallback: String) -> void:
	if r.get("ok") and r.get("json") is Dictionary:
		busy = false
		await _refresh_after_action()
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
