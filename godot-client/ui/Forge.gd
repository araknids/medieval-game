extends Control
# ── Tela FORJA (Smithing) ─────────────────────────────────────────────────────────
# Espelha renderSmithing() do app.js: nível de Forja, resumo de materiais,
# refino (minério→barra), craft de equipamento (com % de sucesso), criar joias
# (3 fragmentos→1 joia) e manutenção (reparar/reforjar itens). Volta com go_back.
# Endpoints: GET /api/smithing/recipes, GET /api/gathering/resources, GET /api/inventory,
# POST /api/smithing/{refine|craft|gem|repair/{id}|reforge/{id}}. [MIGRACAO_GODOT]

signal go_back

# raridade 1-5 → cor
const RARITY_COL := [Color(0.72, 0.72, 0.75), Color(0.45, 0.85, 0.45), Color(0.4, 0.6, 1.0), Color(0.78, 0.45, 0.95), Color(1.0, 0.8, 0.35)]

var content: VBoxContainer
var status: Label
var busy := false

var recipes: Dictionary = {}      # {refine:[], craft:[], gems:[]}
var resources: Array = []         # GET /api/gathering/resources
var inventory: Array = []         # GET /api/inventory
var refine_qty: Dictionary = {}   # ore name → quantidade escolhida (SpinBox)

func _ready() -> void:
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var bg := ColorRect.new()
	bg.color = Color(0.09, 0.08, 0.11)
	bg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(bg)
	var root := VBoxContainer.new()
	root.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side in ["left", "right", "top", "bottom"]:
		root.add_theme_constant_override("margin_" + side, 0)
	add_child(root)
	# header: ← voltar + título + ↻
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 10)
	var back := Button.new(); back.text = "←"; back.custom_minimum_size = Vector2(44, 36)
	back.pressed.connect(func() -> void: go_back.emit())
	header.add_child(back)
	var ttl := Label.new(); ttl.text = "🔨 Forja"; ttl.add_theme_font_size_override("font_size", 26)
	ttl.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	header.add_child(ttl)
	var sync := Button.new(); sync.text = "↻"; sync.custom_minimum_size = Vector2(40, 36)
	sync.pressed.connect(func() -> void: await _refresh())
	header.add_child(sync)
	var m := MarginContainer.new()
	for side in ["left", "right", "top"]:
		m.add_theme_constant_override("margin_" + side, 16)
	m.add_child(header)
	root.add_child(m)
	status = Label.new(); status.add_theme_constant_override("margin_left", 16)
	root.add_child(status)
	# conteúdo rolável
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	root.add_child(scroll)
	var inner := MarginContainer.new()
	inner.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for side in ["left", "right", "bottom"]:
		inner.add_theme_constant_override("margin_" + side, 16)
	scroll.add_child(inner)
	content = VBoxContainer.new()
	content.add_theme_constant_override("separation", 6)
	content.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	inner.add_child(content)
	await _refresh()

func _refresh() -> void:
	status.text = "Carregando…"
	var rr = await Api.smithing_recipes()
	if not (rr.get("ok") and rr.get("json") is Dictionary):
		status.text = "Erro ao carregar (%s)" % str(rr.get("status", "?"))
		return
	recipes = rr["json"]
	var res = await Api.get_resources()
	resources = res["json"] if (res.get("ok") and res.get("json") is Array) else []
	var inv = await Api.get_inventory()
	inventory = inv["json"] if (inv.get("ok") and inv.get("json") is Array) else []
	status.text = ""
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	# ── Seus materiais ──
	content.add_child(_section("📦 Seus materiais"))
	var mats := _materials_text()
	if mats == "":
		content.add_child(_dim("— sem materiais (minere/colete pra forjar) —"))
	else:
		var ml := Label.new(); ml.text = mats; ml.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		ml.modulate = Color(0.88, 0.82, 0.68); ml.add_theme_font_size_override("font_size", 12)
		content.add_child(ml)
	# ── Refinar ──
	content.add_child(_spacer(6))
	content.add_child(_section("Refinar Minérios → Barras"))
	var refine: Array = recipes.get("refine", [])
	if refine.is_empty():
		content.add_child(_dim("— sem receitas —"))
	for r in refine:
		if r is Dictionary:
			content.add_child(_refine_card(r))
	# ── Craftar equipamento ──
	content.add_child(_spacer(6))
	content.add_child(_section("Craftar Equipamento"))
	var craft: Array = recipes.get("craft", [])
	if craft.is_empty():
		content.add_child(_dim("— sem receitas —"))
	for r in craft:
		if r is Dictionary:
			content.add_child(_craft_card(r))
	# ── Joias ──
	content.add_child(_spacer(6))
	content.add_child(_section("Criar Joias"))
	var gems: Array = recipes.get("gems", [])
	if gems.is_empty():
		content.add_child(_dim("— sem fragmentos —"))
	for r in gems:
		if r is Dictionary:
			content.add_child(_gem_card(r))
	# ── Manutenção ──
	content.add_child(_spacer(6))
	content.add_child(_section("🔧 Manutenção (Reparar / Reforjar)"))
	if inventory.is_empty():
		content.add_child(_dim("— sem itens —"))
	for it in inventory:
		if it is Dictionary:
			content.add_child(_maint_card(it))

# ── Cards ─────────────────────────────────────────────────────────────────────────
func _refine_card(r: Dictionary) -> PanelContainer:
	var ore := str(r.get("ore", ""))
	var can := bool(r.get("canCraft", false))
	var panel := _card_panel(can)
	var vb := VBoxContainer.new(); vb.add_theme_constant_override("separation", 4)
	panel.add_child(vb)
	var t := Label.new()
	t.text = "%s ×%d + %d🥉 → %s" % [str(r.get("oreName", ore)), int(r.get("oreQty", 1)), int(r.get("bronzeCost", 0)), str(r.get("barName", ""))]
	t.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(t)
	var lvl := Label.new()
	lvl.text = "Forja Lv.%d %s" % [int(r.get("levelRequired", 1)), "" if can else "🔒"]
	lvl.modulate = Color(1, 1, 1, 0.5); lvl.add_theme_font_size_override("font_size", 12)
	vb.add_child(lvl)
	if can:
		var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 8)
		var qty := SpinBox.new(); qty.min_value = 1; qty.max_value = 100000; qty.value = int(refine_qty.get(ore, 1))
		qty.custom_minimum_size = Vector2(80, 0)
		qty.value_changed.connect(func(v): refine_qty[ore] = int(v))
		row.add_child(qty)
		var bar_name := str(r.get("bar", ""))
		row.add_child(_act("Refinar", _refine.bind(ore)))
		vb.add_child(row)
	return panel

func _craft_card(r: Dictionary) -> PanelContainer:
	var rarity := int(r.get("rarity", 1))
	var col: Color = RARITY_COL[clampi(rarity - 1, 0, 4)]
	var can := bool(r.get("canCraft", false))
	var panel := _card_panel(can, col)
	var vb := VBoxContainer.new(); vb.add_theme_constant_override("separation", 3)
	panel.add_child(vb)
	var nm := Label.new()
	var sockets := int(r.get("sockets", 0))
	nm.text = "%s (%d socket%s)" % [str(r.get("name", "?")), sockets, "" if sockets == 1 else "s"]
	nm.modulate = col; nm.add_theme_font_size_override("font_size", 15)
	nm.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(nm)
	# ingredientes
	var ing_parts: Array = []
	for i in r.get("ingredients", []):
		if i is Dictionary:
			ing_parts.append("%s ×%d" % [str(i.get("name", "?")), int(i.get("qty", 1))])
	var ing := Label.new(); ing.text = " + ".join(ing_parts)
	ing.modulate = Color(1, 1, 1, 0.6); ing.add_theme_font_size_override("font_size", 12)
	ing.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(ing)
	# stats
	var st := _craft_stats(r)
	if st != "":
		var sl := Label.new(); sl.text = st; sl.add_theme_font_size_override("font_size", 12); sl.modulate = Color(0.8, 0.9, 0.8)
		vb.add_child(sl)
	var lvl := Label.new()
	lvl.text = "Forja Lv.%d %s" % [int(r.get("levelRequired", 1)), "" if can else "🔒"]
	lvl.modulate = Color(1, 1, 1, 0.5); lvl.add_theme_font_size_override("font_size", 12)
	vb.add_child(lvl)
	if can:
		var info := Label.new()
		info.text = "🎲 Sucesso: %d%% · Taxa: %d🥉" % [int(r.get("successPct", 0)), int(r.get("bronzeCost", 0))]
		info.modulate = Color(0.55, 0.76, 0.29); info.add_theme_font_size_override("font_size", 12)
		vb.add_child(info)
		vb.add_child(_act("Craftar", _craft.bind(str(r.get("id", "")))))
	return panel

func _gem_card(r: Dictionary) -> PanelContainer:
	var frag := str(r.get("fragment", ""))
	var have := _resource_qty(frag)
	var can := have >= 3
	var panel := _card_panel(can)
	var vb := VBoxContainer.new(); vb.add_theme_constant_override("separation", 4)
	panel.add_child(vb)
	var t := Label.new()
	t.text = "%s ×3 → %s" % [str(r.get("fragmentName", frag)), str(r.get("gemName", ""))]
	t.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(t)
	var hv := Label.new(); hv.text = "Você tem: %d fragmentos" % have
	hv.modulate = Color(1, 1, 1, 0.5); hv.add_theme_font_size_override("font_size", 12)
	vb.add_child(hv)
	if can:
		vb.add_child(_act("Criar Joia", _craft_gem.bind(frag)))
	return panel

func _maint_card(it: Dictionary) -> PanelContainer:
	var rarity := int(it.get("rarity", 1))
	var col: Color = RARITY_COL[clampi(rarity - 1, 0, 4)]
	var panel := _card_panel(true, col)
	var vb := VBoxContainer.new(); vb.add_theme_constant_override("separation", 3)
	panel.add_child(vb)
	var nm := Label.new()
	nm.text = str(it.get("name", "?")) + (" · ⚔ equipado" if it.get("equipped", false) else "")
	nm.modulate = col; nm.add_theme_font_size_override("font_size", 15)
	nm.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vb.add_child(nm)
	var dur := int(it.get("durability", 100))
	var db := Label.new(); db.text = "Durabilidade: %d%%" % dur
	db.modulate = (Color(0.9, 0.5, 0.3) if dur < 100 else Color(1, 1, 1, 0.5)); db.add_theme_font_size_override("font_size", 12)
	vb.add_child(db)
	var id := int(it.get("id", 0))
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 6)
	if dur < 100:
		row.add_child(_act("🔧 Reparar", _repair.bind(id)))
	row.add_child(_act("♻ Reforjar", _reforge.bind(id)))
	vb.add_child(row)
	return panel

# ── Ações async ───────────────────────────────────────────────────────────────────
func _refine(ore: String) -> void:
	if busy: return
	busy = true
	var qty := int(refine_qty.get(ore, 1))
	var r = await Api.smithing_refine(ore, qty)
	_after_action(r, "Refinado!")

func _craft(recipe_id: String) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_craft(recipe_id)
	# craft pode falhar (200 com success=false) — mostra a mensagem do servidor
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		var ok := bool(j.get("success", false))
		status.text = ("✅ " if ok else "❌ ") + str(j.get("message", ""))
		busy = false
		await _refresh()
	else:
		_show_error(r); busy = false

func _craft_gem(frag: String) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_gem(frag)
	_after_action(r, "Joia criada!")

func _repair(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_repair(id)
	_after_action(r, "Reparado!")

func _reforge(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.smithing_reforge(id)
	_after_action(r, "Reforjado!")

# Resultado padrão (sucesso = mensagem + re-refresh; falha = erro).
func _after_action(r, fallback: String) -> void:
	if r.get("ok") and r.get("json") is Dictionary:
		status.text = str(r["json"].get("message", fallback))
		busy = false
		await _refresh()
	else:
		_show_error(r); busy = false

func _show_error(r) -> void:
	if r is Dictionary and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		status.text = str(j.get("message", j.get("error", "Falhou")))
	else:
		status.text = "Falhou (%s)" % str(r.get("status", "?") if r is Dictionary else "?")

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

# ── helpers de UI ─────────────────────────────────────────────────────────────────
func _card_panel(enabled: bool, border := Color(0.4, 0.4, 0.5)) -> PanelContainer:
	var panel := PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.13, 0.12, 0.15)
	sb.set_border_width_all(1)
	sb.border_color = Color(border, 0.6) if enabled else Color(0.3, 0.3, 0.3, 0.5)
	sb.set_corner_radius_all(5)
	sb.set_content_margin_all(8)
	panel.add_theme_stylebox_override("panel", sb)
	panel.modulate = Color(1, 1, 1, 1.0 if enabled else 0.6)
	return panel

func _act(text: String, cb: Callable) -> Button:
	var b := Button.new(); b.text = text; b.custom_minimum_size = Vector2(120, 0)
	b.pressed.connect(cb)
	return b

func _section(t: String) -> Label:
	var l := Label.new(); l.text = t; l.add_theme_font_size_override("font_size", 19); l.modulate = Color(0.8, 0.85, 1.0)
	return l

func _dim(t: String) -> Label:
	var l := Label.new(); l.text = t; l.modulate = Color(1, 1, 1, 0.4)
	return l

func _spacer(h: int) -> Control:
	var s := Control.new(); s.custom_minimum_size = Vector2(0, h)
	return s
