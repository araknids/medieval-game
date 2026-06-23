extends Node
# [PROFILE_INSPECT] Dialog de inspeção de perfil READ-ONLY (boneco 3D + paper-doll + atributos),
# compartilhado pela Classificação e pela Arena. Autoload: ProfileInspect.open(host, player_id).
# Extraído do _inspect_dialog da Leaderboards.gd (a Classificação mantém a própria cópia; esta é a
# reutilizável — a Arena chama daqui). O dim/overlay é filho do `host` (a tela que chamou).

const Icons := preload("res://ui/Icons.gd")

const LEFT_SLOTS := ["HELMET", "ARMOR", "GLOVES", "PANTS", "BOOTS"]
const RIGHT_SLOTS := ["WEAPON", "SHIELD", "SHOULDER", "RING", "NECKLACE"]
const SLOT_LABEL := {
	"WEAPON": "Arma", "SHIELD": "Escudo", "HELMET": "Elmo", "ARMOR": "Peito",
	"PANTS": "Pernas", "BOOTS": "Botas", "GLOVES": "Luvas", "SHOULDER": "Ombros",
	"RING": "Anel", "NECKLACE": "Colar",
}
const CLASS_NAMES := {"recruit": "Recruta", "warrior": "Guerreiro", "archer": "Arqueiro", "merchant": "Mercador"}

# Abre o dialog de inspeção do jogador `pid` como overlay sobre `host`.
func open(host: Control, pid: int) -> void:
	var dim := _make_dim(host)
	var r = await Api.player_profile(pid)
	if not (r.get("ok") and r.get("json") is Dictionary):
		_close_dim(dim)
		UiKit.toast(host, Lang.t("Não foi possível carregar o perfil."), "", 2)
		return
	var p: Dictionary = r["json"]
	var eq: Array = p.get("equipped", [])
	var eq_by_type := {}
	for it in eq:
		if it is Dictionary:
			eq_by_type[str(it.get("type", ""))] = it

	var card := UiKit.card(UiKit.GOLD_SOFT)
	var pc: PanelContainer = card[0]
	var vb: VBoxContainer = card[1]
	pc.custom_minimum_size = Vector2(540, 0)
	vb.add_theme_constant_override("separation", 4)
	var head := Label.new()
	head.text = _named(p)
	head.add_theme_font_size_override("font_size", 18)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	head.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(head)
	var sub := UiKit.dim("%s · Lv%d" % [_class_name(str(p.get("classId", ""))), int(p.get("level", 1))])
	sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(sub)

	var main := HBoxContainer.new()
	main.add_theme_constant_override("separation", 14)
	var doll_row := HBoxContainer.new()
	doll_row.add_theme_constant_override("separation", 5)
	doll_row.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	doll_row.add_child(_slot_col(LEFT_SLOTS, eq_by_type))
	var doll := DollView.new()
	doll.spin = true
	doll.custom_minimum_size = Vector2(180, 270)
	doll.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	doll.tooltip_text = Lang.t("Arraste para girar")
	doll_row.add_child(doll)
	doll_row.add_child(_slot_col(RIGHT_SLOTS, eq_by_type))
	main.add_child(doll_row)
	var stats := VBoxContainer.new()
	stats.add_theme_constant_override("separation", 4)
	stats.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	stats.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var cbd: Dictionary = p.get("combat", {})
	stats.add_child(UiKit.section("Combate"))
	stats.add_child(_stat_list([
		["ATK", int(cbd.get("atk", 0)), true], ["DEF", int(cbd.get("def", 0)), true], ["HP", int(cbd.get("hp", 0)), true],
		["DEX", int(cbd.get("dex", 0)), false], ["AGI", int(cbd.get("agi", 0)), false], ["LUK", int(cbd.get("luk", 0)), false],
	]))
	var at: Dictionary = p.get("attributes", {})
	stats.add_child(UiKit.section("Atributos"))
	stats.add_child(_stat_list([
		["STR", int(at.get("str", 0)), false], ["DEX", int(at.get("dex", 0)), false], ["CON", int(at.get("con", 0)), false],
		["AGI", int(at.get("agi", 0)), false], ["LUK", int(at.get("luk", 0)), false],
	]))
	main.add_child(stats)
	vb.add_child(main)
	vb.add_child(UiKit.spacer(2))
	vb.add_child(UiKit.small_btn(Lang.t("Voltar"), _close_dim.bind(dim)))
	_center_in_dim(dim, pc)
	await get_tree().process_frame
	if is_instance_valid(doll):
		doll.apply(eq, str(p.get("classId", "")), str(p.get("gender", "male")))

func _slot_col(types: Array, eq_by_type: Dictionary) -> VBoxContainer:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 5)
	col.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	for t in types:
		col.add_child(_doll_slot(str(t), eq_by_type))
	return col

func _doll_slot(type: String, eq_by_type: Dictionary) -> Control:
	var pc := ItemTooltipCard.new()
	pc.custom_minimum_size = Vector2(50, 50)
	pc.mouse_filter = Control.MOUSE_FILTER_STOP
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.06, 0.055, 0.07, 0.95)
	sb.set_border_width_all(2)
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(3)
	var it = eq_by_type.get(type, null)
	if it != null:
		pc.item = it
		pc.tooltip_text = " "
		sb.border_color = UiKit.rarity_color(int(it.get("rarity", 1)))
		var icon := UiKit.item_icon_for(it, 40)
		if icon != null:
			icon.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
			icon.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			pc.add_child(icon)
	else:
		sb.border_color = UiKit.BRONZE
		pc.tooltip_text = Lang.t(str(SLOT_LABEL.get(type, type)))
		var icon := TextureRect.new()
		icon.texture = Icons.item_tex(type)
		icon.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		icon.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		icon.custom_minimum_size = Vector2(40, 40)
		icon.modulate = Color(1, 1, 1, 0.28)
		icon.mouse_filter = Control.MOUSE_FILTER_IGNORE
		pc.add_child(icon)
	pc.add_theme_stylebox_override("panel", sb)
	return pc

func _stat_list(stats: Array) -> GridContainer:
	var g := GridContainer.new()
	g.columns = 2
	g.add_theme_constant_override("h_separation", 16)
	g.add_theme_constant_override("v_separation", 2)
	for s in stats:
		var k := Label.new()
		k.text = str(s[0])
		k.add_theme_font_size_override("font_size", 13)
		k.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		k.custom_minimum_size = Vector2(40, 0)
		g.add_child(k)
		var v := Label.new()
		v.text = str(int(s[1]))
		v.add_theme_font_size_override("font_size", 14)
		v.add_theme_color_override("font_color", UiKit.GOLD if bool(s[2]) else UiKit.TEXT)
		v.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
		v.custom_minimum_size = Vector2(52, 0)
		g.add_child(v)
	return g

# ── Overlay helpers (dim escuro clicável que fecha) ──
func _make_dim(host: Control) -> ColorRect:
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.62)
	dim.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	dim.mouse_filter = Control.MOUSE_FILTER_STOP
	host.add_child(dim)
	dim.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed:
			dim.queue_free())
	return dim

func _center_in_dim(dim: ColorRect, node: Control) -> void:
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim.add_child(center)
	node.mouse_filter = Control.MOUSE_FILTER_STOP
	center.add_child(node)

func _close_dim(dim) -> void:
	if dim != null and is_instance_valid(dim):
		dim.queue_free()

func _named(r: Dictionary) -> String:
	var title := str(r.get("title", ""))
	return (("⟨%s⟩ " % title) if title != "" else "") + str(r.get("warriorName", "?"))

func _class_name(cid: String) -> String:
	return Lang.t(str(CLASS_NAMES.get(cid, cid.capitalize())))
