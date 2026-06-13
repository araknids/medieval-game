extends Control
# ── Tela TEMPLO ──────────────────────────────────────────────────────────────────
# GET /api/temple (HP/cura/bênçãos/VIP) + /api/inventory (itens p/ proteção) + /api/warrior (carteira).
# Cura, abençoar (buff), proteger item. Padrão visual: UiKit [PADRAO_UI_GODOT].

signal go_back

var data: Dictionary = {}        # cache de /api/temple
var warrior: Dictionary = {}     # /api/warrior (carteira do header)
var equipped: Array = []         # itens equipados (p/ proteção)
var content: VBoxContainer
var status: Label
var wallet: Label
var busy := false

func _ready() -> void:
	var ui := UiKit.scaffold(self, "⛪ Templo", func() -> void: go_back.emit(), func() -> void: await _refresh(), UiKit.TINT_ADVENTURE)
	content = ui.content
	status = ui.status
	wallet = ui.wallet
	await _refresh()

func _refresh() -> void:
	UiKit.flash(status, "Carregando…", 0)
	var rs = await Api.batch_get(["/api/temple", "/api/inventory", "/api/warrior"])
	var r = rs[0]
	if not (r.get("ok") and r.get("json") is Dictionary):
		UiKit.show_error(status, r)
		return
	data = r["json"]
	var inv = rs[1]
	equipped = []
	if inv.get("ok") and inv.get("json") is Array:
		for it in inv["json"]:
			if it is Dictionary and it.get("equipped", false):
				equipped.append(it)
	var wr = rs[2]
	warrior = wr["json"] if (wr.get("ok") and wr.get("json") is Dictionary) else {}
	_render()

func _render() -> void:
	for c in content.get_children():
		c.queue_free()
	UiKit.flash(status, "", 0)
	UiKit.set_wallet(wallet, warrior)
	_render_blessing()                                  # banner ABENÇOADO (prominente)
	content.add_child(UiKit.section("Estado do Guerreiro"))
	_render_state()
	content.add_child(UiKit.section("Bênçãos"))
	_render_buff_options()
	content.add_child(UiKit.section("Proteção de Itens (%d/%d)" % [int(data.get("protectedCount", 0)), int(data.get("maxProtected", 3))]))
	_render_protection()

# ── Banner ABENÇOADO ───────────────────────────────────────────────────────────
# Estado de bênção em destaque no topo (card dourado + glow) — o jogador vê na hora.
func _render_blessing() -> void:
	var active := str(data.get("activeBuff", ""))
	var active2 := str(data.get("activeBuff2", ""))
	if active == "" and active2 == "":
		content.add_child(UiKit.empty("Sem bênção ativa", "Receba uma bênção abaixo para se fortalecer em combate"))
		return
	var res := UiKit.card(UiKit.GOLD)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var sb: StyleBoxFlat = pc.get_theme_stylebox("panel")
	sb.set_border_width_all(2)
	sb.shadow_color = Color(1.0, 0.8, 0.35, 0.28)             # glow dourado
	sb.shadow_size = 8
	var head := Label.new()
	head.text = "🙏 ABENÇOADO"
	head.add_theme_font_size_override("font_size", 22)
	head.add_theme_color_override("font_color", UiKit.GOLD)
	box.add_child(head)
	if active != "":
		box.add_child(_blessing_line("✨", active, int(data.get("buffSecondsLeft", 0))))
	if active2 != "":
		box.add_child(_blessing_line("👑", active2, int(data.get("buff2SecondsLeft", 0))))
	content.add_child(pc)

func _blessing_line(icon: String, name: String, secs: int) -> HBoxContainer:
	var h := HBoxContainer.new()
	h.add_theme_constant_override("separation", 8)
	var n := Label.new()
	n.text = "%s %s" % [icon, name]
	n.add_theme_font_size_override("font_size", 15)
	n.add_theme_color_override("font_color", UiKit.TEXT)
	n.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(n)
	var t := Label.new()
	t.text = _fmt_left(secs)
	t.add_theme_font_size_override("font_size", 14)
	t.add_theme_color_override("font_color", UiKit.OK)
	h.add_child(t)
	return h

func _fmt_left(secs: int) -> String:
	if secs <= 0:
		return "expirando…"
	var hh := secs / 3600
	var mm := (secs % 3600) / 60
	if hh > 0:
		return "%dh %02dmin restantes" % [hh, mm]
	if mm > 0:
		return "%d min restantes" % mm
	return "%d s restantes" % secs

# ── Estado do guerreiro + curas ────────────────────────────────────────────────
func _render_state() -> void:
	var hp := int(data.get("hpPercent", 100))
	var ko := bool(data.get("isKnockedOut", false))
	content.add_child(UiKit.bar("HP", hp, 100, Color(0.70, 0.22, 0.20), "💀 Inconsciente" if ko else "%d%%" % hp))
	var full := hp >= 100
	if ko:
		content.add_child(UiKit.dim("Seu guerreiro está nocauteado. Cure para voltar ao combate."))
	elif not full:
		content.add_child(UiKit.dim("Regenerando HP… o templo cura instantaneamente."))
	# cura paga/grátis
	if full:
		var done := UiKit.action("✔ HP cheio", Callable())
		done.disabled = true
		content.add_child(done)
	else:
		var cost := int(data.get("healCost", 100))
		var lbl := "Curar (grátis)" if bool(data.get("healFree", false)) else "Curar (%d🥉)" % cost
		content.add_child(UiKit.action(lbl, _heal))
	# VIP heal (CD 10min)
	if bool(data.get("isVip", false)):
		var cd := maxi(0, int(data.get("vipHealCooldownSecs", 0)))
		var vlbl := "✔ HP cheio" if full else ("⏳ VIP CD %dm%02ds" % [cd / 60, cd % 60] if cd > 0 else "👑 VIP Heal (grátis)")
		var vb := UiKit.action(vlbl, _vip_heal)
		vb.disabled = full or cd > 0
		content.add_child(vb)
	# SoulStone heal (CD 30min)
	var ss := int(data.get("soulStones", 0))
	if ss > 0:
		var cd2 := maxi(0, int(data.get("ssHealCooldownSecs", 0)))
		var slbl := "✔ HP cheio" if full else ("⏳ Cooldown %dm%02ds" % [cd2 / 60, cd2 % 60] if cd2 > 0 else "💎 Cura instantânea (1 SoulStone)")
		var sb := UiKit.action(slbl, _soulstone_heal)
		sb.disabled = full or cd2 > 0
		content.add_child(sb)
		content.add_child(UiKit.dim("💎 %d SoulStone(s) · CD 30 min" % ss))

# ── Bênçãos disponíveis ────────────────────────────────────────────────────────
func _render_buff_options() -> void:
	if str(data.get("activeBuff2", "")) == "" and bool(data.get("isVip", false)):
		content.add_child(UiKit.dim("👑 Slot de bênção VIP disponível"))
	var buffs = data.get("buffs", [])
	if not (buffs is Array) or buffs.is_empty():
		content.add_child(UiKit.dim("Nenhuma bênção disponível agora."))
		return
	for b in buffs:
		if b is Dictionary:
			content.add_child(_buff_card(b))

func _buff_card(b: Dictionary) -> PanelContainer:
	var res := UiKit.card()
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	box.add_child(row)
	var left := VBoxContainer.new()
	left.add_theme_constant_override("separation", 2)
	left.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	var nm := Label.new()
	nm.text = "%s %s" % [str(b.get("icon", "✨")), str(b.get("displayName", b.get("id", "?")))]
	nm.add_theme_font_size_override("font_size", 16)
	nm.add_theme_color_override("font_color", UiKit.TEXT)
	left.add_child(nm)
	left.add_child(UiKit.dim("%s · %d🥉" % [str(b.get("effect", "")), int(b.get("bronzeCost", 0))]))
	row.add_child(left)
	var rcol := VBoxContainer.new()
	rcol.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	rcol.add_child(UiKit.small_btn("Abençoar", _apply_buff.bind(str(b.get("id", "")))))
	row.add_child(rcol)
	return pc

# ── Proteção de itens ──────────────────────────────────────────────────────────
func _render_protection() -> void:
	content.add_child(UiKit.dim("Itens protegidos não são perdidos em PvP. Custo: 50🥉/item."))
	if equipped.is_empty():
		content.add_child(UiKit.empty("Nenhum item equipado", "Equipe itens no 🎒 Inventário para protegê-los"))
		return
	for it in equipped:
		content.add_child(_protect_row(it))

func _protect_row(it: Dictionary) -> PanelContainer:
	var col := UiKit.rarity_color(int(it.get("rarity", 1)))
	var res := UiKit.card(col)
	var pc: PanelContainer = res[0]
	var box: VBoxContainer = res[1]
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	box.add_child(row)
	var nm := Label.new()
	nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 15)
	nm.add_theme_color_override("font_color", col)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	row.add_child(nm)
	var id := int(it.get("id", 0))
	if it.get("guarded", false):
		var g := Label.new()
		g.text = "🛡 protegido"
		g.add_theme_font_size_override("font_size", 13)
		g.add_theme_color_override("font_color", UiKit.OK)
		row.add_child(g)
		row.add_child(UiKit.small_btn("Remover", _unprotect.bind(id), true))
	else:
		row.add_child(UiKit.small_btn("Proteger", _protect.bind(id)))
	return pc

# ── Ações: await DIRETO na API; trata o resultado e re-sincroniza ───────────────
func _heal() -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_heal())
	busy = false

func _vip_heal() -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_vip_heal())
	busy = false

func _soulstone_heal() -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_soulstone_heal())
	busy = false

func _apply_buff(buff_id: String) -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_apply_buff(buff_id))
	busy = false

func _protect(id: int) -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_protect(id))
	busy = false

func _unprotect(id: int) -> void:
	if busy: return
	busy = true
	await _do(await Api.temple_unprotect(id))
	busy = false

# r = resultado JÁ resolvido; re-sincroniza e mostra o feedback.
func _do(r: Variant) -> void:
	if r is Dictionary and r.get("ok") and r.get("json") is Dictionary:
		var msg := str(r["json"].get("message", "OK"))
		await _refresh()
		UiKit.flash(status, msg, 1)
	else:
		UiKit.show_error(status, r)
