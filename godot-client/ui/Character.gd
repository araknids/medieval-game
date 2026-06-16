extends Control
# ── Tela FICHA DO PERSONAGEM (fusão Personagem + Inventário + Habilidades) [FICHA_PERSONAGEM] ──
# Paper-doll: boneco 3D no centro com SLOTS de equipamento em volta (esquerda) + painel SUB-ABADO
# à direita (🎒 Mochila / ⚔ Atributos / ✨ Habilidades). Equipar atualiza o boneco AO VIVO.
# A topbar do Shell já mostra stats/atributos/HP/moedas → aqui o foco é EQUIPAR/gastar ponto/skill.
# Detalhe verboso (efeito de atributo, stats do item equipado) mora no TOOLTIP (hover).
# Auditoria + desenho: docs/PLANO_FICHA_PERSONAGEM.md. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

const Icons := preload("res://ui/Icons.gd")
const Doll := preload("res://ui/DollView.gd")
const Weapons := preload("res://Weapons.gd")   # [SLOT_WEAPON_IMG] kind da arma + mapa de modelos

# colunas de slots em volta do boneco (ItemType do backend)
const LEFT_SLOTS := ["HELMET", "ARMOR", "GLOVES", "PANTS", "BOOTS"]
const RIGHT_SLOTS := ["WEAPON", "SHIELD", "SHOULDER", "RING", "NECKLACE"]
const SLOT_LABEL := {
	"WEAPON": "Arma", "SHIELD": "Escudo", "HELMET": "Elmo", "ARMOR": "Peito",
	"PANTS": "Pernas", "BOOTS": "Botas", "GLOVES": "Luvas", "SHOULDER": "Ombros",
	"RING": "Anel", "NECKLACE": "Colar",
}

# atributo: chave no JSON, sigla
const ATTRS := [
	["strength", "STR"], ["constitution", "CON"], ["dexterity", "DEX"],
	["agility", "AGI"], ["luck", "LUK"],
]   # INT removido do front (Mago não implementado)
# Ganho EXATO por ponto (números do backend committado = prod). CON tem soft-cap (8→4→2) por faixa,
# então é calculado em _attr_gain a partir da CON atual. [REBALANCE v2]

var w: Dictionary = {}
var items: Array = []
var abilities_data: Dictionary = {}
var sub_tab := "bag"          # "bag" | "attr" | "abil"
var rarity_filter := 0
var busy := false

var content: VBoxContainer
var status: Label
var wallet
var doll: DollView
var _id_name: Label
var _id_sub: Label
var _slots := {}                  # type -> {frame, icon, item_id}
var _companions := {}             # "mount"/"pet" -> {frame, emoji} [COMPANION_SLOTS]
var _wp := Weapons.new()          # [SLOT_WEAPON_IMG] p/ derivar o kind/modelo da arma
var _subtab_bar_host: VBoxContainer
var _panel_host: VBoxContainer    # onde o painel da sub-aba é montado/limpo

func _ready() -> void:
	var ui := UiKit.scaffold(self, "👤 Personagem", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_DEFAULT)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	_build_layout()
	await _refresh()

# ── Estrutura estática (montada UMA vez — não recria o 3D a cada render) ─────────────
func _build_layout() -> void:
	var main := HBoxContainer.new()
	main.add_theme_constant_override("separation", 16)
	main.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_child(main)
	main.add_child(_build_left())
	main.add_child(_build_right())

func _build_left() -> Control:
	var outer := VBoxContainer.new()
	outer.add_theme_constant_override("separation", 8)
	outer.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	outer.add_child(row)
	row.add_child(_slot_column(LEFT_SLOTS))
	doll = Doll.new()
	doll.custom_minimum_size = Vector2(230, 350)
	doll.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	doll.tooltip_text = Lang.t("Arraste para girar o personagem")
	row.add_child(doll)
	row.add_child(_slot_column(RIGHT_SLOTS))
	_id_name = Label.new()
	_id_name.add_theme_font_size_override("font_size", 20)
	_id_name.add_theme_color_override("font_color", UiKit.GOLD)
	_id_name.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	outer.add_child(_id_name)
	_id_sub = Label.new()
	_id_sub.add_theme_font_size_override("font_size", 13)
	_id_sub.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	_id_sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	outer.add_child(_id_sub)
	# [COMPANION_SLOTS] montaria + pet (do /api/warrior), embaixo da identidade
	var comp := HBoxContainer.new()
	comp.add_theme_constant_override("separation", 16)
	comp.alignment = BoxContainer.ALIGNMENT_CENTER
	comp.add_child(_companion_slot("mount", "Montaria"))
	comp.add_child(_companion_slot("pet", "Pet"))
	outer.add_child(comp)
	return outer

# Slot de companheiro (montaria/pet): moldura + emoji + legenda. [COMPANION_SLOTS]
func _companion_slot(kind: String, caption: String) -> VBoxContainer:
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 2)
	vb.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	var pc := PanelContainer.new()
	pc.custom_minimum_size = Vector2(56, 56)
	pc.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	pc.mouse_filter = Control.MOUSE_FILTER_STOP
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.06, 0.055, 0.07, 0.95)
	sb.set_border_width_all(1)
	sb.border_color = UiKit.BRONZE
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(4)
	pc.add_theme_stylebox_override("panel", sb)
	var emoji := Label.new()
	emoji.add_theme_font_size_override("font_size", 28)
	emoji.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	emoji.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	emoji.mouse_filter = Control.MOUSE_FILTER_IGNORE
	pc.add_child(emoji)
	vb.add_child(pc)
	var cap := Label.new()
	cap.text = Lang.t(caption)
	cap.add_theme_font_size_override("font_size", 11)
	cap.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	cap.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(cap)
	_companions[kind] = {"frame": pc, "emoji": emoji}
	return vb

func _slot_column(types: Array) -> VBoxContainer:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 6)
	col.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	for t in types:
		col.add_child(_slot_frame(str(t)))
	return col

func _slot_frame(type: String) -> PanelContainer:
	var pc := PanelContainer.new()
	pc.custom_minimum_size = Vector2(56, 56)
	pc.mouse_filter = Control.MOUSE_FILTER_STOP
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.06, 0.055, 0.07, 0.95)
	sb.set_border_width_all(1)
	sb.border_color = UiKit.BRONZE
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(4)
	pc.add_theme_stylebox_override("panel", sb)
	var icon := TextureRect.new()
	icon.texture = Icons.tex("slot_" + type.to_lower())
	icon.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	icon.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	icon.custom_minimum_size = Vector2(44, 44)
	icon.mouse_filter = Control.MOUSE_FILTER_IGNORE
	icon.modulate = Color(1, 1, 1, 0.30)   # vazio = apagado
	pc.add_child(icon)
	pc.tooltip_text = Lang.t(str(SLOT_LABEL.get(type, type)))
	pc.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
			_slot_clicked(type))
	_slots[type] = {"frame": pc, "icon": icon, "item_id": 0}
	return pc

func _build_right() -> Control:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 8)
	col.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_subtab_bar_host = VBoxContainer.new()
	col.add_child(_subtab_bar_host)
	var scroll := ScrollContainer.new()      # ÚNICO scroll da tela (painel da sub-aba)
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	scroll.custom_minimum_size = Vector2(0, 330)
	scroll.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	col.add_child(scroll)
	_panel_host = VBoxContainer.new()
	_panel_host.add_theme_constant_override("separation", 8)
	_panel_host.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	scroll.add_child(_panel_host)
	return col

func _build_subtab_bar() -> void:
	for c in _subtab_bar_host.get_children():
		c.queue_free()
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 6)
	for t in [["bag", "tab_bag", "Mochila", "🎒"], ["attr", "tab_attributes", "Atributos", "⚔"], ["abil", "tab_abilities", "Habilidades", "✨"]]:
		row.add_child(_subtab_btn(str(t[0]), str(t[1]), str(t[2]), str(t[3])))
	_subtab_bar_host.add_child(row)

# Botão de sub-aba com ícone PixelLab + texto (fallback no emoji) e destaque do ativo.
func _subtab_btn(value: String, icon_key: String, label: String, emoji: String) -> Button:
	var b := UiKit.small_btn("%s %s" % [emoji, Lang.t(label)], func() -> void: _set_tab(value))
	if Icons.set_icon(b, icon_key):
		b.add_theme_constant_override("icon_max_width", 22)
		b.text = Lang.t(label)
	b.custom_minimum_size = Vector2(0, 36)
	b.add_theme_font_size_override("font_size", 13)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	if sub_tab == value:                       # ativo: fundo preenchido + borda dourada
		var col := UiKit.GOLD
		var sb := StyleBoxFlat.new()
		sb.bg_color = Color(col.r, col.g, col.b, 0.22)
		sb.set_border_width_all(2); sb.border_color = col; sb.set_corner_radius_all(6)
		sb.content_margin_left = 10; sb.content_margin_right = 10
		sb.content_margin_top = 4; sb.content_margin_bottom = 4
		b.add_theme_stylebox_override("normal", sb)
		b.add_theme_stylebox_override("hover", sb)
		b.add_theme_stylebox_override("pressed", sb)
		b.add_theme_stylebox_override("focus", sb)
	else:
		b.modulate = Color(1, 1, 1, 0.6)
	return b

func _set_tab(t) -> void:
	sub_tab = str(t)
	_build_subtab_bar()
	_render_panel()

# ── Dados ────────────────────────────────────────────────────────────────────────────
func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/warrior", "/api/inventory", "/api/abilities"])
	var wr = rs[0]
	if not (wr.get("ok") and wr.get("json") is Dictionary):
		UiKit.show_error(status, wr)
		return
	w = wr["json"]
	var ir = rs[1]
	items = ir["json"] if (ir.get("ok") and ir.get("json") is Array) else []
	var ar = rs[2]
	abilities_data = ar["json"] if (ar.get("ok") and ar.get("json") is Dictionary) else {}
	_apply()

func _apply() -> void:
	UiKit.flash(status, "", 0)
	UiKit.current_class = str(w.get("warriorClassId", UiKit.current_class))   # tema das roupas (slot + ícone + doll) [OUTFITS_CLASSE]
	UiKit.set_wallet(wallet, w)        # alimenta a topbar (HP/estamina/stats/moedas)
	UiKit.set_equipped(items)
	if doll != null and is_instance_valid(doll):
		doll.apply(items, UiKit.current_class)
	var title := str(w.get("title", ""))
	_id_name.text = (title + "  " if title != "" else "") + str(w.get("name", "?"))
	_id_sub.text = Lang.t("%s · Nível %d") % [Lang.t(str(w.get("warriorClass", "Recruta"))), int(w.get("level", 1))]
	if bool(w.get("isKnockedOut", false)):
		_id_sub.text += "   💀"
	_update_slots()
	_update_companions()
	_build_subtab_bar()
	_render_panel()

# Preenche os slots de montaria/pet a partir do warrior (equippedMount/equippedPet). [COMPANION_SLOTS]
func _update_companions() -> void:
	_fill_companion("mount", w.get("equippedMount"), "🐎", "Montaria")
	_fill_companion("pet", w.get("equippedPet"), "🐾", "Pet")

func _fill_companion(kind: String, data, generic_emoji: String, label: String) -> void:
	var c: Dictionary = _companions.get(kind, {})
	if c.is_empty():
		return
	var pc: PanelContainer = c["frame"]
	var emoji: Label = c["emoji"]
	var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
	if data is Dictionary:
		emoji.text = str(data.get("icon", generic_emoji))
		emoji.modulate = Color(1, 1, 1, 1)
		sb.border_color = UiKit.GOLD
		sb.set_border_width_all(2)
		pc.tooltip_text = _companion_tooltip(kind, data, label)
	else:
		emoji.text = generic_emoji
		emoji.modulate = Color(1, 1, 1, 0.30)
		sb.border_color = UiKit.BRONZE
		sb.set_border_width_all(1)
		pc.tooltip_text = "%s — %s" % [Lang.t(label), Lang.t("nenhuma equipada")]

func _companion_tooltip(kind: String, data: Dictionary, label: String) -> String:
	var nm := str(data.get("name", data.get("displayName", "?")))
	var t := Lang.t(label) + ": " + nm
	var parts: Array = []
	if kind == "mount":
		var st := int(data.get("staminaReductionPct", 0))
		if st > 0:
			parts.append(Lang.t("⚡ -%d%% estamina") % st)
		for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"]]:
			var v := int(data.get(pair[0], 0))
			if v != 0:
				parts.append("%s +%d" % [pair[1], v])
	else:
		var hp := int(data.get("hpBonusPercent", 0))
		if hp != 0:
			parts.append("HP +%d%%" % hp)
		var dx := int(data.get("dexBonus", 0))
		if dx != 0:
			parts.append("DEX +%d" % dx)
	if not parts.is_empty():
		t += "\n" + "   ".join(parts)
	return t

# Preenche cada slot com o item equipado do seu tipo (ou apagado se vazio). Stats no tooltip.
func _update_slots() -> void:
	var eq := {}
	for it in items:
		if it is Dictionary and bool(it.get("equipped", false)):
			eq[str(it.get("type", ""))] = it
	for type in _slots:
		var s: Dictionary = _slots[type]
		var frame: PanelContainer = s["frame"]
		var icon: TextureRect = s["icon"]
		var sb: StyleBoxFlat = frame.get_theme_stylebox("panel")
		if eq.has(type):
			var it: Dictionary = eq[type]
			s["item_id"] = int(it.get("id", 0))
			icon.modulate = Color(1, 1, 1, 1)
			sb.border_color = UiKit.rarity_color(int(it.get("rarity", 1)))
			sb.set_border_width_all(2)
			frame.tooltip_text = _equipped_tooltip(it)
			# [SLOT_WEAPON_IMG][OUTFITS_CLASSE] mostra a IMAGEM do equipado: arma → modelo 3D;
			# armadura → peça renderizada do TEMA da classe; resto → ícone genérico do slot.
			var tex := _equip_icon_tex(it, type)
			icon.texture = tex if tex != null else Icons.tex("slot_" + type.to_lower())
		else:
			s["item_id"] = 0
			icon.modulate = Color(1, 1, 1, 0.30)
			sb.border_color = UiKit.BRONZE
			sb.set_border_width_all(1)
			frame.tooltip_text = Lang.t(str(SLOT_LABEL.get(type, type)))
			icon.texture = Icons.tex("slot_" + type.to_lower())   # vazio → ícone genérico de volta

func _equipped_tooltip(it: Dictionary) -> String:
	var tip := str(it.get("name", "?"))
	tip += "\n" + Lang.t("%s · Nv %d · %s") % [Lang.t(str(it.get("typeDisplay", it.get("type", "")))), int(it.get("itemLevel", 1)), Lang.t(str(it.get("rarityName", "")))]
	var st := _stats_line(it)
	if st != "":
		tip += "\n" + st
	tip += "\n" + Lang.t("(clique para desequipar)")
	return tip

# [SLOT_WEAPON_IMG] Ícone 2D renderizado do modelo da arma equipada (assets/weapons/icons/<modelo>.png).
# null se não houver render importado → o slot cai no ícone genérico slot_weapon.
func _weapon_icon(it: Dictionary) -> Texture2D:
	var kind := _wp.weapon_kind(str(it.get("name", "")), str(it.get("weaponCategory", "")))
	var model := str(Weapons.MODELS.get(kind, ""))
	if model != "":
		var p := "res://assets/weapons/icons/" + model + ".png"
		if ResourceLoader.exists(p):
			return load(p)
	return null

# [OUTFITS_CLASSE] Texture do equipado p/ o slot: arma → modelo 3D; armadura → peça do tema da classe;
# resto (anel/colar/escudo) → null (cai no ícone genérico do slot).
func _equip_icon_tex(it: Dictionary, type: String) -> Texture2D:
	if type == "WEAPON":
		return _weapon_icon(it)
	if type == "SHIELD":
		var sp := "res://assets/weapons/icons/" + str(Weapons.SHIELD_MODEL) + ".png"
		return load(sp) if ResourceLoader.exists(sp) else null
	if Outfits.is_armor_slot(type):
		var ap := Outfits.icon_path_item(it, type)   # tema do ITEM equipado [OUTFITS_CLASSE]
		if ap != "" and ResourceLoader.exists(ap):
			return load(ap)
	return null

func _slot_clicked(type: String) -> void:
	var s: Dictionary = _slots.get(type, {})
	var id := int(s.get("item_id", 0))
	if id > 0:
		_unequip(id)

# ── Painel da sub-aba ──────────────────────────────────────────────────────────────────
func _render_panel() -> void:
	for c in _panel_host.get_children():
		c.queue_free()
	match sub_tab:
		"attr":
			_render_attr_panel()
		"abil":
			_render_abil_panel()
		_:
			_render_bag_panel()

# 🎒 Mochila ───────────────────────────────────────────────────────────────────────────
func _render_bag_panel() -> void:
	var bag: Array = []
	for it in items:
		if it is Dictionary and not bool(it.get("equipped", false)):
			bag.append(it)
	_panel_host.add_child(UiKit.section(Lang.t("Mochila (%d)") % bag.size()))
	_panel_host.add_child(UiKit.rarity_filter(rarity_filter, _set_rarity))
	if bag.is_empty():
		_panel_host.add_child(UiKit.empty("Mochila vazia", "Vença missões no 🌍 Mundo para conseguir itens"))
		return
	var shown: Array = bag
	if rarity_filter > 0:
		shown = []
		for it in bag:
			if it is Dictionary and int(it.get("rarity", 1)) == rarity_filter:
				shown.append(it)
	if shown.is_empty():
		_panel_host.add_child(UiKit.dim("— nada nessa raridade —"))
		return
	_panel_host.add_child(UiKit.grid(self, shown, _bag_card, true))

func _set_rarity(r) -> void:
	rarity_filter = int(r)
	_render_panel()

func _bag_card(it) -> Control:
	if not (it is Dictionary):
		return null
	var id := int(it.get("id", 0))
	var rar := int(it.get("rarity", 1))
	var res := UiKit.card(UiKit.rarity_color(rar))
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	box.add_child(row)
	var ic := UiKit.item_icon_for(it, 40)   # arma → render do modelo (igual ao slot) [SLOT_WEAPON_IMG]
	if ic:
		row.add_child(ic)
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(left)
	var nm := Label.new()
	nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 14)
	nm.add_theme_color_override("font_color", UiKit.rarity_color(rar))
	left.add_child(nm)
	left.add_child(UiKit.item_subline(it, int(w.get("level", 0))))   # [REQ_LEVEL] Nv vermelho se exige nível acima
	var sline := UiKit.item_stats_line(it)   # [STATS_CMP] stats únicos coloridos vs equipado (1 linha só)
	if sline:
		left.add_child(sline)
	var rcol := VBoxContainer.new()
	rcol.add_theme_constant_override("separation", 6)
	rcol.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(rcol)
	rcol.add_child(UiKit.small_btn("Equipar", _equip.bind(id)))
	if bool(it.get("pvpLocked", false)):
		rcol.add_child(UiKit.small_btn("🔒 PvP", func() -> void: UiKit.flash(status, Lang.t("Item travado no PvP — não dá pra vender enquanto exposto."), 2)))
	else:
		rcol.add_child(UiKit.small_btn(Lang.t("Vender (%s)") % UiKit.coin_str(int(it.get("sellPrice", 0))), _ask_sell.bind(id, str(it.get("name", "?")), rar)))
	return pc

# ⚔ Atributos ──────────────────────────────────────────────────────────────────────────
func _render_attr_panel() -> void:
	# ── Stats de combate efetivos (atk total etc.) ──
	_panel_host.add_child(UiKit.section("Combate"))
	_panel_host.add_child(_combat_stats_grid())
	# ── Atributos (gastar ponto) — compacto ──
	var pts := int(w.get("availablePoints", 0))
	var ttl := Lang.t("Atributos")
	if pts > 0:
		ttl += "  (%d %s)" % [pts, Lang.t("livre") if pts == 1 else Lang.t("livres")]
	_panel_host.add_child(UiKit.section(ttl))
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 2)     # linhas bem juntas (menos espaçado)
	for a in ATTRS:
		col.add_child(_attr_row(a, pts > 0))
	_panel_host.add_child(col)

# Grade 2-col com os stats efetivos + derivados (fórmulas do BattleSimulator). Tooltip explica cada um.
func _combat_stats_grid() -> GridContainer:
	var g := GridContainer.new()
	g.columns = 2
	g.add_theme_constant_override("h_separation", 18)
	g.add_theme_constant_override("v_separation", 4)
	var dex := int(w.get("dexterity", 0))
	var agi := int(w.get("agility", 0))
	var def := int(w.get("combatDefense", w.get("totalDefense", 0)))
	var crit := clampi(5 + int(w.get("luck", 0)) / 2, 5, 35)
	var hit := clampi(50 + dex, 20, 95)                       # acerto vs alvo neutro
	var extra := mini(agi, 75)                                # golpe extra (velocidade) por AGI
	var mit := int(round(def * 100.0 / (100.0 + def)))        # % de dano cortado pela Defesa
	var maxhp := int(w.get("combatHealth", w.get("totalHealth", 0)))
	var curhp := int(round(maxhp * int(w.get("hpPercent", 100)) / 100.0))
	g.add_child(_stat_chip("stat_atk", "Ataque", str(int(w.get("combatAttack", w.get("totalAttack", 0)))), "Dano base por golpe (antes da mitigação do alvo)"))
	g.add_child(_stat_chip("slot_shield", "Defesa", str(def), "Reduz o dano recebido"))
	g.add_child(_stat_chip("hp", "Vida", "%d/%d" % [curhp, maxhp], "Vida atual / máxima"))
	g.add_child(_stat_chip("stat_atk", "Dano de", _dmg_attr(), "De onde vem seu dano: arma corpo-a-corpo = STR, arco = DEX"))
	g.add_child(_stat_chip("attr_dexterity", "Acerto", "%d%%" % hit, "Chance de acertar um alvo neutro (50 + DEX, teto 95%)"))
	g.add_child(_stat_chip("attr_agility", "Golpe extra", "%d%%" % extra, "Velocidade: chance de atacar 2× no round (vem da AGI)"))
	g.add_child(_stat_chip("attr_agility", "Esquiva", "%d%%" % int(w.get("evasionChance", 0)), "Chance de evitar o golpe inimigo"))
	g.add_child(_stat_chip("attr_luck", "Crítico", "%d%%" % crit, "Chance de crítico — ×1,5 de dano e fura a esquiva"))
	g.add_child(_stat_chip("slot_shield", "Mitigação", "%d%%" % mit, "Quanto a Defesa corta do dano recebido"))
	g.add_child(_stat_chip("arena", "Rank", str(int(w.get("rankPoints", 0))), "Pontos de ranking da Arena"))
	return g

# Atributo de dano da arma equipada: arco → DEX, resto (ou sem arma) → STR.
func _dmg_attr() -> String:
	for it in items:
		if it is Dictionary and bool(it.get("equipped", false)) and str(it.get("type", "")) == "WEAPON":
			var n := str(it.get("name", "")).to_lower()
			if "bow" in n or "arco" in n or "crossbow" in n or "besta" in n:
				return "DEX"
			return "STR"
	return "STR"

func _stat_chip(icon_key: String, label: String, value: String, tip := "") -> HBoxContainer:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 6)
	if tip != "":
		h.tooltip_text = Lang.t(tip)
		h.mouse_filter = Control.MOUSE_FILTER_STOP
	if Icons.tex(icon_key) != null:
		h.add_child(Icons.rect(icon_key, 18))
	var k := Label.new()
	k.text = Lang.t(label)
	k.custom_minimum_size = Vector2(74, 0)
	k.add_theme_font_size_override("font_size", 12)
	k.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	k.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(k)
	var v := Label.new()
	v.text = value
	v.add_theme_font_size_override("font_size", 14)
	v.add_theme_color_override("font_color", UiKit.GOLD)
	v.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(v)
	return h

# Linha compacta: [ícone] [sigla] [valor] [o que aumenta] [botão de cura/atribuir].
func _attr_row(a: Array, can_add: bool) -> Control:
	var key := str(a[0])
	var sig := str(a[1])
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	row.add_child(Icons.rect("attr_" + key, 20))
	var nm := Label.new()
	nm.text = sig
	nm.custom_minimum_size = Vector2(40, 0)
	nm.add_theme_font_size_override("font_size", 13)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	row.add_child(nm)
	var val := Label.new()
	val.text = str(int(w.get(key, 0)))
	val.custom_minimum_size = Vector2(30, 0)
	val.add_theme_font_size_override("font_size", 14)
	val.add_theme_color_override("font_color", UiKit.GOLD)
	row.add_child(val)
	var eff := Label.new()
	eff.text = _attr_gain(key, sig)                     # ganho total inline
	eff.custom_minimum_size = Vector2(178, 0)           # largura fixa → o botão fica perto
	eff.add_theme_font_size_override("font_size", 11)
	eff.add_theme_color_override("font_color", UiKit.OK)
	eff.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	row.add_child(eff)
	if can_add:
		row.add_child(_attr_add_btn(key, sig))
	return row

# Botão de ATRIBUIR ponto = o ícone de cura (cruz) reaproveitado. Fallback: botão de pedra "+".
func _attr_add_btn(key: String, sig: String) -> Control:
	var t := Icons.tex("heal")
	if t != null:
		var b := TextureButton.new()
		b.texture_normal = t
		b.ignore_texture_size = true
		b.stretch_mode = TextureButton.STRETCH_KEEP_ASPECT_CENTERED
		b.custom_minimum_size = Vector2(32, 32)
		b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		b.modulate = Color(1, 1, 1, 0.9)
		b.tooltip_text = Lang.t("Atribuir 1 ponto em %s") % sig
		b.mouse_entered.connect(func() -> void: b.modulate = Color(1, 1, 1, 1))
		b.mouse_exited.connect(func() -> void: b.modulate = Color(1, 1, 1, 0.9))
		b.pressed.connect(func() -> void: await _spend(key))
		return b
	var fb := UiKit.icon_btn("+", func() -> void: await _spend(key))
	fb.custom_minimum_size = Vector2(32, 32)
	fb.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	return fb

# Contribuição TOTAL do valor ATUAL do atributo (não por ponto). Fórmulas do backend committado = prod.
# Ex.: DEX 15 → "+15% acerto · +15 atq (arco)". CON tem soft-cap (8/4/2 por faixa). [REBALANCE v2]
func _attr_gain(key: String, sig: String) -> String:
	var v := int(w.get(key, 0))
	match sig:
		"STR":
			return Lang.t("+%d de ataque") % v
		"CON":
			var t1 := mini(v, 40) * 8
			var t2 := clampi(v - 40, 0, 40) * 4
			var t3 := maxi(v - 80, 0) * 2
			return Lang.t("+%d de vida") % (t1 + t2 + t3)
		"DEX":
			return Lang.t("+%d%% acerto · +%d atq (arco)") % [v, v]
		"AGI":
			return Lang.t("+%d%% golpe · +%d%% esquiva") % [v, (v * 3) / 5]
		"LUK":
			return Lang.t("+%d%% de crítico") % mini(v / 2, 30)
	return ""

# ✨ Habilidades ────────────────────────────────────────────────────────────────────────
func _render_abil_panel() -> void:
	var pts := int(abilities_data.get("abilityPoints", 0))
	var abilities: Array = abilities_data.get("abilities", []) if abilities_data.get("abilities") is Array else []
	if abilities.is_empty():
		_panel_host.add_child(UiKit.section("Habilidades"))
		var pts_txt := (Lang.t("%d ponto") if pts == 1 else Lang.t("%d pontos")) % pts
		_panel_host.add_child(UiKit.empty(
			Lang.t("Você tem %s de habilidade guardado.") % pts_txt,
			"Escolha uma classe (Path Trial no Nv.10) para destravar as habilidades dela."))
		return
	_panel_host.add_child(UiKit.section(Lang.t("Habilidades — %s") % Lang.t(str(abilities_data.get("class", "?")))))
	if pts > 0:
		var pl := Label.new()
		pl.text = Lang.t("⬆ %s para gastar") % ((Lang.t("%d ponto") if pts == 1 else Lang.t("%d pontos")) % pts)
		pl.add_theme_font_size_override("font_size", 14)
		pl.add_theme_color_override("font_color", UiKit.GOLD)
		_panel_host.add_child(pl)
	_panel_host.add_child(UiKit.grid(self, abilities, func(a): return _ability_card(a, pts) if a is Dictionary else null, true))
	_panel_host.add_child(UiKit.spacer(6))
	_panel_host.add_child(UiKit.action_danger(Lang.t("🔄 Resetar habilidades (%s)") % UiKit.coin_str(int(abilities_data.get("respecCost", 0))), _respec))

func _ability_card(a: Dictionary, pts: int) -> PanelContainer:
	var active := bool(a.get("active", false))
	var level := int(a.get("level", 0))
	var max_level := int(a.get("maxLevel", 0))
	var maxed := level >= max_level
	var col: Color = Color(0.48, 0.69, 1.0) if active else Color(0.6, 0.8, 0.6)
	var res := UiKit.card(col)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new()
	hb.add_theme_constant_override("separation", 10)
	box.add_child(hb)
	var icon_key := "skill_" + str(a.get("id", "")).to_lower()   # ícone PixelLab único da skill
	if Icons.tex(icon_key) != null:
		var ir := Icons.rect(icon_key, 32)
		ir.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		hb.add_child(ir)
	else:                                                         # fallback: emoji do backend
		var icon := Label.new()
		icon.text = str(a.get("icon", "•"))
		icon.custom_minimum_size = Vector2(26, 0)
		icon.add_theme_font_size_override("font_size", 18)
		hb.add_child(icon)
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	nm.text = str(a.get("displayName", "?"))
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", col)
	left.add_child(nm)
	var kind_txt := ""
	if active:
		kind_txt = "⚡ Ativa"
		var cd := int(a.get("cooldown", 0))
		if cd > 0:
			kind_txt += Lang.t(" · CD %d rounds") % cd
	else:
		kind_txt = "🪨 Passiva"
	left.add_child(UiKit.dim(kind_txt))
	var desc := str(a.get("description", ""))
	if desc != "":
		left.add_child(UiKit.dim(desc))
	hb.add_child(left)
	var right := VBoxContainer.new()
	right.add_theme_constant_override("separation", 4)
	right.alignment = BoxContainer.ALIGNMENT_CENTER
	right.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var lvl := Label.new()
	lvl.text = "%d/%d" % [level, max_level]
	lvl.add_theme_font_size_override("font_size", 14)
	lvl.add_theme_color_override("font_color", UiKit.GOLD if maxed else UiKit.TEXT)
	lvl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	right.add_child(lvl)
	var learn := UiKit.small_btn("✖ No máx" if maxed else "+ Aprender", _learn.bind(str(a.get("id", ""))))
	learn.disabled = pts <= 0 or maxed
	right.add_child(learn)
	hb.add_child(right)
	return pc

# ── Ações (cache local em memória; em falha re-sincroniza) ─────────────────────────────
func _equip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.equip_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var updated: Dictionary = r["json"]
		for it in items:   # auto-desequipa o item antigo do MESMO slot
			if it is Dictionary and str(it.get("type")) == str(updated.get("type")) and int(it.get("id", -1)) != int(updated.get("id", -2)):
				it["equipped"] = false
		_replace_item(updated)
		await _after_equip_change()
	else:
		await _refresh()                # refresh limpa o status → mostrar o erro DEPOIS (senão some) [REQ_LEVEL]
		UiKit.show_error(status, r)

func _unequip(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.unequip_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		_replace_item(r["json"])
		await _after_equip_change()
	else:
		await _refresh()                # refresh limpa o status → mostrar o erro DEPOIS (senão some) [REQ_LEVEL]
		UiKit.show_error(status, r)

# Equip mudou: re-veste o boneco + slots + painel + avisa o Shell (busto da topbar + índice) e
# re-busca o warrior p/ os stats EFETIVOS da topbar (ATK/DEF/HP mudam com o gear).
func _after_equip_change() -> void:
	UiKit.set_equipped(items)
	if doll != null and is_instance_valid(doll):
		doll.apply(items, UiKit.current_class)
	_update_slots()
	_render_panel()
	if UiKit.equip_changed_sink.is_valid():
		UiKit.equip_changed_sink.call(items)
	var wr = await Api.get_warrior()
	if wr.get("ok") and wr.get("json") is Dictionary:
		w = wr["json"]
		UiKit.set_wallet(wallet, w)

func _ask_sell(id: int, name_text: String, rarity: int) -> void:
	if rarity >= 3:
		UiKit.confirm(self, Lang.t("Vender %s?") % name_text, Lang.t("Vender"), func() -> void: await _sell(id))
	else:
		await _sell(id)

func _sell(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.sell_item(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		items = items.filter(func(it): return not (it is Dictionary) or int(it.get("id", -1)) != id)
		_render_panel()
		UiKit.flash(status, str(r["json"].get("message", Lang.t("Vendido!"))), 1)
	else:
		await _refresh()                # refresh limpa o status → mostrar o erro DEPOIS (senão some) [REQ_LEVEL]
		UiKit.show_error(status, r)

func _spend(key: String) -> void:
	if busy: return
	busy = true
	var r = await Api.spend_attribute(key.to_upper())
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		w = r["json"]
		UiKit.set_wallet(wallet, w)   # topbar reflete o novo atributo/stat
		_render_panel()
		UiKit.flash(status, "Ponto aplicado", 1)
	else:
		UiKit.show_error(status, r)

func _learn(id: String) -> void:
	if busy or id == "": return
	busy = true
	await _do(await Api.ability_learn(id), Lang.t("Aprimorado!"))
	busy = false

func _respec() -> void:
	UiKit.confirm(self,
		Lang.t("Resetar todas as habilidades por %s? Os pontos voltam para você.") % UiKit.coin_str(int(abilities_data.get("respecCost", 0))),
		"Resetar",
		func() -> void: await _do_respec())

func _do_respec() -> void:
	if busy: return
	busy = true
	await _do(await Api.ability_respec(), "Habilidades resetadas.")
	busy = false

# r = resultado JÁ resolvido; re-sincroniza habilidades + warrior (pontos/stat na topbar) e mostra feedback.
func _do(r, default_msg: String) -> void:
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary:
		var msg := str(r["json"].get("message", default_msg))
		var rs = await Api.batch_get(["/api/abilities", "/api/warrior"])
		var ar = rs[0]
		if ar.get("ok") and ar.get("json") is Dictionary:
			abilities_data = ar["json"]
		var wr = rs[1]
		if wr.get("ok") and wr.get("json") is Dictionary:
			w = wr["json"]
			UiKit.set_wallet(wallet, w)
		_render_panel()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# ── helpers ────────────────────────────────────────────────────────────────────────────
func _replace_item(updated: Dictionary) -> void:
	var uid := int(updated.get("id", -1))
	for i in items.size():
		if items[i] is Dictionary and int(items[i].get("id", -2)) == uid:
			items[i] = updated
			return
	items.append(updated)   # segurança: item que não estava no cache local

func _stats_line(it: Dictionary) -> String:
	var parts: Array = []
	for pair in [["attackBonus", "ATK"], ["defenseBonus", "DEF"], ["healthBonus", "HP"], ["strBonus", "STR"], ["dexBonus", "DEX"], ["lukBonus", "LUK"]]:
		var v := int(it.get(pair[0], 0))
		if v != 0:
			parts.append("%s %+d" % [pair[1], v])
	return "   ".join(parts)
