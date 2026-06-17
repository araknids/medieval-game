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
	content.add_child(UiKit.section("🙏 Bênçãos"))
	_render_buff_options()
	content.add_child(UiKit.section(Lang.t("Proteção de Itens (%d/%d)") % [int(data.get("protectedCount", 0)), int(data.get("maxProtected", 3))]))
	_render_protection()

# ── Banner ABENÇOADO ───────────────────────────────────────────────────────────
# Estado de bênção em destaque no topo (card dourado + glow) — o jogador vê na hora.
func _render_blessing() -> void:
	var active := str(data.get("activeBuff", ""))
	var active2 := str(data.get("activeBuff2", ""))
	if active == "" and active2 == "":
		content.add_child(UiKit.dim("Sem bênção ativa — receba uma abaixo para se fortalecer."))
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
		return Lang.t("expirando…")
	var hh := secs / 3600
	var mm := (secs % 3600) / 60
	if hh > 0:
		return Lang.t("%dh %02dmin restantes") % [hh, mm]
	if mm > 0:
		return Lang.t("%d min restantes") % mm
	return Lang.t("%d s restantes") % secs

# ── Estado do guerreiro + curas ────────────────────────────────────────────────
func _render_state() -> void:
	var hp := int(data.get("hpPercent", 100))
	var ko := bool(data.get("isKnockedOut", false))
	content.add_child(UiKit.bar("HP", hp, 100, Color(0.70, 0.22, 0.20), Lang.t("💀 Inconsciente") if ko else "%d%%" % hp))
	var full := hp >= 100
	if full:
		content.add_child(UiKit.dim("✔ HP cheio."))
		return
	if ko:
		content.add_child(UiKit.dim("Nocauteado — cure para voltar ao combate."))
	# [TEMPLO_UI] curas viram ÍCONES (sem botão gigante): básica · VIP · SoulStone. Custo/CD no rótulo + tooltip.
	var row := HBoxContainer.new(); row.add_theme_constant_override("separation", 10)
	var cost := int(data.get("healCost", 100))
	var free := bool(data.get("healFree", false))
	var rlbl := Lang.t("Grátis") if free else UiKit.coin_str(cost)
	row.add_child(_heal_btn("heal_basic", "❤", rlbl, _heal, true, Lang.t("Curar HP no templo"), UiKit.OK if free else UiKit.GOLD_SOFT))
	if bool(data.get("isVip", false)):
		var cd := maxi(0, int(data.get("vipHealCooldownSecs", 0)))
		var vlbl := (Lang.t("%dm%02ds") % [cd / 60, cd % 60]) if cd > 0 else Lang.t("Grátis")
		row.add_child(_heal_btn("heal_vip", "👑", vlbl, _vip_heal, cd <= 0, Lang.t("Cura VIP grátis (CD 10 min)"), UiKit.WARN if cd > 0 else UiKit.OK))
	var ss := int(data.get("soulStones", 0))
	if ss > 0:
		var cd2 := maxi(0, int(data.get("ssHealCooldownSecs", 0)))
		var slbl := (Lang.t("%dm%02ds") % [cd2 / 60, cd2 % 60]) if cd2 > 0 else "1 💎"
		row.add_child(_heal_btn("heal_soul", "💎", slbl, _soulstone_heal, cd2 <= 0, Lang.t("Cura instantânea — 1 SoulStone (CD 30 min) · você tem %d") % ss, UiKit.WARN if cd2 > 0 else UiKit.GOLD_SOFT))
	content.add_child(row)

# Botão de cura ICON-PRIMARY (ícone + custo/CD). Desabilitado em CD/sem-recurso → apagado.
func _heal_btn(icon_key: String, emoji: String, label: String, cb: Callable, enabled: bool, tip: String, accent: Color) -> Button:
	var b := UiKit.icon_choice_btn(icon_key, emoji, label, cb if enabled else Callable(), accent, true)   # compact (~50% menor)
	b.tooltip_text = tip
	if not enabled:
		b.disabled = true
		b.modulate = Color(1, 1, 1, 0.5)
	return b

# ── Bênçãos disponíveis ────────────────────────────────────────────────────────
func _render_buff_options() -> void:
	if str(data.get("activeBuff2", "")) == "" and bool(data.get("isVip", false)):
		content.add_child(UiKit.dim("👑 Slot de bênção VIP disponível"))
	var buffs = data.get("buffs", [])
	if not (buffs is Array) or buffs.is_empty():
		content.add_child(UiKit.dim("Nenhuma bênção disponível agora."))
		return
	var cells: Array = []
	for b in buffs:
		if b is Dictionary:
			cells.append(b)
	content.add_child(UiKit.grid(self, cells, _buff_cell, false, 230, 3))   # grid bem compacto (3 col)

# [TEMPLO_UI] Bênção = CARD CLICÁVEL inteiro (clica = aplica), sem botão gigante. Header [ícone] nome +
# custo; sub = efeito. Efeito completo no tooltip do card. [CARD_BOTAO]
func _buff_cell(b: Dictionary) -> Control:
	var eff_txt := str(b.get("effect", ""))
	var on_click := func() -> void: _apply_buff(str(b.get("id", "")))
	var res := UiKit.clickable_card(UiKit.GOLD_SOFT, on_click, true, eff_txt)
	var pc: PanelContainer = res[0]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(8)   # padding menor → card mais baixo
	var box: VBoxContainer = res[1]
	box.add_theme_constant_override("separation", 2)
	# header: ícone (emoji da bênção) + nome + custo
	var top := HBoxContainer.new(); top.add_theme_constant_override("separation", 6)
	var ic := Label.new(); ic.text = str(b.get("icon", "✨")); ic.add_theme_font_size_override("font_size", 18)
	top.add_child(ic)
	var nm := Label.new(); nm.text = str(b.get("displayName", b.get("id", "?")))
	nm.add_theme_font_size_override("font_size", 15); nm.add_theme_color_override("font_color", UiKit.TEXT)
	nm.size_flags_horizontal = Control.SIZE_EXPAND_FILL; nm.clip_text = true
	top.add_child(nm)
	top.add_child(UiKit.coin_box(int(b.get("bronzeCost", 0)), 14))
	box.add_child(top)
	# efeito (1 linha, clipada — completo no tooltip)
	if eff_txt != "":
		var eff := Label.new()
		eff.text = eff_txt
		eff.add_theme_font_size_override("font_size", 12)
		eff.add_theme_color_override("font_color", UiKit.TEXT_DIM)
		eff.clip_text = true
		box.add_child(eff)
	return res[0]

# ── Proteção de itens ──────────────────────────────────────────────────────────
func _render_protection() -> void:
	# [TEMPLO_UI] intro compacto numa LINHA só: explicação + custo (autowrap OFF p/ não espremer no HBox).
	var prot := HBoxContainer.new(); prot.add_theme_constant_override("separation", 5)
	var pa := UiKit.dim("Protegidos não se perdem em PvP ·")
	pa.autowrap_mode = TextServer.AUTOWRAP_OFF; pa.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	prot.add_child(pa)
	var coin := UiKit.coin_box(50, 14); coin.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	prot.add_child(coin)
	var pb := UiKit.dim("/item")
	pb.autowrap_mode = TextServer.AUTOWRAP_OFF; pb.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	prot.add_child(pb)
	content.add_child(prot)
	if equipped.is_empty():
		content.add_child(UiKit.dim("Nenhum item equipado — equipe no 🎒 Inventário para proteger."))
		return
	content.add_child(UiKit.grid(self, equipped, _protect_cell, false, 230, 3))   # grid bem compacto (3 col)

# [TEMPLO_UI] Item de proteção = CARD CLICÁVEL (clica = protege/remove). Selo de escudo quando protegido;
# sem botão. Tooltip explica a ação. [CARD_BOTAO]
func _protect_cell(it: Dictionary) -> Control:
	var col := UiKit.rarity_color(int(it.get("rarity", 1)))
	var guarded := bool(it.get("guarded", false))
	var id := int(it.get("id", 0))
	var on_click := func() -> void:
		if guarded:
			_unprotect(id)
		else:
			_protect(id)
	var tip := Lang.t("Tocar para remover a proteção") if guarded else Lang.t("Tocar para proteger (não se perde em PvP) · custo 50 bronze")
	var res := UiKit.clickable_card(col, on_click, true, tip)
	var pc: PanelContainer = res[0]
	(pc.get_theme_stylebox("panel") as StyleBoxFlat).set_content_margin_all(8)   # padding menor → card mais baixo
	var box: VBoxContainer = res[1]
	box.add_theme_constant_override("separation", 2)
	var nm := Label.new()
	nm.text = str(it.get("name", "?"))
	nm.add_theme_font_size_override("font_size", 14)
	nm.add_theme_color_override("font_color", col)
	nm.clip_text = true
	box.add_child(nm)
	# status: 🛡 Protegido (ícone) / Desprotegido + dica de ação
	if guarded:
		box.add_child(UiKit.icon_text("🛡 Protegido — tocar p/ remover", 12, UiKit.OK, 16))
	else:
		box.add_child(UiKit.icon_text("🔒 Tocar para proteger", 12, UiKit.TEXT_DIM, 16))
	return res[0]

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
