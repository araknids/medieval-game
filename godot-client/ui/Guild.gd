extends Control
# ── Tela GUILDA ───────────────────────────────────────────────────────────────────
# Espelha loadGuild/renderGuildPanel/renderNoGuildPanel do app.js.
# GET /api/guild + /api/warrior (carteira) → se inGuild: painel (info + membros + doação +
#   ranking + sair/dissolver, e p/ líder: expulsar/transferir). Se não: criar guilda + lista
#   p/ entrar (GET /api/guild/list, só buscada quando sem guilda).
# Inclui guerra de guilda + a FORMAÇÃO 3×5 (líder posiciona os membros). Territórios = tela à parte.
# Padrão visual: UiKit [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]

signal go_back

const F_LANES := 3   # [GUERRA_FORMACAO] colunas (lanes) do tabuleiro de guerra
const F_DEPTH := 5   # linhas (profundidade): 0 = frente (luta 1º) … 4 = retaguarda

var _formation_cells := {}     # "lane:depth" -> OptionButton (editor do líder)
var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false
var data: Dictionary = {}      # detalhe da guilda (quando inGuild)
var warrior: Dictionary = {}   # /api/warrior (carteira do header)
var guild_list: Array = []     # lista de guildas (quando sem guilda)
var war: Dictionary = {}       # /api/guild/war (status da guerra; atWar:false se sem guilda) [GUERRA_GUILDA]
var targets: Array = []        # guildas rivais elegíveis (carregadas ao escolher declarar)
var picking := false           # estado de UI: escolhendo alvo de guerra
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
	await _refresh()

func _refresh() -> void:
	UiKit.show_loading(self)
	# [AUDIT] /api/guild/war entrou no batch (paralelo) — antes era um await sequencial extra a cada refresh
	var rs = await Api.batch_get(["/api/guild", "/api/warrior", "/api/guild/war"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	data = r["json"]
	var wr = rs[1]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	# status da guerra (seguro mesmo sem guilda → atWar:false) [GUERRA_GUILDA]
	picking = false
	targets = []
	var wsr = rs[2]
	war = wsr["json"] if (wsr.get("ok") and wsr.get("json") is Dictionary) else {}
	if bool(data.get("inGuild", false)):
		guild_list = []
		_render_panel()
	else:
		# sem guilda → busca a lista pra entrar, depois renderiza o painel de criação
		var lr = await Api.guild_list()
		guild_list = lr["json"] if (lr.get("ok") and lr.get("json") is Array) else []
		_render_no_guild()

func _clear() -> void:
	for c in content.get_children():
		c.queue_free()
	name_edit = null; desc_edit = null
	donate_gold = null; donate_silver = null; donate_bronze = null
	UiKit.hide_loading()
	UiKit.set_wallet(wallet, warrior)

# ── Painel COM guilda ──────────────────────────────────────────────────────────────
func _render_panel() -> void:
	_clear()
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
	head_box.add_child(UiKit.kv("🏦 Tesouro", _fmt_bronze(int(g.get("treasuryBronze", 0)))))
	head_box.add_child(UiKit.kv("👥 Membros", "%d/%d" % [members.size(), int(g.get("maxMembers", 0))]))   # nº não traduz
	# bônus
	var xpb := int(g.get("xpBonus", 0)); var dropb := int(g.get("dropBonus", 0)); var brb := int(g.get("bronzeBonus", 0))
	if xpb != 0 or dropb != 0 or brb != 0:
		var bl := Label.new()
		bl.text = Lang.t("Bônus: +%d%% XP · +%d%% drop · +%d%% bronze") % [xpb, dropb, brb]
		bl.add_theme_color_override("font_color", UiKit.OK)
		bl.add_theme_font_size_override("font_size", 12)
		head_box.add_child(bl)
	# progresso de nível [GUILD_LEVEL_GOLD]
	var maxed := int(g.get("level", 1)) >= int(g.get("maxLevel", 10))
	if maxed:
		var ml := Label.new()
		ml.text = Lang.t("⭐ Nível máximo (Lv.%d) — total contribuído: %s") % [int(g.get("maxLevel", 10)), _fmt_bronze(int(g.get("lifetimeGold", 0)))]
		ml.add_theme_color_override("font_color", UiKit.GOLD)
		ml.add_theme_font_size_override("font_size", 12)
		head_box.add_child(ml)
	else:
		head_box.add_child(UiKit.bar(Lang.t("Nível"), int(g.get("levelProgressPct", 0)), 100, UiKit.GOLD,
			Lang.t("Lv.%d → Lv.%d  (faltam %s)") % [int(g.get("level", 1)), int(g.get("level", 1)) + 1, _fmt_bronze(int(g.get("goldToNextLevel", 0)))]))
	content.add_child(head_res[0])

	# ── Membros ── [SEM_SCROLL] grid 2-col (era 1 membro por linha)
	content.add_child(UiKit.section(Lang.t("Membros (%d)") % members.size()))
	content.add_child(UiKit.grid(self, members, func(mm): return _member_row(mm, is_leader) if mm is Dictionary else null, false, 260, 2))

	# ── Doar ──
	content.add_child(UiKit.section("Doar para o tesouro"))
	var donate_row := HBoxContainer.new(); donate_row.add_theme_constant_override("separation", 6)
	donate_gold = _spin()
	donate_silver = _spin()
	donate_bronze = _spin()
	donate_row.add_child(_labeled("🥇 Ouro", donate_gold))
	donate_row.add_child(_labeled("🥈 Prata", donate_silver))
	donate_row.add_child(_labeled("🥉 Bronze", donate_bronze))
	content.add_child(donate_row)
	content.add_child(UiKit.action("💰 Doar", _donate))

	# ── Sair / Dissolver ──
	content.add_child(UiKit.section("Liderança"))
	if is_leader:
		content.add_child(UiKit.action_danger("💀 Dissolver Guilda", _confirm_disband))
	else:
		content.add_child(UiKit.action_danger("🚪 Sair da Guilda", _confirm_leave))

	# ── Top Doadores ──
	var rank: Array = g.get("donationRank", []) if g.get("donationRank") is Array else []
	content.add_child(UiKit.section("🏆 Top Doadores"))
	if rank.is_empty():
		content.add_child(UiKit.dim("— sem doações ainda —"))
	else:
		var i := 0
		for d in rank:
			if d is Dictionary:
				var medal := "🥇" if i == 0 else ("🥈" if i == 1 else ("🥉" if i == 2 else "%d." % (i + 1)))
				var me := bool(d.get("isMe", false))
				var row := UiKit.kv("%s %s%s" % [medal, str(d.get("warriorName", "?")), (Lang.t(" (você)") if me else "")], _fmt_bronze(int(d.get("donatedBronze", 0))), UiKit.GOLD if me else UiKit.TEXT)
				content.add_child(row)
			i += 1

	# ── Guerra de Guilda [GUERRA_GUILDA] ──
	_render_war()

	# ── Formação 3×5 da guerra [GUERRA_FORMACAO] ──
	_render_formation(members, is_leader)

func _member_row(mm: Dictionary, is_leader: bool) -> PanelContainer:
	var me := bool(mm.get("isMe", false))
	var ml := bool(mm.get("isLeader", false))
	var res := UiKit.card()
	var box: VBoxContainer = res[1]
	var hb := HBoxContainer.new(); hb.add_theme_constant_override("separation", 8)
	box.add_child(hb)
	var title := str(mm.get("title", ""))
	var nm := Label.new()
	nm.text = (title + " " if title != "" else "") + str(mm.get("warriorName", "?")) + (" 👑" if ml else "") + (Lang.t(" (você)") if me else "")
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", UiKit.GOLD if me else UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	hb.add_child(nm)
	var fat := int(mm.get("fatiguePct", 0))
	if fat > 0:
		var fl := Label.new(); fl.text = "😓 -%d%%" % fat
		fl.add_theme_color_override("font_color", UiKit.ERR)
		fl.add_theme_font_size_override("font_size", 11)
		hb.add_child(fl)
	# botões do líder (não em si mesmo / kick não no líder)
	if is_leader and not me:
		var pid := int(mm.get("playerId", 0))
		var who := str(mm.get("warriorName", "?"))
		if not ml:
			hb.add_child(UiKit.small_btn("Expulsar", _confirm_kick.bind(pid, who), true))
		hb.add_child(UiKit.small_btn("Transferir", _confirm_transfer.bind(pid, who)))
	return res[0]

# ── Guerra de Guilda [GUERRA_GUILDA] ────────────────────────────────────────────────
# Renderiza a seção de guerra a partir do cache `war` (carregado no _refresh).
func _render_war() -> void:
	content.add_child(UiKit.section("⚔ Guerra de Guilda"))
	content.add_child(UiKit.dim("Guerra de 7 dias contra uma guilda rival. Quem fizer mais kills leva 25% do ouro. Ambas precisam ter controlado um território."))
	if bool(war.get("atWar", false)):
		_render_war_active()
	else:
		_render_war_idle()

func _render_war_active() -> void:
	var my_kills := int(war.get("myKills", 0))
	var enemy_kills := int(war.get("enemyKills", 0))
	# placar
	var res := UiKit.card(UiKit.ERR)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	var head := Label.new()
	head.text = Lang.t("Em guerra com %s") % str(war.get("enemyGuildName", "?"))
	head.add_theme_font_size_override("font_size", 18)
	head.add_theme_color_override("font_color", UiKit.ERR)
	box.add_child(head)
	box.add_child(UiKit.kv("Placar", Lang.t("%d × %d (você × inimigo)") % [my_kills, enemy_kills]))
	box.add_child(UiKit.kv("Termina em", _fmt_time(int(war.get("secondsLeft", 0)))))
	content.add_child(pc)
	# inimigos
	var enemies: Array = war.get("enemies", []) if war.get("enemies") is Array else []
	content.add_child(UiKit.section("Inimigos"))
	if enemies.is_empty():
		content.add_child(UiKit.dim("— nenhum inimigo disponível para atacar agora —"))
	else:
		content.add_child(UiKit.grid(self, enemies, _enemy_card))

func _enemy_card(e: Variant) -> Control:
	var en: Dictionary = e if e is Dictionary else {}
	var res := UiKit.card()
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	box.add_child(row)
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
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
	var hl := Label.new()
	hl.text = Lang.t("Nv %d · ❤ %d%%") % [int(en.get("level", 1)), hp]
	hl.add_theme_font_size_override("font_size", 12)
	hl.add_theme_color_override("font_color", UiKit.ERR if ko else UiKit.TEXT_DIM)
	left.add_child(hl)
	var rcol := VBoxContainer.new()
	rcol.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	row.add_child(rcol)
	var pid := int(en.get("playerId", 0))
	if ko:
		rcol.add_child(UiKit.dim("💀 KO"))          # [AUDIT] status, não botão (não parece clicável)
	elif shielded:
		rcol.add_child(UiKit.dim("🛡 Protegido"))   # [AUDIT] idem
	else:
		rcol.add_child(UiKit.small_btn("⚔ Atacar", _attack.bind(pid), true))
	return pc

func _render_war_idle() -> void:
	if not bool(data.get("isLeader", false)):
		content.add_child(UiKit.dim("Só o líder pode declarar guerra."))
		return
	if picking:
		content.add_child(UiKit.section("Escolha uma guilda rival"))
		if targets.is_empty():
			content.add_child(UiKit.empty("Nenhuma guilda elegível.", "Rivais precisam ter controlado um território."))
		else:
			content.add_child(UiKit.grid(self, targets, func(t): return _target_row(t) if t is Dictionary else null, false, 260, 2))   # [SEM_SCROLL]
		content.add_child(UiKit.action("Cancelar", _cancel_picking))
	else:
		content.add_child(UiKit.action("⚔ Declarar Guerra", _open_targets))

func _target_row(t: Dictionary) -> PanelContainer:
	var res := UiKit.card()
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	box.add_child(row)
	var nm := Label.new()
	nm.text = Lang.t("%s   Nv.%d") % [str(t.get("name", "?")), int(t.get("level", 1))]
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(nm)
	var gid := int(t.get("id", 0))
	row.add_child(UiKit.small_btn("⚔ Declarar", _confirm_declare.bind(gid), true))
	return pc

# ── Formação 3×5 da guerra [GUERRA_FORMACAO] ────────────────────────────────────────
# Tabuleiro de 3 lanes (colunas) × 5 de profundidade (linhas). A FRENTE (linha 1) luta primeiro
# e leva o HP restante pro próximo da coluna; vence quem ganha 2 das 3 lanes. Só o LÍDER edita
# (um OptionButton por célula); membros veem só leitura. Salvar posiciona + define o roster.
func _render_formation(members: Array, is_leader: bool) -> void:
	content.add_child(UiKit.section("⚔ Formação de Guerra (3 lanes × 5)"))
	content.add_child(UiKit.dim("Cada coluna é um gauntlet: a FRENTE luta primeiro e leva o HP restante pro próximo. Vence quem ganha 2 das 3 lanes. Células vazias = auto-preenchidas pelos membros mais descansados."))
	_formation_cells = {}
	# formação atual: "lane:depth" -> membro
	var placed := {}
	for m in members:
		if m is Dictionary and int(m.get("warLane", -1)) >= 0 and int(m.get("warDepth", -1)) >= 0:
			placed["%d:%d" % [int(m.get("warLane", 0)), int(m.get("warDepth", 0))]] = m
	var grid := GridContainer.new()
	grid.columns = F_LANES + 1   # 1 coluna de rótulo de profundidade + 3 lanes
	grid.add_theme_constant_override("h_separation", 6)
	grid.add_theme_constant_override("v_separation", 6)
	# cabeçalho: célula vazia + Lane 1/2/3
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
	content.add_child(grid)
	if is_leader:
		content.add_child(UiKit.action("💾 Salvar formação", _save_formation))
	else:
		content.add_child(UiKit.dim("Só o líder pode posicionar a formação."))

# Rótulo simples (cabeçalho / profundidade) do tabuleiro.
func _f_label(text: String, size: int, col: Color) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", col)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.custom_minimum_size = Vector2(64, 0)
	return l

# Dropdown de uma célula (líder): "—" (vazia) + cada membro. Pré-seleciona quem já está na célula.
func _f_option(members: Array, current: Dictionary) -> OptionButton:
	var ob := OptionButton.new()
	ob.custom_minimum_size = Vector2(112, 30)
	ob.add_item("—", 0)   # vazio
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

# Célula só-leitura (membro não-líder): nome de quem está, ou vazio.
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

# Coleta as células preenchidas → valida duplicata → POST /war-formation.
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
		var parts := key.split(":")
		slots.append({"playerId": pid, "lane": int(parts[0]), "depth": int(parts[1])})
	busy = true
	var r = await Api.guild_set_formation(slots)
	busy = false
	if r.get("ok"):
		await _refresh()
		UiKit.flash(status, Lang.t("Formação salva."), 1)
	else:
		UiKit.show_error(status, r)

# ── Painel SEM guilda ──────────────────────────────────────────────────────────────
func _render_no_guild() -> void:
	_clear()
	content.add_child(UiKit.empty("Você não pertence a nenhuma guilda.", "Crie a sua ou entre numa existente abaixo"))

	# criar
	content.add_child(UiKit.section("Criar nova guilda  (custa 100 bronze)"))
	var res := UiKit.card()
	var box: VBoxContainer = res[1]
	name_edit = UiKit.input("Nome (3-30 chars)"); name_edit.max_length = 30
	name_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	box.add_child(name_edit)
	desc_edit = UiKit.input("Descrição (opcional)"); desc_edit.max_length = 120
	desc_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	box.add_child(desc_edit)
	box.add_child(UiKit.action("🛡 Criar Guilda", _create))
	content.add_child(res[0])

	# lista p/ entrar
	content.add_child(UiKit.section("Guildas existentes"))
	if guild_list.is_empty():
		content.add_child(UiKit.empty("Nenhuma guilda criada ainda.", "Seja o primeiro a fundar uma!"))
	else:
		content.add_child(UiKit.grid(self, guild_list, func(g): return _guild_list_row(g) if g is Dictionary else null, false, 260, 2))   # [SEM_SCROLL]

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
	var cl := Label.new(); cl.text = "👥 %d/%d" % [members, maxm]
	cl.add_theme_font_size_override("font_size", 12)
	cl.add_theme_color_override("font_color", UiKit.TEXT_DIM)
	left.add_child(cl)
	hb.add_child(left)
	var rcol := VBoxContainer.new()
	rcol.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var full := members >= maxm
	var join := UiKit.small_btn("Cheia" if full else "Entrar", _join.bind(int(g.get("id", 0))) if not full else Callable())
	join.disabled = full
	rcol.add_child(join)
	hb.add_child(rcol)
	return res[0]

# ── Confirmações de ações destrutivas ──────────────────────────────────────────────
func _confirm_leave() -> void:
	UiKit.confirm(self, "Sair da guilda? Você perde os bônus e a contribuição.", "🚪 Sair", func() -> void: await _leave())

func _confirm_disband() -> void:
	UiKit.confirm(self, "Dissolver a guilda PERMANENTEMENTE? Todos os membros são expulsos.", "💀 Dissolver", func() -> void: await _disband())

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
			msg = Lang.t("🎉 A doação subiu a guilda para o nível %d!") % int(j.get("level", 0))
		else:
			msg = Lang.t("Doado! Tesouro: %s") % _fmt_bronze(int(j.get("guildGold", 0)))
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# ── Ações de Guerra de Guilda [GUERRA_GUILDA] ───────────────────────────────────────
# Carrega as guildas rivais e entra no modo de escolha de alvo (re-render puro de UI).
func _open_targets() -> void:
	if busy: return
	busy = true
	var r = await Api.guild_war_targets()
	busy = false
	if r.get("ok") and r.get("json") is Array:
		targets = r["json"]
		picking = true
		_render_panel()
	else:
		UiKit.show_error(status, r)

func _cancel_picking() -> void:
	picking = false
	targets = []
	_render_panel()

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
	if r.get("ok") and r.get("json") is Dictionary:
		var j: Dictionary = r["json"]
		var won := bool(j.get("won", false))
		var opp := str(j.get("opponentName", "?"))
		var head := Lang.t("🏆 Venceu") if won else Lang.t("💀 Perdeu")
		var loot := str(j.get("loot", ""))
		var loot_txt := " · %s" % loot if loot != "" else ""
		await _refresh()
		UiKit.flash(status, Lang.t("%s contra %s%s · placar %d×%d") % [head, opp, loot_txt, int(j.get("myKills", 0)), int(j.get("enemyKills", 0))], 1)
	else:
		UiKit.show_error(status, r)

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
	return UiKit.coin_str(total)   # [MOEDA] ouro/prata/bronze por extenso (distinguível em texto)

func _spin() -> SpinBox:
	var s := SpinBox.new()
	s.min_value = 0; s.max_value = 999999; s.step = 1; s.value = 0
	s.custom_minimum_size = Vector2(90, 0)
	return s

func _labeled(text: String, node: Control) -> VBoxContainer:
	var box := VBoxContainer.new()
	box.add_child(UiKit.dim(text))
	box.add_child(node)
	return box
