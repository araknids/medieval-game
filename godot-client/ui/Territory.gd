extends Control
# ── Tela GUERRA DE TERRITÓRIO ──────────────────────────────────────────────────────
# Lista os territórios de guerra (GET /api/territory) + o território da própria guilda
# (/api/territory/my) e deixa o LÍDER declarar/cancelar ataque. A batalha roda a cada 6h
# (formação 3×5 da guilda) [GUERRA_FORMACAO]. Só o líder declara. Padrão visual: UiKit
# [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]

signal go_back
signal request_battle(data)   # [GUERRA_GAUNTLET] pede ao App o replay 3D da última guerra do território

const Icons := preload("res://ui/Icons.gd")   # [AUDITORIA_UI_TERRITORIO] tira de info por ícones + botão de assistir

var territories: Array = []      # GET /api/territory
var my_territory: Dictionary = {}  # GET /api/territory/my
var guild: Dictionary = {}       # GET /api/guild (inGuild / isLeader)
var warrior: Dictionary = {}     # /api/warrior (carteira do header)
var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false

func _ready() -> void:
	var ui := UiKit.scaffold(self, "🗺 Território", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_BATTLE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	# tudo em PARALELO — chamadas independentes
	var rs = await Api.batch_get(["/api/territory", "/api/territory/my", "/api/guild", "/api/warrior"])
	var rt = rs[0]
	if not (rt.get("ok") and rt.get("json") is Array):
		UiKit.show_error(status, rt)
		return
	territories = rt["json"]
	var rm = rs[1]
	my_territory = rm["json"] if (rm.get("ok") and rm.get("json") is Dictionary) else {}
	var rg = rs[2]
	guild = rg["json"] if (rg.get("ok") and rg.get("json") is Dictionary) else {}
	var rw = rs[3]
	warrior = rw["json"] if (rw.get("ok") and rw.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	content.add_child(UiKit.dim("Declare ataque a um território. A batalha roda a cada 6h (formação da guilda). Vencer dá o território + bônus de guilda. Só o líder declara."))
	if bool(my_territory.get("hasTerritory", false)):
		content.add_child(_my_territory_banner())
	content.add_child(UiKit.section("Territórios"))
	if territories.is_empty():
		content.add_child(UiKit.empty("Nenhum território de guerra", "Volte mais tarde — os territórios aparecem quando a guerra está ativa."))
		return
	content.add_child(UiKit.grid(self, territories, _territory_card))

# ── Banner do território da própria guilda (card dourado + bônus) ──────────────────
func _my_territory_banner() -> PanelContainer:
	var res := UiKit.card(UiKit.GOLD)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	sb.shadow_color = Color(1.0, 0.8, 0.35, 0.28)            # glow dourado
	sb.shadow_size = 8
	var head := Label.new()
	head.text = Lang.t("🏰 Sua guilda controla: %s") % str(my_territory.get("displayName", "?"))
	head.add_theme_font_size_override("font_size", 18)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(head)
	# [AUDITORIA_UI_TERRITORIO] bônus em CHIPS [ícone valor] que quebram de linha (compacto vs 7 linhas kv).
	# Só mostra os bônus > 0 (mineração/pesca/quest são exclusivos de cada reino → 0 nos outros).
	var flow := HFlowContainer.new()
	flow.add_theme_constant_override("h_separation", 12)
	flow.add_theme_constant_override("v_separation", 6)
	box.add_child(flow)
	var bonuses := [
		["star",        Lang.t("XP +%d%%") % int(my_territory.get("xpBonus", 0)),            int(my_territory.get("xpBonus", 0)),       Lang.t("Bônus de XP de toda fonte")],
		["bronze",      Lang.t("Bronze +%d%%") % int(my_territory.get("bronzeBonus", 0)),      int(my_territory.get("bronzeBonus", 0)),   Lang.t("Bônus de bronze de toda fonte")],
		["map_mines",   Lang.t("Mineração +%d%%") % int(my_territory.get("miningBonus", 0)),   int(my_territory.get("miningBonus", 0)),   Lang.t("Bônus exclusivo de mineração")],
		["map_fishing", Lang.t("Pesca +%d%%") % int(my_territory.get("fishingBonus", 0)),      int(my_territory.get("fishingBonus", 0)),  Lang.t("Bônus exclusivo de pesca")],
		["world",       Lang.t("XP de quest +%d%%") % int(my_territory.get("questXpBonus", 0)), int(my_territory.get("questXpBonus", 0)), Lang.t("Bônus exclusivo de XP de quest")],
	]
	for b in bonuses:
		if int(b[2]) > 0:
			flow.add_child(_info_chip(str(b[0]), str(b[1]), UiKit.OK, str(b[3])))
	# defesa/debuff sempre (informativo)
	flow.add_child(_info_chip("slot_shield", Lang.t("Defesas: %d") % int(my_territory.get("defenseStreak", 0)), UiKit.TEXT, Lang.t("Batalhas defendidas em sequência")))
	var debuff := int(my_territory.get("debuffPercent", 0))
	if debuff > 0:
		flow.add_child(_info_chip("warning", "-%d%%" % debuff, UiKit.WARN, Lang.t("Debuff de defesa por streak (te enfraquece ao defender)")))
	return pc

# ── Card de território (um por entrada do /api/territory) ──────────────────────────
func _territory_card(t) -> Control:
	if not (t is Dictionary):
		return null
	var is_mine := bool(t.get("isMine", false))
	var res := UiKit.card(UiKit.GOLD if is_mine else UiKit.BRONZE)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	# ── Header: nome (esquerda) + assistir (ícone à direita, só quando há replay) ──
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 8)
	box.add_child(header)
	var nm := Label.new()
	nm.text = str(t.get("displayName", "?"))
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", UiKit.GOLD)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	header.add_child(nm)
	# [AUDITORIA_UI_TERRITORIO] "assistir" virou ÍCONE no canto e só aparece quando HÁ replay (some o clique-morto F3)
	if bool(t.get("hasLastBattle", false)):
		header.add_child(_watch_icon_btn(str(t.get("territory", ""))))
	var lore := str(t.get("lore", ""))
	if lore != "":
		box.add_child(UiKit.dim(lore))
	# ── Tira de info: [controlador] [próxima batalha] [streak] — ícones+valor no lugar de 3 linhas kv ──
	box.add_child(_info_strip(t))
	# guildas declarando ataque
	var declaring = t.get("declaringGuilds", [])
	if declaring is Array and not declaring.is_empty():
		var names: Array = []
		for g in declaring:
			names.append(str(g))
		box.add_child(_info_chip("node_combat", Lang.t("Declarando:") + " " + ", ".join(names), UiKit.TEXT_DIM, Lang.t("Guildas que vão atacar este território no próximo ciclo")))
	# ── Ação primária: encolhida e alinhada à DIREITA (não estica mais o card inteiro) [F1] ──
	var in_guild := bool(guild.get("inGuild", false))
	var is_leader := bool(guild.get("isLeader", false))
	if in_guild and is_leader:
		var territory_id := str(t.get("territory", ""))
		box.add_child(UiKit.spacer(2))
		var arow := HBoxContainer.new()
		arow.alignment = BoxContainer.ALIGNMENT_END   # botão no min-size, encostado à direita
		if bool(t.get("myGuildDeclared", false)):
			arow.add_child(UiKit.action_danger("Cancelar ataque", _confirm_cancel))
		else:
			var declare_btn := UiKit.action("⚔ Declarar ataque", _declare.bind(territory_id))
			Icons.label_button(declare_btn, "declare_war", "⚔ Declarar ataque")   # estandarte (fallback: mantém ⚔)
			declare_btn.add_theme_constant_override("icon_max_width", 22)
			arow.add_child(declare_btn)
		box.add_child(arow)
	elif in_guild:
		box.add_child(UiKit.dim("Só o líder da guilda pode declarar."))
	else:
		box.add_child(UiKit.dim("Entre numa guilda para participar da guerra de território."))
	return pc

# [AUDITORIA_UI_TERRITORIO] Tira compacta de info do território (controlador · próxima batalha · streak).
func _info_strip(t: Dictionary) -> Control:
	var row := HFlowContainer.new()
	row.add_theme_constant_override("h_separation", 14)
	row.add_theme_constant_override("v_separation", 4)
	# controlador (coroa): guilda (verde) ou "Neutro" (cinza)
	var is_neutral := bool(t.get("isNeutral", false))
	var ctrl_guild := str(t.get("controllingGuild", ""))
	if is_neutral or ctrl_guild == "":
		row.add_child(_info_chip("node_boss", Lang.t("Neutro"), UiKit.TEXT_DIM, Lang.t("Nenhuma guilda controla — ataque para tomar")))
	else:
		row.add_child(_info_chip("node_boss", ctrl_guild, UiKit.OK, Lang.t("Guilda que controla este território")))
	# próxima batalha (ampulheta)
	row.add_child(_info_chip("hourglass", _fmt_time(int(t.get("secsUntilBattle", 0))), UiKit.TEXT, Lang.t("Quando a próxima batalha do território é resolvida")))
	# streak de defesa (escudo) — só quando > 0
	var streak := int(t.get("defenseStreak", 0))
	if streak > 0:
		row.add_child(_info_chip("slot_shield", "%d (-%d%%)" % [streak, int(t.get("debuffPercent", 0))], UiKit.WARN, Lang.t("Defesas seguidas — o defensor ganha um debuff acumulado")))
	return row

# Chip "[ícone] valor" com tooltip explicativo (substitui as linhas kv verbosas). [AUDITORIA_UI_TERRITORIO]
func _info_chip(icon_key: String, text: String, col: Color, tip := "") -> Control:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 5)
	if tip != "":
		h.tooltip_text = tip
		h.mouse_filter = Control.MOUSE_FILTER_STOP
	h.add_child(Icons.rect(icon_key, 18))
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", 13)
	l.add_theme_color_override("font_color", col)
	l.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.add_child(l)
	return h

# Botão de ASSISTIR replay como ícone discreto (flat) no header do card. Fallback ▶ até o ícone importar.
func _watch_icon_btn(territory: String) -> Button:
	var b := Button.new()
	b.flat = true
	b.tooltip_text = Lang.t("Assistir a última batalha (replay 3D)")
	b.custom_minimum_size = Vector2(32, 32)
	b.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	if Icons.set_icon(b, "watch"):
		b.add_theme_constant_override("icon_max_width", 24)
	else:
		b.text = "▶"
		b.add_theme_font_size_override("font_size", 16)
	b.pressed.connect(_watch_battle.bind(territory))
	return b

# ── Tempo: segundos → "Xh Ymin" / "Ymin" / "agora" ─────────────────────────────────
func _fmt_time(secs: int) -> String:
	if secs <= 0:
		return "a qualquer momento"
	var hh := secs / 3600
	var mm := (secs % 3600) / 60
	if hh > 0:
		return Lang.t("%dh %dmin") % [hh, mm]
	if mm > 0:
		return Lang.t("%dmin") % mm
	return Lang.t("%ds") % secs

# ── Ações (await DIRETO na API; em sucesso flash + refresh; em falha mostra o erro) ─
func _declare(territory: String) -> void:
	if busy: return
	busy = true
	var r = await Api.territory_declare(territory)
	busy = false
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary:
		var msg := str(r["json"].get("message", Lang.t("Ataque declarado!")))
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

func _confirm_cancel() -> void:
	UiKit.confirm(self, "Cancelar o ataque declarado?", "Cancelar ataque", func() -> void: await _do_cancel())

func _do_cancel() -> void:
	if busy: return
	busy = true
	var r = await Api.territory_cancel()
	busy = false
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary:
		var msg := str(r["json"].get("message", Lang.t("Ataque cancelado.")))
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)

# [GUERRA_GAUNTLET] busca o replay da última guerra e pede o 3D ao App (via Shell → request_battle).
func _watch_battle(territory: String) -> void:
	if busy: return
	busy = true
	UiKit.flash(status, "Carregando replay…", 0)
	var r = await Api.territory_replay(territory)
	busy = false
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary and bool(r["json"].get("hasReplay", false)):
		var j: Dictionary = r["json"]
		request_battle.emit({
			"events": j.get("events", []),
			"scene": str(j.get("scene", "castle")),
			"war": true,
			"won": str(j.get("winner", "")) == str(j.get("attacker", "")),
			"enemy": str(j.get("defender", "")),
		})
	else:
		UiKit.flash(status, "Sem batalha pra assistir ainda.", 1)
