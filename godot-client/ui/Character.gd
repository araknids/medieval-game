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
const BAG_ROWS_MAX_H := 156.0    # altura de ~3 linhas de card compacto (≈42px) → trava o inner scroll da Mochila
const RES_ROWS_MAX_H := 104.0    # altura de ~3 linhas de chip de recurso → trava o inner scroll dos Recursos
# Ganho EXATO por ponto (números do backend committado = prod). CON tem soft-cap (8→4→2) por faixa,
# então é calculado em _attr_gain a partir da CON atual. [REBALANCE v2]

var w: Dictionary = {}
var items: Array = []
var resources: Array = []     # recursos de coleta (GET /api/gathering/resources) → seção na Mochila
var abilities_data: Dictionary = {}
var postures: Array = []      # [POSTURE] /api/warrior/postures (lista fixa {id,displayName,atkMult,defMult})
var sub_tab := "bag"          # "bag" | "attr" | "abil" | "ach"
var rarity_filter := 0
var bag_sort := "level"       # [INV_COMPACTO] ordenação da mochila: level|rarity|type|upgrade|value
var ach_data: Dictionary = {}   # [MENUBAR_REORG] /api/achievements (catálogo + título ativo) — Conquistas virou sub-aba
var ach_filter := "all"
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
var _resources_host: VBoxContainer  # [RECURSOS] seção fixa de recursos, abaixo de tudo (fora do scroll)

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
	outer.size_flags_vertical = Control.SIZE_SHRINK_BEGIN   # alinha o topo (boneco) ao nível das sub-abas
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	outer.add_child(row)
	row.add_child(_slot_column(LEFT_SLOTS))
	doll = Doll.new()
	doll.custom_minimum_size = Vector2(230, 312)   # [FICHA] menor que antes (350) → sobe o nick + slots de montaria/pet
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
	# ícone PixelLab "mount"/"pet" se importado; senão cai no emoji 🐎/🐾 [ICONES_RARIDADE]
	var itex := Icons.tex(kind)
	if itex != null:
		var ir := TextureRect.new()
		ir.texture = itex
		ir.custom_minimum_size = Vector2(48, 48)
		ir.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		ir.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		ir.mouse_filter = Control.MOUSE_FILTER_IGNORE
		pc.add_child(ir)
		_companions[kind] = {"frame": pc, "icon": ir}
	else:
		var emoji := Label.new()
		emoji.add_theme_font_size_override("font_size", 28)
		emoji.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		emoji.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
		emoji.mouse_filter = Control.MOUSE_FILTER_IGNORE
		pc.add_child(emoji)
		_companions[kind] = {"frame": pc, "emoji": emoji}
	vb.add_child(pc)
	var cap := Label.new()
	cap.text = Lang.t(caption)
	cap.add_theme_font_size_override("font_size", 11)
	cap.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	cap.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(cap)
	return vb

func _slot_column(types: Array) -> VBoxContainer:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 6)
	col.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	for t in types:
		col.add_child(_slot_frame(str(t)))
	return col

func _slot_frame(type: String) -> PanelContainer:
	var pc := ItemTooltipCard.new()        # [ITEM_TOOLTIP] tooltip rico quando há item equipado
	pc.equipped_slot = true
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
	icon.texture = Icons.item_tex(type)
	icon.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	icon.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	icon.custom_minimum_size = Vector2(44, 44)
	icon.mouse_filter = Control.MOUSE_FILTER_IGNORE
	icon.modulate = Color(1, 1, 1, 0.30)   # vazio = apagado
	pc.add_child(icon)
	pc.tooltip_text = Lang.t(str(SLOT_LABEL.get(type, type)))
	# [SEM_UNEQUIP_CLICK] clicar no slot NÃO desequipa mais (a pedido) — troca-se equipando outro item.
	_slots[type] = {"frame": pc, "icon": icon, "item_id": 0}
	return pc

func _build_right() -> Control:
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 8)
	col.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_subtab_bar_host = VBoxContainer.new()
	col.add_child(_subtab_bar_host)
	# Painel da sub-aba: cresce com o conteúdo até ~360 e então rola (capped_scroll). Assim a Mochila
	# (com a grid já travada em 3 linhas) NÃO reserva 330 fixos → os Recursos sobem logo abaixo.
	_panel_host = VBoxContainer.new()
	_panel_host.add_theme_constant_override("separation", 8)
	_panel_host.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	col.add_child(UiKit.capped_scroll(_panel_host, 360.0))
	# [RECURSOS] seção embaixo da lista → fica na altura da Montaria/Pet, sem alongar a tela
	_resources_host = VBoxContainer.new()
	_resources_host.add_theme_constant_override("separation", 6)
	_resources_host.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	col.add_child(_resources_host)
	return col

func _build_subtab_bar() -> void:
	for c in _subtab_bar_host.get_children():
		c.queue_free()
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 6)
	for t in [["bag", "tab_bag", "Mochila", "🎒"], ["attr", "tab_attributes", "Atributos", "⚔"], ["abil", "tab_abilities", "Habilidades", "✨"], ["ach", "achievements", "Conquistas", "🏆"]]:
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
	if _subtab_alert(value):   # [PONTOS] "!" vermelho quando há ponto não gasto na sub-aba
		b.add_child(_make_subtab_alert())
	return b

# [PONTOS] A sub-aba tem ponto não gasto? attr = ponto de atributo (sempre); abil = ponto de habilidade,
# mas SÓ depois de escolher a classe (Recruta acumula mas não pode gastar). [HABILIDADES][CLASSES]
func _subtab_alert(value: String) -> bool:
	if value == "attr":
		return int(w.get("availablePoints", 0)) > 0
	if value == "abil":
		return int(w.get("abilityPoints", 0)) > 0 and str(w.get("warriorClassId", "")) != "RECRUIT"
	return false

# "!" vermelho no canto superior direito do botão de sub-aba (ícone PixelLab; fallback no Label "!").
func _make_subtab_alert() -> Control:
	var node: Control
	if Icons.tex("quest_alert_red") != null:
		var tr := TextureRect.new()
		tr.texture = Icons.tex("quest_alert_red")
		tr.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		tr.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		node = tr
	else:
		var l := Label.new(); l.text = "!"
		l.add_theme_color_override("font_color", UiKit.ERR)
		l.add_theme_font_size_override("font_size", 14)
		node = l
	node.mouse_filter = Control.MOUSE_FILTER_IGNORE
	node.anchor_left = 1.0; node.anchor_right = 1.0
	node.anchor_top = 0.0; node.anchor_bottom = 0.0
	node.offset_left = -21; node.offset_top = -3   # [TAMANHO] "!" ~40% maior (13→19px) e mais visível
	node.offset_right = -2; node.offset_bottom = 16
	return node

func _set_tab(t) -> void:
	sub_tab = str(t)
	_build_subtab_bar()
	_render_panel()
	_render_resources()   # [RECURSOS] atualiza a seção na troca de aba (só aparece na Mochila)

# ── Dados ────────────────────────────────────────────────────────────────────────────
func _refresh() -> void:
	UiKit.show_loading(self)
	var rs = await Api.batch_get(["/api/warrior", "/api/inventory", "/api/abilities", "/api/gathering/resources", "/api/achievements", "/api/warrior/postures"])
	var wr = rs[0]
	if not (wr.get("ok") and wr.get("json") is Dictionary):
		UiKit.show_error(status, wr)
		return
	w = wr["json"]
	var pr2 = rs[5]   # [POSTURE] lista fixa de posturas (id/displayName/atkMult/defMult)
	if pr2.get("ok") and pr2.get("json") is Array:
		postures = pr2["json"]
	var ir = rs[1]
	items = ir["json"] if (ir.get("ok") and ir.get("json") is Array) else []
	var ar = rs[2]
	abilities_data = ar["json"] if (ar.get("ok") and ar.get("json") is Dictionary) else {}
	var rr = rs[3]   # recursos de coleta (minério/peixe/essência/núcleo…) p/ a seção na Mochila
	resources = rr["json"] if (rr.get("ok") and rr.get("json") is Array) else []
	var achr = rs[4]   # [MENUBAR_REORG] conquistas (sub-aba Conquistas)
	ach_data = achr["json"] if (achr.get("ok") and achr.get("json") is Dictionary) else {}
	_apply()

# [AUDIT] Refresh PÓS-AÇÃO enxuto: re-baixa só os endpoints que o `paths` pede, preenchendo os MESMOS
# membros que o _refresh preencheria (w/items/abilities_data/resources). Os membros NÃO pedidos guardam
# o valor em cache (a ação não os altera). Re-renderiza por _apply() (mesmo caminho do _refresh).
# Endpoints aceitos: /api/warrior, /api/inventory, /api/abilities, /api/gathering/resources.
func _refresh_subset(paths: Array) -> void:
	var rs = await Api.batch_get(paths)
	for i in paths.size():
		var path := str(paths[i])
		var res = rs[i]
		match path:
			"/api/warrior":
				if res.get("ok") and res.get("json") is Dictionary:
					w = res["json"]
			"/api/inventory":
				if res.get("ok") and res.get("json") is Array:
					items = res["json"]
			"/api/abilities":
				if res.get("ok") and res.get("json") is Dictionary:
					abilities_data = res["json"]
			"/api/gathering/resources":
				if res.get("ok") and res.get("json") is Array:
					resources = res["json"]
	_apply()

func _apply() -> void:
	UiKit.hide_loading()
	UiKit.current_class = str(w.get("warriorClassId", UiKit.current_class))   # tema das roupas (slot + ícone + doll) [OUTFITS_CLASSE]
	UiKit.current_gender = str(w.get("gender", UiKit.current_gender)).to_lower()   # base/peças Male/Female [OUTFITS_FEMALE]
	UiKit.set_wallet(wallet, w)        # alimenta a topbar (HP/estamina/stats/moedas)
	UiKit.set_equipped(items)
	if doll != null and is_instance_valid(doll):
		doll.apply(items, UiKit.current_class, UiKit.current_gender)
	var title := str(w.get("title", ""))
	_id_name.text = (title + "  " if title != "" else "") + str(w.get("name", "?"))
	_id_sub.text = Lang.t("%s · Nível %d") % [Lang.t(str(w.get("warriorClass", "Recruta"))), int(w.get("level", 1))]
	if bool(w.get("isKnockedOut", false)):
		_id_sub.text += "   💀"
	_update_slots()
	_update_companions()
	_build_subtab_bar()
	_render_panel()
	_render_resources()   # [RECURSOS] seção fixa embaixo (independe da sub-aba)

# Preenche os slots de montaria/pet a partir do warrior (equippedMount/equippedPet). [COMPANION_SLOTS]
func _update_companions() -> void:
	_fill_companion("mount", w.get("equippedMount"), "🐎", "Montaria")
	_fill_companion("pet", w.get("equippedPet"), "🐾", "Pet")

func _fill_companion(kind: String, data, generic_emoji: String, label: String) -> void:
	var c: Dictionary = _companions.get(kind, {})
	if c.is_empty():
		return
	var pc: PanelContainer = c["frame"]
	var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
	var equipped := data is Dictionary
	if c.has("icon"):   # ícone PixelLab: opaco se equipado, apagado se vazio
		(c["icon"] as TextureRect).modulate = Color(1, 1, 1, 1.0 if equipped else 0.32)
	elif c.has("emoji"):
		var emoji: Label = c["emoji"]
		emoji.text = str(data.get("icon", generic_emoji)) if equipped else generic_emoji
		emoji.modulate = Color(1, 1, 1, 1.0 if equipped else 0.30)
	if equipped:
		sb.border_color = UiKit.GOLD
		sb.set_border_width_all(2)
		pc.tooltip_text = _companion_tooltip(kind, data, label)
	else:
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
			(frame as ItemTooltipCard).item = it   # [ITEM_TOOLTIP] tooltip rico do equipado
			frame.tooltip_text = " "
			# [SLOT_WEAPON_IMG][OUTFITS_CLASSE] mostra a IMAGEM do equipado: arma → modelo 3D;
			# armadura → peça renderizada do TEMA da classe; resto → ícone genérico do slot.
			var tex := _equip_icon_tex(it, type)
			icon.texture = tex if tex != null else Icons.item_tex(type)
		else:
			s["item_id"] = 0
			icon.modulate = Color(1, 1, 1, 0.30)
			sb.border_color = UiKit.BRONZE
			sb.set_border_width_all(1)
			(frame as ItemTooltipCard).item = {}   # vazio → cai no tooltip_text (rótulo do slot)
			frame.tooltip_text = Lang.t(str(SLOT_LABEL.get(type, type)))
			icon.texture = Icons.item_tex(type)   # vazio → ícone genérico de volta

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
# Ícone do item EQUIPADO no slot = MESMA fonte da bag (UiKit.item_icon_tex) → sempre relacionados.
func _equip_icon_tex(it: Dictionary, _type: String) -> Texture2D:
	return UiKit.item_icon_tex(it)

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
		"ach":
			_render_ach_panel()
		_:
			_render_bag_panel()

# 🎒 Mochila ───────────────────────────────────────────────────────────────────────────
func _render_bag_panel() -> void:
	var bag: Array = []
	for it in items:
		if it is Dictionary and not bool(it.get("equipped", false)):
			bag.append(it)
	bag.sort_custom(_bag_sort)   # honra `bag_sort` (level/rarity/type/upgrade/value)
	_panel_host.add_child(UiKit.section(Lang.t("Mochila (%d)") % bag.size()))
	_panel_host.add_child(UiKit.rarity_filter(rarity_filter, _set_rarity))
	_panel_host.add_child(_sort_row())   # [INV_COMPACTO] chips de ordenação
	if bag.is_empty():
		_panel_host.add_child(UiKit.empty("Mochila vazia", "Vença missões no Mundo para conseguir itens"))
	else:
		var shown: Array = bag
		if rarity_filter > 0:
			shown = []
			for it in bag:
				if it is Dictionary and int(it.get("rarity", 1)) == rarity_filter:
					shown.append(it)
		if shown.is_empty():
			_panel_host.add_child(UiKit.dim("— nada nessa raridade —"))
		else:
			# [INV_COMPACTO] slots enxutos (1 linha) → cabem 3 por linha; detalhe vai pro hover/popup
			# Trava em ~3 linhas: passou disso, rola SÓ a grid (inner scroll), sem alongar a aba.
			var grid := UiKit.grid(self, shown, _bag_card, true, 188.0, 3)
			_panel_host.add_child(UiKit.capped_scroll(grid, BAG_ROWS_MAX_H))

# [INV_COMPACTO] Linha de chips de ordenação da mochila (reusa filter_row).
func _sort_row() -> Control:
	return UiKit.filter_row([
		{"label": Lang.t("Nível"), "value": "level"},
		{"label": Lang.t("Raridade"), "value": "rarity"},
		{"label": Lang.t("Tipo"), "value": "type"},
		{"label": Lang.t("Melhoria"), "value": "upgrade"},
		{"label": Lang.t("Valor"), "value": "value"},
	], bag_sort, _set_sort)

func _set_sort(mode) -> void:
	bag_sort = str(mode)
	_render_panel()

# 🏆 Conquistas (sub-aba) — portada da antiga tela Achievements [MENUBAR_REORG]. Renderiza no _panel_host
# (grids em 2 col, mais estreito que a tela cheia). Dados em ach_data (/api/achievements no batch).
func _render_ach_panel() -> void:
	var all: Array = ach_data.get("achievements", []) if ach_data.get("achievements") is Array else []
	var active := str(ach_data.get("activeTitle", ""))
	var unlocked: Array = []
	for a in all:
		if a is Dictionary and bool(a.get("unlocked", false)):
			unlocked.append(a)
	_panel_host.add_child(UiKit.section(Lang.t("Conquistas & Títulos   (%d/%d)") % [unlocked.size(), all.size()]))
	var hint := Label.new()
	hint.text = Lang.t("Escolha um título para exibir antes do seu nome (todos veem):")
	hint.add_theme_color_override("font_color", UiKit.GOLD_SOFT)
	hint.add_theme_font_size_override("font_size", 12)
	hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_panel_host.add_child(hint)
	if unlocked.is_empty():
		_panel_host.add_child(UiKit.empty("Nenhum título ainda", "Desbloqueie conquistas abaixo para ganhar títulos."))
	else:
		# [TITULOS] radio compacto: escolhe UM título ativo (Nenhum + os desbloqueados). active vem como
		# TEXTO do título → mapeio p/ o id (enum) que o _select_title espera.
		var opts: Array = [{"label": Lang.t("Nenhum"), "value": ""}]
		var active_id := ""
		for a in unlocked:
			var aid := str(a.get("id", ""))
			opts.append({"label": str(a.get("title", "")), "value": aid})
			if str(a.get("title", "")) == active:
				active_id = aid
		_panel_host.add_child(UiKit.filter_row(opts, active_id, _select_title))
	_panel_host.add_child(UiKit.section("Catálogo"))
	_panel_host.add_child(UiKit.filter_row([
		{"label": "Todas", "value": "all", "color": UiKit.GOLD},
		{"label": "Desbloqueadas", "value": "unlocked", "color": UiKit.OK},
		{"label": "Bloqueadas", "value": "locked", "color": UiKit.TEXT_DIM},
	], ach_filter, _set_ach_filter))
	var cats: Array = []
	var by_cat: Dictionary = {}
	for a in all:
		if not (a is Dictionary): continue
		if not _passes_ach_filter(a): continue
		var cat := str(a.get("category", "—"))
		if not by_cat.has(cat):
			by_cat[cat] = []; cats.append(cat)
		by_cat[cat].append(a)
	if cats.is_empty():
		_panel_host.add_child(UiKit.dim("— nenhuma conquista neste filtro —"))
	for cat in cats:
		_panel_host.add_child(UiKit.section(cat))
		_panel_host.add_child(UiKit.grid(self, by_cat[cat], _ach_card, true, 200.0, 2))

func _passes_ach_filter(a: Dictionary) -> bool:
	if ach_filter == "unlocked":
		return bool(a.get("unlocked", false))
	if ach_filter == "locked":
		return not bool(a.get("unlocked", false))
	return true

func _set_ach_filter(v) -> void:
	ach_filter = str(v)
	_render_panel()

# [TITULOS] Linha COMPACTA (1 linha): ícone + nome "título" + X/Y; descrição + progresso vão pro
# HOVER (tooltip). Antes era um card alto (ícone+nome+descrição+barra) que estourava a tela.
func _ach_card(a: Dictionary) -> PanelContainer:
	var unlocked := bool(a.get("unlocked", false))
	var res := UiKit.card(UiKit.GOLD_SOFT if unlocked else Color(0.3, 0.3, 0.34, 0.6), unlocked)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(7)
	# hover = detalhe completo (descrição + progresso) — fora da linha
	var desc := str(a.get("description", ""))
	var threshold := int(a.get("threshold", 0))
	var cur := int(a.get("current", 0))
	var tip := str(a.get("displayName", "?"))
	if desc != "": tip += "\n" + desc
	if not unlocked and threshold > 0: tip += "\n" + Lang.t("Progresso: %d/%d") % [cur, threshold]
	pc.tooltip_text = tip
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 8)
	box.add_child(hb)
	var ikey := "achievements" if unlocked else "locked"
	if Icons.tex(ikey) != null:
		hb.add_child(Icons.rect(ikey, 18))
	var nm := Label.new()
	nm.text = "%s  “%s”" % [str(a.get("displayName", "?")), str(a.get("title", ""))]
	nm.add_theme_font_size_override("font_size", 13)
	nm.add_theme_color_override("font_color", UiKit.TEXT if unlocked else UiKit.TEXT_DIM)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	nm.clip_text = true
	nm.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	hb.add_child(nm)
	# desbloqueada: o ícone/cor dourada já marca; bloqueada: mostra X/Y (detalhe no hover)
	if not unlocked:
		var val := Label.new()
		val.text = "%d/%d" % [cur, threshold]
		val.add_theme_font_size_override("font_size", 12)
		val.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		val.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		hb.add_child(val)
	return pc

func _select_title(id: String) -> void:
	if busy: return
	busy = true
	var r = await Api.select_title(id)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		ach_data["activeTitle"] = str(r["json"].get("activeTitle", ""))
		UiKit.flash(status, Lang.t("Título atualizado."), 1)
		_render_panel()
	else:
		UiKit.show_error(status, r)

# "Poder" do item = soma dos stats (HP pesa menos por ser número grande). Empate por raridade no _bag_sort.
func _item_power(it: Dictionary) -> int:
	return int(it.get("attackBonus", 0)) + int(it.get("defenseBonus", 0)) \
		+ int(it.get("strBonus", 0)) + int(it.get("dexBonus", 0)) + int(it.get("lukBonus", 0)) \
		+ int(round(int(it.get("healthBonus", 0)) * 0.3))

# Ordena a bag: nível do item ↓, depois poder ↓, depois raridade ↓ (melhor gear no topo).
# [INV_COMPACTO] Comparador da mochila — honra `bag_sort`. Cada modo cai no desempate por nível↓+poder↓
# (ordem estável e útil) quando a chave primária empata.
func _bag_sort(a, b) -> bool:
	if not (a is Dictionary) or not (b is Dictionary):
		return false
	match bag_sort:
		"rarity":
			var ra := int(a.get("rarity", 1)); var rb := int(b.get("rarity", 1))
			if ra != rb: return ra > rb
		"type":
			var ta := str(a.get("type", "")); var tb := str(b.get("type", ""))
			if ta != tb: return ta < tb
		"value":
			var va := int(a.get("sellPrice", 0)); var vb := int(b.get("sellPrice", 0))
			if va != vb: return va > vb
		"upgrade":
			var ua := _upgrade_score(a); var ub := _upgrade_score(b)
			if ua != ub: return ua > ub
		_:   # "level" (padrão)
			pass
	var la := int(a.get("itemLevel", 1))
	var lb := int(b.get("itemLevel", 1))
	if la != lb:
		return la > lb
	var pa := _item_power(a)
	var pb := _item_power(b)
	if pa != pb:
		return pa > pb
	return int(a.get("rarity", 1)) > int(b.get("rarity", 1))

# [INV_COMPACTO] "Quão melhor que o equipado" (soma dos deltas vs a peça do mesmo slot). 0 quando não há
# o que comparar (sem peça equipada no slot) → fica no meio, entre upgrades (+) e downgrades (−).
func _upgrade_score(it: Dictionary) -> int:
	var t := str(it.get("type", ""))
	if not UiKit.equipped.has(t):
		return 0
	var cur: Dictionary = UiKit.equipped[t]
	if int(cur.get("id", -1)) == int(it.get("id", -2)):
		return 0
	var total := 0
	for k in ["attackBonus", "defenseBonus", "healthBonus", "strBonus", "dexBonus", "lukBonus"]:
		total += int(it.get(k, 0)) - int(cur.get(k, 0))
	return total

# [RECURSOS] Seção PRÓPRIA, fixa abaixo de tudo (não rola com os itens) — chips [📦 nome ×qtd] em flow.
func _render_resources() -> void:
	if _resources_host == null:
		return
	for c in _resources_host.get_children():
		c.queue_free()
	# [RECURSOS] Recursos SÓ na Mochila — nas outras sub-abas (Atributos/Habilidades/Conquistas) a seção
	# fica vazia, pra não esticar a página e criar scroll geral.
	if sub_tab != "bag":
		return
	var res: Array = []
	for r in resources:
		if r is Dictionary and int(r.get("quantity", 0)) > 0:
			res.append(r)
	_resources_host.add_child(UiKit.section(Lang.t("Recursos (%d)") % res.size()))
	if res.is_empty():
		_resources_host.add_child(UiKit.dim("— nenhum recurso —"))
		return
	var flow := HFlowContainer.new()
	flow.add_theme_constant_override("h_separation", 8)
	flow.add_theme_constant_override("v_separation", 6)
	flow.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for r in res:
		flow.add_child(_res_chip(r))
	# Muitos recursos → rola SÓ os chips (inner scroll), sem empurrar a aba pra baixo.
	_resources_host.add_child(UiKit.capped_scroll(flow, RES_ROWS_MAX_H))

func _res_chip(r: Dictionary) -> Control:
	var rtype := str(r.get("type", ""))
	var category := str(r.get("category", ""))
	var pc := PanelContainer.new()
	# [RECURSOS] hover explica p/ que serve o recurso (e quanto o peixe restaura)
	pc.tooltip_text = _res_use_text(r)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.08, 0.07, 0.09, 0.95)
	sb.set_border_width_all(1)
	sb.border_color = UiKit.BRONZE
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(6)
	pc.add_theme_stylebox_override("panel", sb)
	var hb := HBoxContainer.new()
	hb.add_theme_constant_override("separation", 6)
	hb.mouse_filter = Control.MOUSE_FILTER_IGNORE   # hover cai no pc (mostra o tooltip); botões ainda clicam
	pc.add_child(hb)
	var qty := int(r.get("quantity", 0))
	# [RECURSOS_GIF] ícone próprio do recurso (res_<tipo>), que ANIMA no hover do chip se houver
	# anim/res_<tipo>/ (ex.: minério na picareta, peixe nadando, gema brilhando). Sem ícone próprio →
	# cai no genérico `package`. O 📦 de texto saiu (sem emoji de web). [piloto: minério/peixe/gema]
	var key := "res_" + rtype.to_lower()
	var icon := _res_icon(key)
	if icon != null:
		Icons.anim_rect(pc, icon, key)   # hover no chip TODO → cicla a GIF (no-op se não houver anim/)
		hb.add_child(icon)
	var lbl := Label.new()
	lbl.text = "%s ×%d" % [str(r.get("displayName", rtype)), qty]
	lbl.add_theme_font_size_override("font_size", 13)
	lbl.add_theme_color_override("font_color", UiKit.TEXT)
	lbl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	hb.add_child(lbl)
	# [RECURSOS] peixe → botão de CONSUMIR (peixe de estamina dá SÓ estamina; peixe de vida dá SÓ vida)
	if category == "FISH":
		var ch := int(r.get("consumeHp", 0))
		var ctip := (Lang.t("Consumir: +%d%% de vida") % ch) if ch > 0 else (Lang.t("Consumir: +%d de estamina") % int(r.get("consumeStamina", 0)))
		hb.add_child(_action_icon("fish", "🐟", _consume_resource.bind(rtype), ctip))
	hb.add_child(_action_icon("stash", "🧰", _stash_resource.bind(rtype, qty), Lang.t("Guardar no baú")))
	return pc

# [RECURSOS_GIF] TextureRect do recurso: ícone próprio (res_<tipo>) se já gerado/importado, senão o
# genérico de pacote. mouse IGNORE → o hover (e a anim) ficam no chip-pai (pc). null se nem o pacote
# existir. A anim de hover é ligada pelo chamador via Icons.anim_rect(pc, icon, key).
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

# [RECURSOS] Texto de hover: p/ que cada categoria de recurso serve (peixe mostra o valor exato).
func _res_use_text(r: Dictionary) -> String:
	var rtype := str(r.get("type", ""))
	var category := str(r.get("category", ""))
	if category == "FISH":
		var hp := int(r.get("consumeHp", 0))
		if hp > 0:
			return Lang.t("Consumir restaura +%d%% de vida.") % hp
		return Lang.t("Consumir restaura +%d de estamina.") % int(r.get("consumeStamina", 0))
	if rtype == "MONSTER_CORE":
		return Lang.t("Exigido na Path Trial (virar de classe) + material de forja.")
	match category:
		"ORE":      return Lang.t("Refine na Forja → barra de metal (base do equipamento).")
		"BAR":      return Lang.t("Material da Forja: forja armas e armaduras.")
		"FRAGMENT": return Lang.t("Lapide na Forja → joia (encaixa em soquete).")
		"GEM":      return Lang.t("Joia: encaixa em soquete de equipamento (bônus de stat).")
		"ESSENCE":  return Lang.t("Encanta arma/armadura com elemento (na Forja).")
		"MATERIAL": return Lang.t("Material de forja (craft de equipamento).")
		_:          return Lang.t("Recurso de coleta.")

func _consume_resource(rtype: String) -> void:
	if busy or rtype == "":
		return
	busy = true
	var r = await Api.gathering_consume(rtype)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		# [AUDIT] consumir peixe muda estamina/HP (warrior) + a pilha do peixe (recursos). NÃO toca
		# inventário de itens nem habilidades. Re-busca só warrior+recursos (o /api/warrior é obrigatório:
		# a resposta só devolve newStamina/newHpPercent, não o DTO completo que a topbar/stats renderizam).
		await _refresh_subset(["/api/warrior", "/api/gathering/resources"])
		UiKit.flash(status, str(r["json"].get("message", Lang.t("Consumido!"))), 1)
	else:
		await _refresh()                # erro → resync FULL (igual antes)
		UiKit.show_error(status, r)

func _set_rarity(r) -> void:
	rarity_filter = int(r)
	_render_panel()

# [INV_COMPACTO] Slot ENXUTO: só ícone + nome + seta ▲/▼/= (melhor/pior/equivalente vs equipado).
# TODO detalhe (stats, comparação, lore, preço) mora no tooltip rico (hover do card) e no popup de
# ações (clique). O card É clicável: `card.gui_input` pega o clique; os filhos ficam PASS → o hover
# sobe pro card (que mostra o tooltip). Sem botão sobreposto (bloquearia o tooltip).
func _bag_card(it) -> Control:
	if not (it is Dictionary):
		return null
	var rar := int(it.get("rarity", 1))
	var card := ItemTooltipCard.new()       # [ITEM_TOOLTIP] card com tooltip rico no hover
	card.item = it
	card.player_level = int(w.get("level", 0))   # [REQ_LEVEL] "Nv X" vermelho no tooltip se exige nível acima
	card.tooltip_text = " "                  # != "" senão o _make_custom_tooltip nem dispara
	card.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	var res := UiKit.card_styled(card, UiKit.rarity_color(rar))
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	# slot apertado: menos respiro que o card padrão (margem 12 → 7)
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(7)
	box.add_theme_constant_override("separation", 0)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	box.add_child(row)
	var ic := UiKit.item_icon_for(it, 28)   # arma → render do modelo (igual ao slot) [SLOT_WEAPON_IMG]
	if ic:
		row.add_child(ic)
	var nm := Label.new()
	nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 13)
	nm.add_theme_color_override("font_color", UiKit.rarity_color(rar))
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	nm.clip_text = true
	nm.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS   # nome longo → "…"
	row.add_child(nm)
	# [REQ_LEVEL] nível exigido NO slot (sem precisar do hover): vermelho se acima do nível do jogador
	var ilvl := int(it.get("itemLevel", 1))
	var plvl := int(w.get("level", 0))
	var lv := Label.new()
	lv.text = Lang.t("Nv %d") % ilvl
	lv.add_theme_font_size_override("font_size", 11)
	lv.add_theme_color_override("font_color", UiKit.ERR if (plvl > 0 and ilvl > plvl) else UiKit.TEXT_DIM)
	lv.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	lv.mouse_filter = Control.MOUSE_FILTER_PASS
	row.add_child(lv)
	var arrow := UiKit.compare_arrow(it)     # ▲/▼/= vs equipado (null se nada p/ comparar)
	if arrow != null:
		arrow.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		row.add_child(arrow)
	# [ITEM_TOOLTIP] PASS nos filhos (incl. o box do card_styled) → o hover E o clique sobem pro card;
	# o card (STOP) mostra o tooltip rico e trata o clique. Um container STOP no meio comeria o clique.
	for n in [box, row, nm, ic]:
		if n != null and n is Control:
			(n as Control).mouse_filter = Control.MOUSE_FILTER_PASS
	card.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
			_open_item_actions(it))
	return pc

# [INV_COMPACTO] Clique no slot → popup com o card RICO do item (detalhe completo) + ações:
# Equipar / Guardar no baú / Vender (preço). Reusa item_tooltip_panel e o padrão de modal (dim + clique
# fora fecha). Item travado no PvP mostra o aviso no lugar do Vender.
func _open_item_actions(it: Dictionary) -> void:
	var id := int(it.get("id", 0))
	var rar := int(it.get("rarity", 1))
	var dim_rect := ColorRect.new()
	dim_rect.color = Color(0, 0, 0, 0.62)
	dim_rect.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	dim_rect.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(dim_rect)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	center.mouse_filter = Control.MOUSE_FILTER_IGNORE
	dim_rect.add_child(center)
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 8)
	center.add_child(col)
	col.add_child(UiKit.item_tooltip_panel(it, {"equipped": false, "player_level": int(w.get("level", 0))}))   # card rico (detalhe completo)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	col.add_child(row)
	var eq_btn := UiKit.small_btn_icon(Lang.t("Equipar"), "equip", func() -> void:
		dim_rect.queue_free()
		await _equip(id))
	row.add_child(eq_btn)
	var st_btn := UiKit.small_btn_icon(Lang.t("Guardar"), "stash", func() -> void:
		dim_rect.queue_free()
		await _stash_item(id))
	row.add_child(st_btn)
	if bool(it.get("pvpLocked", false)):
		var lk := UiKit.small_btn(Lang.t("🔒 PvP"), func() -> void:   # 🔒 vira ícone "locked" no _btn_label
			UiKit.flash(status, Lang.t("Item travado no PvP — não dá pra vender enquanto exposto."), 2))
		row.add_child(lk)
	else:
		var sell_btn := UiKit.small_btn_icon(Lang.t("Vender (%s)") % UiKit.coin_str(int(it.get("sellPrice", 0))), "sell", func() -> void:
			dim_rect.queue_free()
			await _ask_sell(id, str(it.get("name", "?")), rar))
		row.add_child(sell_btn)
	var close_btn := UiKit.small_btn(Lang.t("Fechar"), func() -> void:
		dim_rect.queue_free())
	col.add_child(close_btn)
	dim_rect.gui_input.connect(func(e: InputEvent) -> void:
		if e is InputEventMouseButton and e.pressed:
			dim_rect.queue_free())

# Ação como ÍCONE-BOTÃO: o ícone É o botão (sem moldura). flat + StyleBoxEmpty (zero padding) + brilho no
# hover. Usa o ícone PixelLab `key` se importado; senão cai no emoji. [GRID_COLS]
func _action_icon(key: String, emoji: String, cb: Callable, tip: String) -> Button:
	var b := Button.new()
	b.flat = true
	b.focus_mode = Control.FOCUS_NONE
	b.custom_minimum_size = Vector2(34, 34)
	b.tooltip_text = tip
	b.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
	var empty := StyleBoxEmpty.new()
	for s in ["normal", "hover", "pressed", "focus"]:
		b.add_theme_stylebox_override(s, empty)
	if Icons.set_icon(b, key):
		b.expand_icon = true
		b.add_theme_constant_override("icon_max_width", 30)
	else:
		b.text = emoji
		b.add_theme_font_size_override("font_size", 18)
	b.mouse_entered.connect(func() -> void: b.modulate = Color(1.25, 1.25, 1.25))
	b.mouse_exited.connect(func() -> void: b.modulate = Color.WHITE)
	b.pressed.connect(cb)
	return b

# ⚔ Atributos ──────────────────────────────────────────────────────────────────────────
func _render_attr_panel() -> void:
	# ── Stats de combate efetivos (atk total etc.) ──
	_panel_host.add_child(UiKit.section("Combate"))
	_panel_host.add_child(_combat_stats_grid())
	# ── Postura de combate (tradeoff ATK/DEF, vale em TODO combate; troca livre) [POSTURE] ──
	if not postures.is_empty():
		_panel_host.add_child(UiKit.section("Postura de Combate"))
		_panel_host.add_child(_posture_picker())
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

# [POSTURE] Picker das 3 posturas — a postura ativa fica destacada; o ATK/DEF efetivo (acima) já reflete a escolha.
const POSTURE_ICON := {"OFFENSIVE": "posture_offensive", "DEFENSIVE": "posture_defensive", "BALANCED": "posture_balanced"}
# rótulo PT (o displayName do backend é inglês "⚔️ Offensive"…); Lang.t() resolve PT→EN. [I18N]
const POSTURE_LABEL := {"OFFENSIVE": "Ofensiva", "DEFENSIVE": "Defensiva", "BALANCED": "Equilibrada"}

func _posture_picker() -> Control:
	var cur := str(w.get("combatPosture", "BALANCED"))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 6)
	for p in postures:
		if p is Dictionary:
			row.add_child(_posture_btn(p, cur))
	return row

func _posture_btn(p: Dictionary, cur: String) -> Button:
	var id := str(p.get("id", ""))
	var active := (id == cur)
	var atk := int(round((float(p.get("atkMult", 1.0)) - 1.0) * 100.0))
	var def := int(round((float(p.get("defMult", 1.0)) - 1.0) * 100.0))
	var b := Button.new()
	DarkButtonStyle.apply(b)
	b.custom_minimum_size = Vector2(0, 62)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	b.focus_mode = Control.FOCUS_NONE
	b.tooltip_text = "ATK %+d%%  ·  DEF %+d%%" % [atk, def]
	if not active:
		b.pressed.connect(func() -> void: await _set_posture(id))
	var v := VBoxContainer.new()
	v.alignment = BoxContainer.ALIGNMENT_CENTER
	v.add_theme_constant_override("separation", 1)
	v.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	v.mouse_filter = Control.MOUSE_FILTER_IGNORE
	b.add_child(v)
	var icon_key := str(POSTURE_ICON.get(id, ""))
	if Icons.tex(icon_key) != null:
		var ir := Icons.rect(icon_key, 26)
		ir.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		ir.mouse_filter = Control.MOUSE_FILTER_IGNORE
		v.add_child(ir)
	var nm := Label.new()
	nm.text = Lang.t(str(POSTURE_LABEL.get(id, UiKit.strip_web_emoji(str(p.get("displayName", id))))))
	nm.add_theme_font_size_override("font_size", 12)
	nm.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	nm.mouse_filter = Control.MOUSE_FILTER_IGNORE
	v.add_child(nm)
	var hl := Label.new()
	hl.text = "ATK %+d%%  DEF %+d%%" % [atk, def]
	hl.add_theme_font_size_override("font_size", 10)
	hl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	hl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	hl.mouse_filter = Control.MOUSE_FILTER_IGNORE
	v.add_child(hl)
	if active:
		var sb := StyleBoxFlat.new()
		sb.bg_color = Color(UiKit.GOLD.r, UiKit.GOLD.g, UiKit.GOLD.b, 0.22)
		sb.set_border_width_all(2); sb.border_color = UiKit.GOLD; sb.set_corner_radius_all(6)
		for s in ["normal", "hover", "pressed", "focus"]:
			b.add_theme_stylebox_override(s, sb)
	else:
		b.modulate = Color(1, 1, 1, 0.7)
	return b

func _set_posture(posture: String) -> void:
	if busy: return
	busy = true
	var r = await Api.warrior_set_posture(posture)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		w = r["json"]                 # WarriorResponse atualizado (combatPosture + ATK/DEF efetivo)
		UiKit.set_wallet(wallet, w)   # topbar reflete o novo ATK/DEF
		_render_panel()               # re-renderiza a aba (postura ativa + stats de combate)
		UiKit.flash(status, Lang.t("Postura atualizada"), 1)
	else:
		UiKit.show_error(status, r)

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
	# [AUDIT] inventário + guerreiro em UM batch (paralelo) — antes eram 2 awaits sequenciais.
	# re-busca o inventário p/ refletir mudanças SERVER-SIDE além do item tocado — ex.: auto-swap
	# arco↔escudo desequipa o conflitante (senão o boneco/slots ficavam com os dois). [ARCO_SEM_ESCUDO]
	var rs = await Api.batch_get(["/api/inventory", "/api/warrior"])
	var ir = rs[0]
	if ir.get("ok") and ir.get("json") is Array:
		items = ir["json"]
	UiKit.set_equipped(items)
	if doll != null and is_instance_valid(doll):
		doll.apply(items, UiKit.current_class, UiKit.current_gender)
	_update_slots()
	_render_panel()
	if UiKit.equip_changed_sink.is_valid():
		UiKit.equip_changed_sink.call(items)
	if UiKit.duel_refresh_sink.is_valid():
		UiKit.duel_refresh_sink.call()   # [MENU_FUNDO] herói do duelo re-veste com o gear novo
	var wr = rs[1]
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

# [STASH] Guardar ITEM no baú (do inventário da Ficha). Mesma API do Baú; o backend cobra a taxa.
func _stash_item(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.stash_deposit_item(id)
	busy = false
	if r.get("ok"):
		# [AUDIT] guardar ITEM tira-o da bag (inventário) + cobra a taxa (warrior). NÃO toca recursos
		# nem habilidades. Re-busca só warrior+inventário.
		await _refresh_subset(["/api/warrior", "/api/inventory"])
		UiKit.flash(status, Lang.t("Guardado no baú!"), 1)
	else:
		await _refresh()                # erro → resync FULL (igual antes)
		UiKit.show_error(status, r)

# [STASH] Guardar RECURSO no baú (deposita a quantidade toda do tipo).
func _stash_resource(rtype: String, qty: int) -> void:
	if busy or rtype == "" or qty <= 0:
		return
	busy = true
	var r = await Api.stash_deposit_resource(rtype, qty)
	busy = false
	if r.get("ok"):
		# [AUDIT] guardar RECURSO tira da pilha da bag (recursos) + cobra a taxa (warrior). NÃO toca
		# inventário de itens nem habilidades. Re-busca só warrior+recursos.
		await _refresh_subset(["/api/warrior", "/api/gathering/resources"])
		UiKit.flash(status, Lang.t("Guardado no baú!"), 1)
	else:
		await _refresh()                # erro → resync FULL (igual antes)
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
		_build_subtab_bar()           # [PONTOS] atualiza o "!" da sub-aba (some quando zera o ponto)
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
		_build_subtab_bar()   # [PONTOS] atualiza o "!" da sub-aba Habilidades (some quando zera o ponto)
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
