extends Control
# [LEADERBOARDS] Tela de classificação: rankings de jogadores e guildas + perfil/inspeção.
# Linha clicável → dialog do jogador (Inspecionar · Enviar carta · Adicionar amigo · Convidar p/ guilda).
# Amizade/convites se GERENCIAM no ícone de Amigos da topbar (tela Friends), não aqui.
# Doc: docs/PLANO_LEADERBOARDS.md
signal go_back
signal open_mail_to(recipient)   # [MAIL_COMPOSE] "Enviar carta" → Correio com o nick preenchido

var content: VBoxContainer
var status: Label

# ── Estado ──
var _tab := "level"               # categoria de jogador OU "guilds"/"territory"
var _guild_sub := "power"         # sub-categoria da aba Guildas
var _territory_code := ""         # reino selecionado na aba Território
var _territories: Array = []      # picker de reinos [{code,name,icon}]
var _page := 0
var _guild_checked := false
var _has_guild := false
var _can_invite := false          # sou líder de uma guilda?

const TOP_TABS := [
	{"label": "Nível", "value": "level"},
	{"label": "Arena", "value": "arena"},
	{"label": "Torre", "value": "tower"},
	{"label": "Caçador", "value": "hunter"},
	{"label": "Carrasco", "value": "slayer"},
	{"label": "Riqueza", "value": "wealth"},
	{"label": "Guildas", "value": "guilds"},
	{"label": "Território", "value": "territory"},
]
const GUILD_SUBS := [
	{"label": "Poder", "value": "power"},
	{"label": "Territórios", "value": "territory"},
	{"label": "Kills de guerra", "value": "warkills"},
	{"label": "Membros", "value": "members"},
]
# Explicação curta de cada ranking (some a dúvida do "Caçador"/"Carrasco").
const TAB_DESC := {
	"level": "Quem está em nível mais alto.",
	"arena": "Quem tem mais pontos de ranque na Arena (duelos PvP).",
	"tower": "Quem subiu mais andares na Torre.",
	"hunter": "Quem matou mais MONSTROS (caça / PvE).",
	"slayer": "Quem matou mais JOGADORES (PvP).",
	"wealth": "Quem tem mais dinheiro (bronze total).",
	"guilds": "Ranking de guildas — escolha o critério abaixo.",
	"territory": "Quem mais ajudou um território (missões/incursões no reino). Escolha o reino.",
}
const VALUE_LABEL := {
	"level": "Nv", "arena": "RP", "tower": "Andar", "hunter": "kills",
	"slayer": "kills", "wealth": "", "territory": "inc",
}
const CLASS_NAMES := {"recruit": "Recruta", "warrior": "Guerreiro", "archer": "Arqueiro", "merchant": "Mercador"}

func _ready() -> void:
	var ui := UiKit.scaffold(self, "Classificação",
		func() -> void: go_back.emit(),
		func() -> void: await _refresh(),
		UiKit.TINT_SOCIAL)
	content = ui.content
	status = ui.status
	await _refresh()

# ── Carga ──
func _refresh() -> void:
	UiKit.show_loading(self)
	if not _guild_checked:
		_guild_checked = true
		var g = await Api.guild_get()
		if g.get("ok") and g.get("json") is Dictionary:
			_has_guild = g["json"].has("id")
			_can_invite = bool(g["json"].get("isLeader", false))
	var data := await _load_tab_data()
	UiKit.hide_loading()
	_render(data)

func _load_tab_data() -> Dictionary:
	match _tab:
		"guilds":
			var r = await Api.leaderboard_guild(_guild_sub, _page)
			return {"rows": _rows_of(r)}
		"territory":
			if _territories.is_empty():
				var t = await Api.leaderboard_territories()
				if t.get("ok") and t.get("json") is Array:
					_territories = t["json"]
					if _territory_code == "" and not _territories.is_empty():
						_territory_code = str(_territories[0].get("code", ""))
			if _territory_code == "":
				return {"rows": []}
			var r2 = await Api.leaderboard_territory(_territory_code, _page)
			return {"rows": _rows_of(r2)}
		_:
			var r4 = await Api.leaderboard(_tab, _page)
			return {"rows": _rows_of(r4)}

func _rows_of(r) -> Array:
	return r["json"] if r.get("ok") and r.get("json") is Array else []

# ── Render ──
func _render(data: Dictionary) -> void:
	for c in content.get_children():
		c.queue_free()
	content.add_child(UiKit.filter_row(TOP_TABS, _tab, _on_tab))
	var desc := str(TAB_DESC.get(_tab, ""))
	if desc != "":
		content.add_child(UiKit.dim(Lang.t(desc)))
	match _tab:
		"guilds": _render_guilds(data.get("rows", []))
		"territory": _render_territory(data.get("rows", []))
		_: _render_players(data.get("rows", []))

func _render_players(rows: Array) -> void:
	content.add_child(UiKit.section_paged("Ranking", _page, rows.size() >= 20,
		_go_page.bind(-1), _go_page.bind(1)))
	if rows.is_empty():
		content.add_child(UiKit.dim(Lang.t("Ainda sem ninguém aqui.")))
		return
	var list := VBoxContainer.new()
	list.add_theme_constant_override("separation", 5)
	list.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	for r in rows:
		list.add_child(_player_row(r))
	content.add_child(UiKit.capped_scroll(list, 460.0))

func _render_guilds(rows: Array) -> void:
	content.add_child(UiKit.filter_row(GUILD_SUBS, _guild_sub, _on_guild_sub))
	content.add_child(UiKit.section_paged("Guildas", _page, rows.size() >= 20,
		_go_page.bind(-1), _go_page.bind(1)))
	if rows.is_empty():
		content.add_child(UiKit.dim(Lang.t("Ainda sem guildas.")))
		return
	var list := VBoxContainer.new()
	list.add_theme_constant_override("separation", 5)
	for r in rows:
		list.add_child(_guild_row(r))
	content.add_child(UiKit.capped_scroll(list, 460.0))

func _render_territory(rows: Array) -> void:
	var opts: Array = []
	for t in _territories:
		opts.append({"label": str(t.get("name", t.get("code", ""))), "value": str(t.get("code", ""))})
	if not opts.is_empty():
		content.add_child(UiKit.filter_row(opts, _territory_code, _on_territory))
	content.add_child(UiKit.section_paged("Quem mais ajudou", _page, rows.size() >= 20,
		_go_page.bind(-1), _go_page.bind(1)))
	if rows.is_empty():
		content.add_child(UiKit.dim(Lang.t("Ainda sem incursões neste território.")))
		return
	var list := VBoxContainer.new()
	list.add_theme_constant_override("separation", 5)
	for r in rows:
		list.add_child(_player_row(r))
	content.add_child(UiKit.capped_scroll(list, 420.0))

# ── Linhas ──
func _player_row(r: Dictionary) -> Control:
	var res := UiKit.clickable_card(UiKit.BRONZE, _player_dialog.bind(r), true, Lang.t("Ver opções"))
	var vb: VBoxContainer = res[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	row.add_child(_rank_label(int(r.get("rank", 0))))
	var nm := Label.new()
	nm.text = _named(r)
	nm.add_theme_font_size_override("font_size", 14)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.clip_text = true
	row.add_child(nm)
	var sub := Label.new()
	sub.text = "%s · Lv%d" % [_class_name(str(r.get("classId", ""))), int(r.get("level", 1))]
	sub.add_theme_font_size_override("font_size", 12)
	sub.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	row.add_child(sub)
	var val := Label.new()
	val.text = ("%s %s" % [_fmt_value(int(r.get("value", 0))), str(VALUE_LABEL.get(_tab, ""))]).strip_edges()
	val.add_theme_font_size_override("font_size", 14)
	val.add_theme_color_override("font_color", UiKit.GOLD)
	val.custom_minimum_size = Vector2(108, 0)
	val.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	row.add_child(val)
	vb.add_child(row)
	return res[0]

func _guild_row(r: Dictionary) -> Control:
	var card := UiKit.card(UiKit.BRONZE)
	var vb: VBoxContainer = card[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	row.add_child(_rank_label(int(r.get("rank", 0))))
	var nm := Label.new()
	nm.text = str(r.get("guildName", "?"))
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.clip_text = true
	row.add_child(nm)
	var lv := Label.new()
	lv.text = "Lv%d" % int(r.get("level", 1))
	lv.add_theme_font_size_override("font_size", 12)
	lv.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	row.add_child(lv)
	var val := Label.new()
	val.text = _fmt_value(int(r.get("value", 0)))
	val.add_theme_color_override("font_color", UiKit.GOLD)
	val.custom_minimum_size = Vector2(90, 0)
	val.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	row.add_child(val)
	vb.add_child(row)
	return card[0]

# ── Dialog do jogador ──
func _player_dialog(r: Dictionary) -> void:
	var pid := int(r.get("playerId", 0))
	var pname := str(r.get("warriorName", "?"))
	var dim := _make_dim()
	var card := UiKit.card(UiKit.GOLD_SOFT)
	var pc: PanelContainer = card[0]
	var vb: VBoxContainer = card[1]
	pc.custom_minimum_size = Vector2(340, 0)
	vb.add_theme_constant_override("separation", 6)
	var head := Label.new()
	head.text = _named(r)
	head.add_theme_font_size_override("font_size", 18)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	head.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(head)
	vb.add_child(UiKit.dim("%s · Lv%d" % [_class_name(str(r.get("classId", ""))), int(r.get("level", 1))]))
	vb.add_child(UiKit.spacer(4))
	var inspect_btn := UiKit.action(Lang.t("Inspecionar"), _open_inspect.bind(pid, pname, dim))
	inspect_btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	vb.add_child(inspect_btn)
	vb.add_child(UiKit.small_btn(Lang.t("Enviar carta"), _open_letter.bind(pname, dim)))
	vb.add_child(UiKit.small_btn(Lang.t("Adicionar amigo"), _do_friend_request.bind(pid, dim)))
	if _has_guild and _can_invite:
		vb.add_child(UiKit.small_btn(Lang.t("Convidar p/ guilda"), _do_guild_invite.bind(pid, dim)))
	vb.add_child(UiKit.spacer(2))
	vb.add_child(UiKit.small_btn(Lang.t("Voltar"), _close_dim.bind(dim)))
	_center_in_dim(dim, pc)

# ── Inspeção (perfil read-only) ──
func _inspect_dialog(pid: int, pname: String) -> void:
	var dim := _make_dim()
	var r = await Api.player_profile(pid)
	if not (r.get("ok") and r.get("json") is Dictionary):
		_close_dim(dim)
		UiKit.toast(self, Lang.t("Não foi possível carregar o perfil."), "", 2)
		return
	var p: Dictionary = r["json"]
	var card := UiKit.card(UiKit.GOLD_SOFT)
	var pc: PanelContainer = card[0]
	var vb: VBoxContainer = card[1]
	pc.custom_minimum_size = Vector2(420, 0)
	var head := Label.new()
	head.text = _named(p)
	head.add_theme_font_size_override("font_size", 18)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	head.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(head)
	vb.add_child(UiKit.dim("%s · Lv%d" % [_class_name(str(p.get("classId", ""))), int(p.get("level", 1))]))
	var cb: Dictionary = p.get("combat", {})
	vb.add_child(UiKit.section("Combate"))
	vb.add_child(_stat_grid([
		["ATK", int(cb.get("atk", 0)), true], ["DEF", int(cb.get("def", 0)), true], ["HP", int(cb.get("hp", 0)), true],
		["DEX", int(cb.get("dex", 0)), false], ["AGI", int(cb.get("agi", 0)), false], ["LUK", int(cb.get("luk", 0)), false],
	]))
	var at: Dictionary = p.get("attributes", {})
	vb.add_child(UiKit.section("Atributos"))
	vb.add_child(_stat_grid([
		["STR", int(at.get("str", 0)), false], ["DEX", int(at.get("dex", 0)), false], ["CON", int(at.get("con", 0)), false],
		["AGI", int(at.get("agi", 0)), false], ["LUK", int(at.get("luk", 0)), false],
	]))
	var eq: Array = p.get("equipped", [])
	vb.add_child(UiKit.section("Equipados"))
	if eq.is_empty():
		vb.add_child(UiKit.dim(Lang.t("Nada equipado.")))
	else:
		var flow := HFlowContainer.new()
		flow.add_theme_constant_override("h_separation", 6)
		flow.add_theme_constant_override("v_separation", 6)
		for it in eq:
			flow.add_child(_inspect_item_slot(it))
		vb.add_child(flow)
	vb.add_child(UiKit.spacer(4))
	vb.add_child(UiKit.small_btn(Lang.t("Voltar"), _close_dim.bind(dim)))
	_center_in_dim(dim, pc)

func _stat_grid(stats: Array) -> GridContainer:
	var g := GridContainer.new()
	g.columns = 3
	g.add_theme_constant_override("h_separation", 16)
	g.add_theme_constant_override("v_separation", 3)
	for s in stats:
		var col: Color = UiKit.GOLD if bool(s[2]) else UiKit.TEXT
		g.add_child(UiKit.kv(str(s[0]), str(int(s[1])), col))
	return g

func _inspect_item_slot(it: Dictionary) -> Control:
	var slot := ItemTooltipCard.new()
	slot.item = it
	slot.tooltip_text = " "   # precisa != "" p/ o tooltip rico disparar
	slot.custom_minimum_size = Vector2(52, 52)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.05, 0.045, 0.06)
	sb.set_corner_radius_all(3)
	sb.set_border_width_all(2)
	sb.border_color = UiKit.rarity_color(int(it.get("rarity", 1)))
	slot.add_theme_stylebox_override("panel", sb)
	var icon := UiKit.item_icon_for(it, 40)
	if icon != null:
		icon.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		icon.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		slot.add_child(icon)
	return slot

# ── Ações do dialog ──
func _open_inspect(pid: int, pname: String, dim) -> void:
	_close_dim(dim)
	await _inspect_dialog(pid, pname)

func _open_letter(pname: String, dim) -> void:
	_close_dim(dim)
	open_mail_to.emit(pname)   # Shell abre o Correio com o nick preenchido

func _do_friend_request(pid: int, dim) -> void:
	var r = await Api.friend_request(pid)
	_close_dim(dim)
	if r.get("ok"):
		UiKit.toast(self, Lang.t("Pedido de amizade enviado."), "members", 1)
	else:
		UiKit.toast(self, UiKit.err_text(r), "", 2)

func _do_guild_invite(pid: int, dim) -> void:
	var r = await Api.guild_invite(pid)
	_close_dim(dim)
	if r.get("ok"):
		UiKit.toast(self, Lang.t("Convite enviado."), "members", 1)
	else:
		UiKit.toast(self, UiKit.err_text(r), "", 2)

# ── Troca de aba / página ──
func _on_tab(v) -> void:
	_tab = str(v)
	_page = 0
	await _refresh()

func _on_guild_sub(v) -> void:
	_guild_sub = str(v)
	_page = 0
	await _refresh()

func _on_territory(code) -> void:
	_territory_code = str(code)
	_page = 0
	await _refresh()

func _go_page(d: int) -> void:
	_page = maxi(0, _page + d)
	await _refresh()

# ── Dialog helpers (dim + center) ──
func _make_dim() -> ColorRect:
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.62)
	dim.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	dim.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(dim)
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

# ── Util ──
func _named(r: Dictionary) -> String:
	var title := str(r.get("title", ""))
	return (("⟨%s⟩ " % title) if title != "" else "") + str(r.get("warriorName", "?"))

func _class_name(cid: String) -> String:
	return Lang.t(str(CLASS_NAMES.get(cid, cid.capitalize())))

func _fmt_value(v: int) -> String:
	if v >= 1000000:
		return "%.1fM" % (v / 1000000.0)
	if v >= 10000:
		return "%.1fk" % (v / 1000.0)
	return str(v)

func _rank_label(rank: int) -> Label:
	var l := Label.new()
	l.text = "#%d" % rank
	l.add_theme_font_size_override("font_size", 16)
	l.add_theme_color_override("font_color", _rank_color(rank))
	l.custom_minimum_size = Vector2(46, 0)
	return l

func _rank_color(rank: int) -> Color:
	match rank:
		1: return Color(1.0, 0.84, 0.0)
		2: return Color(0.78, 0.78, 0.82)
		3: return Color(0.80, 0.52, 0.24)
		_: return UiKit.TEXT_DIM
