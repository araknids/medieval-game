extends Control
# ── Tela GUERRA DE TERRITÓRIO ──────────────────────────────────────────────────────
# Lista os territórios de guerra (GET /api/territory) + o território da própria guilda
# (/api/territory/my) e deixa o LÍDER declarar/cancelar ataque. A batalha roda a cada 6h
# (formação 3×5 da guilda) [GUERRA_FORMACAO]. Só o líder declara. Padrão visual: UiKit
# [PADRAO_UI_GODOT]. [MIGRACAO_GODOT]

signal go_back
signal request_battle(data)   # [GUERRA_GAUNTLET] pede ao App o replay 3D da última guerra do território

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
	box.add_child(UiKit.kv("Bônus de XP", "+%d%%" % int(my_territory.get("xpBonus", 0)), UiKit.OK))
	box.add_child(UiKit.kv("Bônus de bronze", "+%d%%" % int(my_territory.get("bronzeBonus", 0)), UiKit.OK))
	box.add_child(UiKit.kv("Bônus de mineração", "+%d%%" % int(my_territory.get("miningBonus", 0)), UiKit.OK))
	box.add_child(UiKit.kv("Bônus de pesca", "+%d%%" % int(my_territory.get("fishingBonus", 0)), UiKit.OK))
	box.add_child(UiKit.kv("Bônus de XP de quest", "+%d%%" % int(my_territory.get("questXpBonus", 0)), UiKit.OK))
	box.add_child(UiKit.kv("Defesas seguidas", str(int(my_territory.get("defenseStreak", 0)))))
	box.add_child(UiKit.kv("Debuff de defesa", "-%d%%" % int(my_territory.get("debuffPercent", 0)), UiKit.WARN))
	return pc

# ── Card de território (um por entrada do /api/territory) ──────────────────────────
func _territory_card(t) -> Control:
	if not (t is Dictionary):
		return null
	var is_mine := bool(t.get("isMine", false))
	var res := UiKit.card(UiKit.GOLD if is_mine else UiKit.BRONZE)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var nm := Label.new()
	nm.text = str(t.get("displayName", "?"))
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(nm)
	var lore := str(t.get("lore", ""))
	if lore != "":
		box.add_child(UiKit.dim(lore))
	# controlada por (verde se uma guilda segura; "Neutro" se neutro)
	var is_neutral := bool(t.get("isNeutral", false))
	var ctrl_guild := str(t.get("controllingGuild", ""))
	if is_neutral or ctrl_guild == "":
		box.add_child(UiKit.kv("Controlada por", "Neutro", UiKit.TEXT_DIM))
	else:
		box.add_child(UiKit.kv("Controlada por", ctrl_guild, UiKit.OK))
	# próxima batalha (segundos → "Xh Ymin")
	box.add_child(UiKit.kv("Próxima batalha", _fmt_time(int(t.get("secsUntilBattle", 0)))))
	# debuff de defesa / streak (informativo)
	var streak := int(t.get("defenseStreak", 0))
	if streak > 0:
		box.add_child(UiKit.kv("Defesas seguidas", Lang.t("%d (-%d%% debuff)") % [streak, int(t.get("debuffPercent", 0))], UiKit.WARN))
	# guildas declarando ataque
	var declaring = t.get("declaringGuilds", [])
	if declaring is Array and not declaring.is_empty():
		var names: Array = []
		for g in declaring:
			names.append(str(g))
		box.add_child(UiKit.dim(Lang.t("⚔ Declarando:") + " " + ", ".join(names)))
	# assistir o replay 3D da última batalha (qualquer um pode ver) [GUERRA_GAUNTLET]
	box.add_child(UiKit.spacer(4))
	box.add_child(UiKit.small_btn("⚔ Assistir última batalha", _watch_battle.bind(str(t.get("territory", "")))))
	# ── Ação: só o líder da guilda declara/cancela ──
	box.add_child(UiKit.spacer(4))
	var in_guild := bool(guild.get("inGuild", false))
	var is_leader := bool(guild.get("isLeader", false))
	if in_guild and is_leader:
		var territory_id := str(t.get("territory", ""))
		if bool(t.get("myGuildDeclared", false)):
			box.add_child(UiKit.action_danger("Cancelar ataque", _confirm_cancel))
		else:
			box.add_child(UiKit.action("⚔ Declarar ataque", _declare.bind(territory_id)))
	elif in_guild:
		box.add_child(UiKit.dim("Só o líder da guilda pode declarar."))
	else:
		box.add_child(UiKit.dim("Entre numa guilda para participar da guerra de território."))
	return pc

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
