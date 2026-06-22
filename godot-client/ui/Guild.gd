extends Control
# ── Tela GUILDA ───────────────────────────────────────────────────────────────────
# [GUILD_TABS] Reformulada (2026-06): 3 sub-abas (Visão Geral / Membros / Guerra) p/ caber SEM
# scroll de página; listas de tamanho livre vão em capped_scroll (rolam internas, a página fica
# fixa). Botões grandes viraram ícones-botão compactos (icon_choice_btn/small_btn_icon) que animam/
# brilham no hover; emojis de web inline viraram ícones PixelLab (Icons.rect). Sem guilda = painel
# único (criar/entrar), sem abas. Espelha o padrão de sub-aba do Character.gd. [PADRAO_UI_GODOT]
# Inclui guerra de guilda + a FORMAÇÃO 3×5 (líder posiciona os membros). Territórios = tela à parte.

signal go_back
signal request_battle(data)   # [BATALHA_ANIMADA] ataque de guerra → replay 3D (App esconde o shell)
signal open_screen(name)      # [MENUBAR_REORG] Território saiu da barra lateral → botão aqui abre a tela

const Icons := preload("res://ui/Icons.gd")
const F_LANES := 3   # [GUERRA_FORMACAO] colunas (lanes) do tabuleiro de guerra
const F_DEPTH := 5   # linhas (profundidade): 0 = frente (luta 1º) … 4 = retaguarda

var _formation_cells := {}     # "lane:depth" -> OptionButton (editor do líder)
var _pending_after := {}       # [BATALHA_ANIMADA] resultado do ataque guardado durante o replay 3D
var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var data: Dictionary = {}      # detalhe da guilda (quando inGuild)
var warrior: Dictionary = {}   # /api/warrior (carteira do header)
var guild_list: Array = []     # lista de guildas (quando sem guilda)
var war: Dictionary = {}       # /api/guild/war (status da guerra; atWar:false se sem guilda)
var targets: Array = []        # guildas rivais elegíveis (carregadas ao escolher declarar)
var picking := false           # estado de UI: escolhendo alvo de guerra
var sub_tab := "overview"      # [GUILD_TABS] overview | members | war
var _subtab_bar_host: VBoxContainer
var _panel_host: VBoxContainer
# campos de input (criar guilda / doar) — guardados p/ ler no submit
var name_edit: LineEdit
var desc_edit: LineEdit
var donate_gold: SpinBox
var donate_silver: SpinBox
var donate_bronze: SpinBox

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🛡 Guilda", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_SOCIAL)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	# estrutura montada UMA vez: barra de sub-abas + painel da aba (Character.gd)
	_subtab_bar_host = VBoxContainer.new()
	content.add_child(_subtab_bar_host)
	_panel_host = VBoxContainer.new()
	_panel_host.add_theme_constant_override("separation", 7)
	_panel_host.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	content.add_child(_panel_host)
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	# [AUDIT] /api/guild/war entrou no batch (paralelo)
	var rs = await Api.batch_get(["/api/guild", "/api/warrior", "/api/guild/war"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	data = r["json"]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	picking = false
	targets = []
	var wsr = rs[2]
	war = wsr["json"] if (wsr.get("ok") and wsr.get("json") is Dictionary) else {}
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)
	if bool(data.get("inGuild", false)):
		guild_list = []
		_render_guild()
	else:
		# sem guilda → busca a lista pra entrar, depois renderiza o painel de criação
		var lr = await Api.guild_list()
		guild_list = lr["json"] if (lr.get("ok") and lr.get("json") is Array) else []
		_render_no_guild()

# Limpa SÓ o painel da sub-aba (não a barra). Zera os refs de input (apontariam p/ nós liberados).
func _clear_panel() -> void:
	for c in _panel_host.get_children():
		c.queue_free()
	name_edit = null; desc_edit = null
	donate_gold = null; donate_silver = null; donate_bronze = null

# ── COM guilda: barra de sub-abas + painel ──────────────────────────────────────────
func _render_guild() -> void:
	_subtab_bar_host.visible = true
	_build_subtab_bar()
	_render_subtab()

func _build_subtab_bar() -> void:
	for c in _subtab_bar_host.get_children():
		c.queue_free()
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 6)
	for t in [["overview", "guild", "Visão Geral", "🛡"], ["members", "members", "Membros", "👥"], ["war", "declare_war", "Guerra", "⚔"]]:
		row.add_child(_subtab_btn(str(t[0]), str(t[1]), str(t[2]), str(t[3])))
	_subtab_bar_host.add_child(row)

# Botão de sub-aba com ícone PixelLab + texto (fallback no emoji) e destaque do ativo. [Character.gd]
func _subtab_btn(value: String, icon_key: String, label: String, emoji: String) -> Button:
	var b := UiKit.small_btn("%s %s" % [emoji, Lang.t(label)], func() -> void: _set_tab(value))
	if Icons.set_icon(b, icon_key):
		b.add_theme_constant_override("icon_max_width", 22)
		b.text = Lang.t(label)
	b.custom_minimum_size = Vector2(0, 36)
	b.add_theme_font_size_override("font_size", 13)
	b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	if sub_tab == value:
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
	picking = false   # trocar de aba sai do modo "escolher alvo"
	_build_subtab_bar()
	_render_subtab()

func _render_subtab() -> void:
	_clear_panel()
	match sub_tab:
		"members": _tab_members()
		"war": _tab_war()
		_: _tab_overview()

# ── Aba "Visão Geral" ───────────────────────────────────────────────────────────────
func _tab_overview() -> void:
	var g := data
	var is_leader := bool(g.get("isLeader", false))
	# cabeçalho da guilda (card)
	var head_res := UiKit.card(UiKit.GOLD)
	var head_box: VBoxContainer = head_res[1]
	var head := Label.new()
	head.text = Lang.t("%s   Lv.%d") % [str(g.get("name", "?")), int(g.get("level", 1))]
	head.add_theme_font_size_override("font_size", 22)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	head_box.add_child(head)
	var dtxt := str(g.get("description", ""))
	head_box.add_child(UiKit.dim(dtxt if dtxt != "" else "Sem descrição."))
	var members: Array = g.get("members", []) if g.get("members") is Array else []
	head_box.add_child(_info_row("treasury", "Tesouro", UiKit.coin_box(int(g.get("treasuryBronze", 0)))))
	var mlbl := Label.new(); mlbl.text = "%d/%d" % [members.size(), int(g.get("maxMembers", 0))]
	mlbl.add_theme_font_size_override("font_size", 14); mlbl.add_theme_color_override("font_color", UiKit.TEXT)
	head_box.add_child(_info_row("members", "Membros", mlbl))
	var xpb := int(g.get("xpBonus", 0)); var dropb := int(g.get("dropBonus", 0)); var brb := int(g.get("bronzeBonus", 0))
	if xpb != 0 or dropb != 0 or brb != 0:
		var bl := Label.new()
		bl.text = Lang.t("Bônus: +%d%% XP · +%d%% drop · +%d%% bronze") % [xpb, dropb, brb]
		bl.add_theme_color_override("font_color", UiKit.OK)
		bl.add_theme_font_size_override("font_size", 12)
		head_box.add_child(bl)
	var maxed := int(g.get("level", 1)) >= int(g.get("maxLevel", 10))
	if maxed:
		head_box.add_child(_icon_text("star", Lang.t("Nível máximo (Lv.%d) — total: %s") % [int(g.get("maxLevel", 10)), _fmt_bronze(int(g.get("lifetimeGold", 0)))], UiKit.GOLD))
	else:
		head_box.add_child(UiKit.bar(Lang.t("Nível"), int(g.get("levelProgressPct", 0)), 100, UiKit.GOLD,
			Lang.t("Lv.%d → Lv.%d  (faltam %s)") % [int(g.get("level", 1)), int(g.get("level", 1)) + 1, _fmt_bronze(int(g.get("goldToNextLevel", 0)))]))
	_panel_host.add_child(head_res[0])
	# linha de ícones-botão: Território + Doar + os 3 campos de doação (inline)
	var actions := HBoxContainer.new(); actions.add_theme_constant_override("separation", 10)
	actions.add_child(UiKit.icon_choice_btn("map_fortress", "🏰", Lang.t("Território"), func() -> void: open_screen.emit("Territory"), UiKit.GOLD_SOFT, true))
	actions.add_child(UiKit.icon_choice_btn("gold", "💰", Lang.t("Doar"), _donate, UiKit.GOLD_SOFT, true))
	donate_gold = _spin(); donate_silver = _spin(); donate_bronze = _spin()
	actions.add_child(_coin_spin("gold", donate_gold))
	actions.add_child(_coin_spin("silver", donate_silver))
	actions.add_child(_coin_spin("bronze", donate_bronze))
	_panel_host.add_child(actions)
	# Top Doadores (lista travada — não estica a página)
	var rank: Array = g.get("donationRank", []) if g.get("donationRank") is Array else []
	_panel_host.add_child(UiKit.section("🏆 Top Doadores"))
	if rank.is_empty():
		_panel_host.add_child(UiKit.dim("— sem doações ainda —"))
	else:
		var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 2)
		box.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		var i := 0
		for d in rank:
			if d is Dictionary:
				box.add_child(_donor_row(i, d))
			i += 1
		_panel_host.add_child(UiKit.capped_scroll(box, 110.0))
	# Sair / Dissolver — discreto, alinhado à direita
	var lead := HBoxContainer.new(); lead.add_theme_constant_override("separation", 8)
	lead.alignment = BoxContainer.ALIGNMENT_END
	if is_leader:
		lead.add_child(UiKit.small_btn_icon(Lang.t("Dissolver"), "skull", _confirm_disband, true))
	else:
		lead.add_child(UiKit.small_btn_icon(Lang.t("Sair"), "act_flee", _confirm_leave, true))
	_panel_host.add_child(lead)

# ── Aba "Membros" ───────────────────────────────────────────────────────────────────
func _tab_members() -> void:
	var members: Array = data.get("members", []) if data.get("members") is Array else []
	var is_leader := bool(data.get("isLeader", false))
	_panel_host.add_child(UiKit.section(Lang.t("Membros (%d)") % members.size()))
	var grid := UiKit.grid(self, members, func(mm): return _member_row(mm, is_leader) if mm is Dictionary else null, false, 260, 2)
	_panel_host.add_child(UiKit.capped_scroll(grid, 440.0))

func _member_row(mm: Dictionary, is_leader: bool) -> PanelContainer:
	var me := bool(mm.get("isMe", false))
	var ml := bool(mm.get("isLeader", false))
	var res := UiKit.card()
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 6)
	box.add_child(hb)
	if ml and Icons.tex("crown") != null:   # coroa do líder (era 👑)
		var cr := Icons.rect("crown", 16); cr.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		hb.add_child(cr)
	var title := str(mm.get("title", ""))
	var nm := Label.new()
	nm.text = (title + " " if title != "" else "") + str(mm.get("warriorName", "?")) + (Lang.t(" (você)") if me else "")
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.GOLD if me else UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	nm.clip_text = true
	hb.add_child(nm)
	var fat := int(mm.get("fatiguePct", 0))
	if fat > 0:   # fadiga de guerra (era 😓)
		if Icons.tex("fatigue") != null:
			var fi := Icons.rect("fatigue", 14); fi.size_flags_vertical = Control.SIZE_SHRINK_CENTER
			hb.add_child(fi)
		var fl := Label.new(); fl.text = "-%d%%" % fat
		fl.add_theme_color_override("font_color", UiKit.ERR)
		fl.add_theme_font_size_override("font_size", 11)
		fl.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		hb.add_child(fl)
	# botões do líder (não em si mesmo / kick não no líder)
	if is_leader and not me:
		var pid := int(mm.get("playerId", 0))
		var who := str(mm.get("warriorName", "?"))
		if not ml:
			hb.add_child(UiKit.small_btn_icon(Lang.t("Expulsar"), "act_flee", _confirm_kick.bind(pid, who), true))
		hb.add_child(UiKit.small_btn_icon(Lang.t("Transferir"), "crown", _confirm_transfer.bind(pid, who)))
	return res[0]

# ── Aba "Guerra" ────────────────────────────────────────────────────────────────────
func _tab_war() -> void:
	if bool(war.get("atWar", false)):
		_war_active()
	else:
		_war_idle()
	_render_formation()

func _war_active() -> void:
	var my_kills := int(war.get("myKills", 0))
	var enemy_kills := int(war.get("enemyKills", 0))
	var res := UiKit.card(UiKit.ERR)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_border_width_all(2)
	var head := Label.new()
	head.text = Lang.t("Em guerra com %s") % str(war.get("enemyGuildName", "?"))
	head.add_theme_font_size_override("font_size", 18)
	head.add_theme_color_override("font_color", UiKit.ERR)
	box.add_child(head)
	box.add_child(UiKit.kv("Placar", Lang.t("%d × %d (você × inimigo)") % [my_kills, enemy_kills]))
	box.add_child(UiKit.kv("Termina em", _fmt_time(int(war.get("secondsLeft", 0)))))
	_panel_host.add_child(pc)
	var enemies: Array = war.get("enemies", []) if war.get("enemies") is Array else []
	_panel_host.add_child(UiKit.section(Lang.t("Inimigos")))
	if enemies.is_empty():
		_panel_host.add_child(UiKit.dim("— nenhum inimigo disponível para atacar agora —"))
	else:
		var grid := UiKit.grid(self, enemies, _enemy_card)
		_panel_host.add_child(UiKit.capped_scroll(grid, 180.0))

func _enemy_card(e: Variant) -> Control:
	var en: Dictionary = e if e is Dictionary else {}
	var res := UiKit.card()
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 10)
	box.add_child(row)
	var left := VBoxContainer.new(); left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(left)
	var ko := bool(en.get("knockedOut", false))
	var shielded := bool(en.get("shielded", false))
	var title := str(en.get("title", ""))
	var nm := Label.new()
	nm.text = (title + " " if title != "" else "") + str(en.get("warriorName", "?"))
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	left.add_child(nm)
	var hp := int(en.get("hpPercent", 100))
	left.add_child(_icon_text("hp", Lang.t("Nv %d · %d%%") % [int(en.get("level", 1)), hp], UiKit.ERR if ko else UiKit.TEXT_DIM))
	var rcol := VBoxContainer.new()
	rcol.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rcol.size_flags_horizontal = Control.SIZE_SHRINK_END
	row.add_child(rcol)
	var pid := int(en.get("playerId", 0))
	if ko:
		rcol.add_child(_icon_text("skull", Lang.t("KO"), UiKit.TEXT_DIM))
	elif shielded:
		rcol.add_child(_icon_text("slot_shield", Lang.t("Protegido"), UiKit.TEXT_DIM))
	else:
		rcol.add_child(UiKit.small_btn_icon(Lang.t("Atacar"), "node_combat", _attack.bind(pid), true))
	return pc

func _war_idle() -> void:
	if not bool(data.get("isLeader", false)):
		_panel_host.add_child(UiKit.dim("Guerra de 7 dias contra uma rival. Só o líder pode declarar."))
		return
	if picking:
		_panel_host.add_child(UiKit.section(Lang.t("Escolha uma guilda rival")))
		if targets.is_empty():
			_panel_host.add_child(UiKit.empty("Nenhuma guilda elegível.", "Rivais precisam ter controlado um território."))
		else:
			var grid := UiKit.grid(self, targets, func(t): return _target_row(t) if t is Dictionary else null, false, 260, 2)
			_panel_host.add_child(UiKit.capped_scroll(grid, 220.0))
		_panel_host.add_child(UiKit.small_btn(Lang.t("Cancelar"), _cancel_picking))
	else:
		_panel_host.add_child(UiKit.dim("Guerra de 7 dias: quem fizer mais kills leva 25% do ouro. Ambas precisam ter controlado um território."))
		var dr := HBoxContainer.new()
		dr.add_child(UiKit.icon_choice_btn("declare_war", "⚔", Lang.t("Declarar Guerra"), _open_targets, UiKit.ERR, true))
		_panel_host.add_child(dr)

func _target_row(t: Dictionary) -> PanelContainer:
	var res := UiKit.card()
	var box: VBoxContainer = res[1]
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 10)
	box.add_child(row)
	var nm := Label.new()
	nm.text = Lang.t("%s   Nv.%d") % [str(t.get("name", "?")), int(t.get("level", 1))]
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(nm)
	var gid := int(t.get("id", 0))
	row.add_child(UiKit.small_btn_icon(Lang.t("Declarar"), "declare_war", _confirm_declare.bind(gid), true))
	return res[0]

# ── Formação 3×5 da guerra [GUERRA_FORMACAO] ────────────────────────────────────────
func _render_formation() -> void:
	var members: Array = data.get("members", []) if data.get("members") is Array else []
	var is_leader := bool(data.get("isLeader", false))
	_panel_host.add_child(UiKit.section("⚔ Formação de Guerra (3 × 5)"))
	_panel_host.add_child(UiKit.dim("A frente luta 1º e leva o HP restante pro próximo; vence quem ganha 2 das 3 lanes. Vazias = auto-preenchidas."))
	_formation_cells = {}
	var placed := {}
	for m in members:
		if m is Dictionary and int(m.get("warLane", -1)) >= 0 and int(m.get("warDepth", -1)) >= 0:
			placed["%d:%d" % [int(m.get("warLane", 0)), int(m.get("warDepth", 0))]] = m
	var grid := GridContainer.new()
	grid.columns = F_LANES + 1
	grid.add_theme_constant_override("h_separation", 6)
	grid.add_theme_constant_override("v_separation", 6)
	grid.add_child(_f_label("", 11, UiKit.TEXT_DIM))
	for l in F_LANES:
		grid.add_child(_f_label("Lane %d" % (l + 1), 12, UiKit.GOLD))
	var depth_names := ["Frente", "2ª", "3ª", "4ª", "Retag."]
	for d in F_DEPTH:
		var dl := str(depth_names[d]) + (" (1º)" if d == 0 else "")
		grid.add_child(_f_label(dl, 11, UiKit.GOLD if d == 0 else UiKit.TEXT_DIM))
		for l in F_LANES:
			var key := "%d:%d" % [l, d]
			var cur: Dictionary = placed.get(key, {})
			if is_leader:
				var ob := _f_option(members, cur)
				_formation_cells[key] = ob
				grid.add_child(ob)
			else:
				grid.add_child(_f_readonly_cell(cur))
	_panel_host.add_child(grid)
	if is_leader:
		var sr := HBoxContainer.new()
		sr.add_child(UiKit.icon_choice_btn("territory", "💾", Lang.t("Salvar"), _save_formation, UiKit.GOLD_SOFT, true))
		_panel_host.add_child(sr)
	else:
		_panel_host.add_child(UiKit.dim("Só o líder pode posicionar a formação."))

func _f_label(text: String, size: int, col: Color) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", col)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.custom_minimum_size = Vector2(64, 0)
	return l

func _f_option(members: Array, current: Dictionary) -> OptionButton:
	var ob := OptionButton.new()
	ob.custom_minimum_size = Vector2(112, 30)
	ob.add_item("—", 0)
	var sel_idx := 0
	var idx := 1
	for m in members:
		if m is Dictionary:
			var pid := int(m.get("playerId", 0))
			ob.add_item(str(m.get("warriorName", "?")), pid)
			if int(current.get("playerId", -1)) == pid:
				sel_idx = idx
			idx += 1
	ob.select(sel_idx)
	return ob

func _f_readonly_cell(current: Dictionary) -> PanelContainer:
	var pc := PanelContainer.new()
	pc.custom_minimum_size = Vector2(112, 30)
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.08, 0.07, 0.09, 0.95)
	sb.set_border_width_all(1)
	sb.border_color = UiKit.BRONZE
	sb.set_corner_radius_all(4)
	sb.set_content_margin_all(4)
	pc.add_theme_stylebox_override("panel", sb)
	var l := Label.new()
	l.text = str(current.get("warriorName", "—")) if not current.is_empty() else "—"
	l.add_theme_font_size_override("font_size", 11)
	l.add_theme_color_override("font_color", UiKit.TEXT if not current.is_empty() else UiKit.TEXT_DIM)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	pc.add_child(l)
	return pc

func _save_formation() -> void:
	if busy: return
	var slots := []
	var used := {}
	for key in _formation_cells:
		var ob: OptionButton = _formation_cells[key]
		var pid := ob.get_selected_id()
		if pid == 0:
			continue
		if used.has(pid):
			UiKit.flash(status, Lang.t("Cada membro só pode ocupar uma célula."), 2)
			return
		used[pid] = true
		var parts := str(key).split(":")
		slots.append({"playerId": pid, "lane": int(parts[0]), "depth": int(parts[1])})
	busy = true
	var r = await Api.guild_set_formation(slots)
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, Lang.t("Formação salva."), 1)
	else:
		UiKit.show_error(status, r)

# ── Painel SEM guilda (sem abas) ────────────────────────────────────────────────────
func _render_no_guild() -> void:
	_subtab_bar_host.visible = false
	_clear_panel()
	_panel_host.add_child(UiKit.empty("Você não pertence a nenhuma guilda.", "Crie a sua ou entre numa existente abaixo"))
	_panel_host.add_child(UiKit.section("Criar nova guilda  (custa 100 bronze)"))
	var res := UiKit.card()
	var box: VBoxContainer = res[1]
	name_edit = UiKit.input("Nome (3-30 chars)"); name_edit.max_length = 30
	name_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	box.add_child(name_edit)
	desc_edit = UiKit.input("Descrição (opcional)"); desc_edit.max_length = 120
	desc_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	box.add_child(desc_edit)
	var cr := HBoxContainer.new(); cr.alignment = BoxContainer.ALIGNMENT_END
	cr.add_child(UiKit.icon_choice_btn("guild", "🛡", Lang.t("Criar Guilda"), _create, UiKit.GOLD_SOFT, true))
	box.add_child(cr)
	_panel_host.add_child(res[0])
	_panel_host.add_child(UiKit.section("Guildas existentes"))
	if guild_list.is_empty():
		_panel_host.add_child(UiKit.empty("Nenhuma guilda criada ainda.", "Seja o primeiro a fundar uma!"))
	else:
		var grid := UiKit.grid(self, guild_list, func(g): return _guild_list_row(g) if g is Dictionary else null, false, 260, 2)
		_panel_host.add_child(UiKit.capped_scroll(grid, 320.0))

func _guild_list_row(g: Dictionary) -> PanelContainer:
	var res := UiKit.card()
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 8)
	box.add_child(hb)
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new(); nm.text = Lang.t("%s   Nv.%d") % [str(g.get("name", "?")), int(g.get("level", 1))]
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	left.add_child(nm)
	var d := str(g.get("description", ""))
	if d != "":
		left.add_child(UiKit.dim(d))
	var members := int(g.get("members", 0)); var maxm := int(g.get("maxMembers", 0))
	left.add_child(_icon_text("members", "%d/%d" % [members, maxm], UiKit.TEXT_DIM))
	hb.add_child(left)
	var rcol := VBoxContainer.new()
	rcol.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var full := members >= maxm
	var join := UiKit.small_btn_icon(Lang.t("Cheia") if full else Lang.t("Entrar"), "guild", _join.bind(int(g.get("id", 0))) if not full else Callable())
	join.disabled = full
	rcol.add_child(join)
	hb.add_child(rcol)
	return res[0]

# ── Helpers de linha com ícone (substituem os emojis inline) ────────────────────────
# Linha "[ícone] rótulo ............ valor" (header da guilda). Ícone só se existir (sem caixa vazia).
func _info_row(icon_key: String, label: String, value_node: Control) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 6)
	if Icons.tex(icon_key) != null:
		var ic := Icons.rect(icon_key, 18); ic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h.add_child(ic)
	var k := Label.new(); k.text = Lang.t(label)
	k.add_theme_font_size_override("font_size", 14); k.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	k.custom_minimum_size = Vector2(110, 0)
	h.add_child(k)
	value_node.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	value_node.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(value_node)
	return h

# "[ícone] texto" — pra badges/linhas curtas (status do inimigo, nível máx, contagem de membros).
func _icon_text(icon_key: String, text: String, color: Color) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 5)
	h.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	if Icons.tex(icon_key) != null:
		var ic := Icons.rect(icon_key, 14); ic.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h.add_child(ic)
	var l := Label.new(); l.text = text
	l.add_theme_font_size_override("font_size", 12); l.add_theme_color_override("font_color", color)
	l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	h.add_child(l)
	return h

# Coluna [ícone-moeda em cima] [SpinBox] — campo de doação (substitui o 🥇/🥈/🥉 de texto).
func _coin_spin(coin_key: String, spin: SpinBox) -> VBoxContainer:
	var box := VBoxContainer.new(); box.add_theme_constant_override("separation", 1)
	box.alignment = BoxContainer.ALIGNMENT_CENTER
	if Icons.tex(coin_key) != null:
		var ic := Icons.rect(coin_key, 16); ic.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		box.add_child(ic)
	spin.custom_minimum_size = Vector2(78, 0)
	box.add_child(spin)
	return box

# Linha do ranking de doadores: medalha (ouro/prata/bronze) p/ top 3, "N." depois.
func _donor_row(i: int, d: Dictionary) -> HBoxContainer:
	var h := HBoxContainer.new(); h.add_theme_constant_override("separation", 8)
	h.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var me := bool(d.get("isMe", false))
	var medal_key := ["gold", "silver", "bronze"][i] if i < 3 else ""
	if medal_key != "" and Icons.tex(medal_key) != null:
		var mi := Icons.rect(medal_key, 16); mi.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		h.add_child(mi)
	else:
		var num := Label.new(); num.text = "%d." % (i + 1)
		num.custom_minimum_size = Vector2(22, 0); num.add_theme_font_size_override("font_size", 13)
		num.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		h.add_child(num)
	var nm := Label.new()
	nm.text = str(d.get("warriorName", "?")) + (Lang.t(" (você)") if me else "")
	nm.add_theme_font_size_override("font_size", 13)
	nm.add_theme_color_override("font_color", UiKit.GOLD if me else UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(nm)
	var val := Label.new(); val.text = _fmt_bronze(int(d.get("donatedBronze", 0)))
	val.add_theme_font_size_override("font_size", 13); val.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	h.add_child(val)
	return h

# ── Confirmações de ações destrutivas ──────────────────────────────────────────────
func _confirm_leave() -> void:
	UiKit.confirm(self, "Sair da guilda? Você perde os bônus e a contribuição.", "Sair", func() -> void: await _leave())

func _confirm_disband() -> void:
	UiKit.confirm(self, "Dissolver a guilda PERMANENTEMENTE? Todos os membros são expulsos.", "Dissolver", func() -> void: await _disband())

func _confirm_kick(pid: int, who: String) -> void:
	UiKit.confirm(self, Lang.t("Expulsar %s da guilda?") % who, "Expulsar", func() -> void: await _kick(pid))

func _confirm_transfer(pid: int, who: String) -> void:
	UiKit.confirm(self, Lang.t("Transferir a liderança para %s? Você deixa de ser líder.") % who, "Transferir", func() -> void: await _transfer(pid), false)

func _confirm_declare(guild_id: int) -> void:
	UiKit.confirm(self, "Declarar guerra de 7 dias?", "Declarar", func() -> void: await _do_declare(guild_id))

# ── Ações (async, 1 chamada → re-refresh) ──────────────────────────────────────────
func _create() -> void:
	if busy: return
	if name_edit == null: return
	var nm := name_edit.text.strip_edges()
	var ds := desc_edit.text.strip_edges() if desc_edit else ""
	busy = true
	var r = await Api.guild_create(nm, ds)
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, Lang.t("Guilda criada!"), 1)
	else:
		UiKit.show_error(status, r)

func _join(id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.guild_join(id)
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, Lang.t("Você entrou na guilda!"), 1)
	else:
		UiKit.show_error(status, r)

func _leave() -> void:
	if busy: return
	busy = true
	var r = await Api.guild_leave()
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, Lang.t("Você saiu da guilda."), 1)
	else:
		UiKit.show_error(status, r)

func _disband() -> void:
	if busy: return
	busy = true
	var r = await Api.guild_disband()
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, Lang.t("Guilda dissolvida."), 1)
	else:
		UiKit.show_error(status, r)

func _kick(pid: int) -> void:
	if busy: return
	busy = true
	var r = await Api.guild_kick(pid)
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, Lang.t("Membro expulso."), 1)
	else:
		UiKit.show_error(status, r)

func _transfer(pid: int) -> void:
	if busy: return
	busy = true
	var r = await Api.guild_transfer(pid)
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, Lang.t("Liderança transferida."), 1)
	else:
		UiKit.show_error(status, r)

func _donate() -> void:
	if busy: return
	if donate_gold == null: return
	var amount := int(donate_gold.value) * 10000 + int(donate_silver.value) * 100 + int(donate_bronze.value)
	if amount <= 0:
		UiKit.flash(status, Lang.t("Informe um valor válido."), 2)
		return
	busy = true
	var r = await Api.guild_donate(amount)
	busy = false
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		var msg := ""
		if bool(j.get("leveledUp", false)):
			msg = Lang.t("A doação subiu a guilda para o nível %d!") % int(j.get("level", 0))
		else:
			msg = Lang.t("Doado! Tesouro: %s") % _fmt_bronze(int(j.get("guildGold", 0)))
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# ── Ações de Guerra de Guilda [GUERRA_GUILDA] ───────────────────────────────────────
func _open_targets() -> void:
	if busy: return
	busy = true
	var r = await Api.guild_war_targets()
	busy = false
	if r.get("ok") and r.get("json") is Array:
		targets = r["json"]
		picking = true
		sub_tab = "war"
		_build_subtab_bar()
		_render_subtab()
	else:
		UiKit.show_error(status, r)

func _cancel_picking() -> void:
	picking = false
	targets = []
	_render_subtab()

func _do_declare(guild_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.guild_war_declare(guild_id)
	busy = false
	if r.get("ok"):
		var msg := Lang.t("Guerra declarada!")
		if r.get("json") is Dictionary:
			msg = str(r["json"].get("message", msg))
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

func _attack(player_id: int) -> void:
	if busy: return
	busy = true
	var r = await Api.guild_war_attack(player_id)
	busy = false
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	var j: Dictionary = r["json"]
	var be = j.get("battleEvents")
	if be is Array and be.size() >= 2:
		# [BATALHA_ANIMADA] replay 3D por cima; _on_battle_over volta e mostra o relatório
		_pending_after = j
		request_battle.emit({"events": be, "scene": str(j.get("scene", "fortress")), "won": bool(j.get("won", false)), "enemy": str(j.get("opponentName", ""))})
	else:
		await _refresh()
		_show_attack_report(j)

# o App chama isto quando o replay 3D termina (volta pra Guilda + mostra o relatório)
func _on_battle_over() -> void:
	var j: Dictionary = _pending_after if _pending_after is Dictionary else {}
	_pending_after = {}
	sub_tab = "war"   # volta pra aba Guerra depois do replay
	await _refresh()
	if not j.is_empty():
		_show_attack_report(j)

func _show_attack_report(j: Dictionary) -> void:
	var won := bool(j.get("won", false))
	var opp := str(j.get("opponentName", "?"))
	var title := (Lang.t("🏆 Vitória contra %s!") % opp) if won else (Lang.t("💀 Derrota contra %s") % opp)
	var rows: Array = []
	rows.append(UiKit.kv("Placar", "%d × %d" % [int(j.get("myKills", 0)), int(j.get("enemyKills", 0))]))
	var loot := str(j.get("loot", ""))
	if loot != "":
		rows.append(_icon_text("gift", loot, UiKit.GOLD_SOFT))
	var log: Array = j.get("battleLog", []) if j.get("battleLog") is Array else []
	UiKit.show_battle_report(self, won, title, rows, log)

# ── helpers locais (formatação + SpinBox de doação) ─────────────────────────────────
func _fmt_time(secs: int) -> String:
	var s := maxi(0, secs)
	var days := s / 86400
	var hours := (s % 86400) / 3600
	var mins := (s % 3600) / 60
	if days > 0:
		return "%dd %02dh" % [days, hours]
	if hours > 0:
		return "%dh %02dmin" % [hours, mins]
	if mins > 0:
		return "%d min" % mins
	return "%d s" % s

func _fmt_bronze(total: int) -> String:
	return UiKit.coin_str(total)

func _spin() -> SpinBox:
	var s := SpinBox.new()
	s.min_value = 0; s.max_value = 999999; s.step = 1; s.value = 0
	s.custom_minimum_size = Vector2(78, 0)
	return s
